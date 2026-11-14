package com.deoffice.app

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.*
import java.util.zip.*

object Conv {
    fun ext(n: String) = n.substringAfterLast('.', "").lowercase()

    private fun unzip(ins: InputStream): Map<String, ByteArray> {
        val m = HashMap<String, ByteArray>()
        ZipInputStream(ins).use { z ->
            var e = z.nextEntry
            while (e != null) { m[e.name] = z.readBytes(); e = z.nextEntry }
        }
        return m
    }

    private fun parse(b: ByteArray, f: (XmlPullParser, Int) -> Unit) {
        val p = Xml.newPullParser(); p.setInput(ByteArrayInputStream(b), "UTF-8")
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) { f(p, ev); ev = p.next() }
    }

    // ---------- Lectura: Word y Excel (párrafos y tablas; las celdas se separan con tabulador) ----------
    fun readDocx(ins: InputStream): List<String> {
        val x = unzip(ins)["word/document.xml"] ?: return emptyList()
        val out = ArrayList<String>(); val par = StringBuilder()
        var row: ArrayList<String>? = null; var cell: StringBuilder? = null; var inT = false
        parse(x) { p, ev ->
            val cur = cell ?: par
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:tr" -> row = ArrayList()
                    "w:tc" -> cell = StringBuilder()
                    "w:t" -> inT = true
                    "w:tab" -> cur.append(if (cell != null) " " else "\t")
                }
                XmlPullParser.TEXT -> if (inT) cur.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "w:t" -> inT = false
                    "w:p" -> if (cell == null) { out.add(par.toString()); par.setLength(0) } else cell!!.append(' ')
                    "w:tc" -> { row?.add(cell.toString().trim()); cell = null }
                    "w:tr" -> { row?.let { out.add(it.joinToString("\t")) }; row = null }
                }
            }
        }
        return out
    }

    fun readXlsx(ins: InputStream): List<String> {
        val z = unzip(ins); val ss = ArrayList<String>()
        z["xl/sharedStrings.xml"]?.let { b ->
            val sb = StringBuilder(); var inT = false
            parse(b) { p, ev ->
                when (ev) {
                    XmlPullParser.START_TAG -> if (p.name == "si") sb.setLength(0) else if (p.name == "t") inT = true
                    XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                    XmlPullParser.END_TAG -> if (p.name == "t") inT = false else if (p.name == "si") ss.add(sb.toString())
                }
            }
        }
        val key = z.keys.filter { it.startsWith("xl/worksheets/sheet") }.sorted().firstOrNull() ?: return emptyList()
        val out = ArrayList<String>(); var cells = ArrayList<String>()
        var ref = ""; var type = ""; var txt = StringBuilder(); var cap = false
        parse(z[key]!!) { p, ev ->
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> cells = ArrayList()
                    "c" -> { ref = p.getAttributeValue(null, "r") ?: ""; type = p.getAttributeValue(null, "t") ?: ""; txt = StringBuilder() }
                    "v", "t" -> cap = true
                }
                XmlPullParser.TEXT -> if (cap) txt.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> cap = false
                    "c" -> {
                        val i = ref.takeWhile { it.isLetter() }.fold(0) { a, c -> a * 26 + (c - 'A' + 1) } - 1
                        while (cells.size < i) cells.add("")
                        val s = txt.toString()
                        cells.add(if (type == "s") ss.getOrElse(s.toIntOrNull() ?: -1) { "" } else s)
                    }
                    "row" -> out.add(cells.joinToString("\t"))
                }
            }
        }
        return out
    }

    // ---------- Escritura: DOCX y XLSX ----------
    private const val H = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val PK = "http://schemas.openxmlformats.org/package/2006/"
    private const val OD = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun ct(vararg o: Pair<String, String>) = H + "<Types xmlns=\"${PK}content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" + o.joinToString("") { "<Override PartName=\"${it.first}\" ContentType=\"${it.second}\"/>" } + "</Types>"
    private fun rel(type: String, target: String) = "$H<Relationships xmlns=\"${PK}relationships\"><Relationship Id=\"rId1\" Type=\"$OD/$type\" Target=\"$target\"/></Relationships>"
    private fun pack(os: OutputStream, f: Map<String, String>) {
        ZipOutputStream(os).use { z -> f.forEach { (k, v) -> z.putNextEntry(ZipEntry(k)); z.write(v.toByteArray()); z.closeEntry() } }
    }
    private fun col(i: Int): String { var n = i + 1; var s = ""; while (n > 0) { s = ('A' + (n - 1) % 26) + s; n = (n - 1) / 26 }; return s }

    fun writeDocx(lines: List<String>, os: OutputStream) {
        val body = lines.joinToString("") { l -> "<w:p>" + l.split("\t").joinToString("<w:r><w:tab/></w:r>") { "<w:r><w:t xml:space=\"preserve\">${esc(it)}</w:t></w:r>" } + "</w:p>" }
        pack(os, mapOf(
            "[Content_Types].xml" to ct("/word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"),
            "_rels/.rels" to rel("officeDocument", "word/document.xml"),
            "word/document.xml" to "$H<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body</w:body></w:document>"))
    }

    fun writeXlsx(lines: List<String>, os: OutputStream) {
        val num = Regex("-?\\d+(\\.\\d+)?")
        val rows = lines.mapIndexed { r, l ->
            "<row r=\"${r + 1}\">" + l.split("\t").mapIndexed { c, v ->
                val a = col(c) + (r + 1)
                if (v.isEmpty()) "" else if (num.matches(v)) "<c r=\"$a\"><v>$v</v></c>" else "<c r=\"$a\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>"
            }.joinToString("") + "</row>"
        }.joinToString("")
        val m = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        pack(os, mapOf(
            "[Content_Types].xml" to ct("/xl/workbook.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml", "/xl/worksheets/sheet1.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"),
            "_rels/.rels" to rel("officeDocument", "xl/workbook.xml"),
            "xl/workbook.xml" to "$H<workbook xmlns=\"$m\" xmlns:r=\"$OD\"><sheets><sheet name=\"Hoja1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to rel("worksheet", "worksheets/sheet1.xml"),
            "xl/worksheets/sheet1.xml" to "$H<worksheet xmlns=\"$m\"><sheetData>$rows</sheetData></worksheet>"))
    }

    // ---------- PDF e imágenes ----------
    fun textToPdf(lines: List<String>, os: OutputStream) {
        val w = 595; val h = 842; val m = 40; val uw = (w - 2 * m).toFloat()
        val paint = TextPaint().apply { textSize = 11f; color = Color.BLACK; isAntiAlias = true }
        val line = Paint().apply { style = Paint.Style.STROKE; color = Color.GRAY; strokeWidth = 0.7f }
        val d = PdfDocument(); var n = 1
        var page = d.startPage(PdfDocument.PageInfo.Builder(w, h, n).create()); var y = m.toFloat()
        for (l in lines) {
            val cells = l.split("\t"); val cw = uw / cells.size
            val ls = cells.map { val t = it.ifEmpty { " " }; StaticLayout.Builder.obtain(t, 0, t.length, paint, (cw - 8).toInt()).build() }
            val rh = ls.maxOf { it.height } + 6f
            if (y + rh > h - m) { d.finishPage(page); n++; page = d.startPage(PdfDocument.PageInfo.Builder(w, h, n).create()); y = m.toFloat() }
            ls.forEachIndexed { i, s ->
                val c = page.canvas; c.save(); c.translate(m + i * cw + 4, y + 3); s.draw(c); c.restore()
                if (cells.size > 1) c.drawRect(m + i * cw, y, m + (i + 1) * cw, y + rh, line)
            }
            y += rh
        }
        d.finishPage(page); d.writeTo(os); d.close()
    }

    fun imageToPdf(b: Bitmap, os: OutputStream) {
        val d = PdfDocument(); val pg = d.startPage(PdfDocument.PageInfo.Builder(b.width, b.height, 1).create())
        pg.canvas.drawColor(Color.WHITE); pg.canvas.drawBitmap(b, 0f, 0f, null); d.finishPage(pg); d.writeTo(os); d.close()
    }

    @Suppress("DEPRECATION")
    private fun cf(f: String) = when (f) { "jpg" -> Bitmap.CompressFormat.JPEG; "webp" -> Bitmap.CompressFormat.WEBP; else -> Bitmap.CompressFormat.PNG }

    fun saveBitmap(b: Bitmap, f: String, os: OutputStream) {
        val x = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        if (f == "jpg") Canvas(x).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) } else Canvas(x).drawBitmap(b, 0f, 0f, null)
        x.compress(cf(f), 95, os)
    }

    fun pdfToImages(ctx: Context, u: Uri, f: String, os: OutputStream) {
        val pfd = ctx.contentResolver.openFileDescriptor(u, "r")!!; val r = PdfRenderer(pfd); val z = ZipOutputStream(os)
        for (i in 0 until r.pageCount) {
            val pg = r.openPage(i); val bm = Bitmap.createBitmap(pg.width * 2, pg.height * 2, Bitmap.Config.ARGB_8888)
            bm.eraseColor(Color.WHITE); pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT); pg.close()
            z.putNextEntry(ZipEntry("pagina_${i + 1}.$f")); bm.compress(cf(f), 95, z); z.closeEntry()
        }
        z.finish(); r.close(); pfd.close()
    }
}
