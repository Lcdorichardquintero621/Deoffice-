package com.deoffice.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.*
import android.print.*
import android.provider.OpenableColumns
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import java.io.*

class MainActivity : Activity() {
    private lateinit var box: LinearLayout
    private var name = ""; private var kind = ""; private var uri: Uri? = null
    private var orig = ""; private var edit: EditText? = null; private var bmp: Bitmap? = null
    private var writer: ((OutputStream) -> Unit)? = null
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFEFF9F7.toInt()) }
        root.addView(ImageView(this).apply { setImageResource(R.drawable.header); adjustViewBounds = true }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val bar = LinearLayout(this).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        fun btn(t: String, a: () -> Unit) = bar.addView(Button(this).apply { text = t; isAllCaps = false; textSize = 11f; setOnClickListener { a() } }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        btn("Abrir") { open() }; btn("Guardar") { saveCopy() }; btn("Convertir") { convert() }; btn("Imprimir") { print() }; btn("Nube") { askKey() }
        root.addView(bar)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        info("Toca «Abrir» para elegir un archivo Word (.docx), Excel (.xlsx), PDF, TXT/CSV o imagen.")
    }

    private fun info(s: String) { box.removeAllViews(); box.addView(TextView(this).apply { text = s; textSize = 15f }) }

    private fun open() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/pdf", "text/*", "image/*")), 1)

    override fun onActivityResult(rq: Int, rs: Int, d: Intent?) {
        val u = d?.data ?: return
        if (rs != RESULT_OK) return
        if (rq == 1) load(u)
        else if (rq == 2) {
            val w = writer ?: return
            toast("Procesando…")
            Thread {
                try { contentResolver.openOutputStream(u)!!.use { w(it) }; runOnUiThread { toast("Copia guardada") } }
                catch (e: Exception) { runOnUiThread { toast("Error: ${e.message}") } }
            }.start()
        }
    }

    private fun load(u: Uri) {
        try {
            name = contentResolver.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null } ?: "archivo"
            uri = u; edit = null; bmp = null; box.removeAllViews()
            val ins = { contentResolver.openInputStream(u)!! }
            when (Conv.ext(name)) {
                "docx" -> { kind = "doc"; text(Conv.readDocx(ins())) }
                "xlsx" -> { kind = "sheet"; text(Conv.readXlsx(ins())) }
                "txt" -> { kind = "txt"; text(ins().bufferedReader().use { it.readLines() }) }
                "csv" -> { kind = "sheet"; text(ins().bufferedReader().use { it.readLines() }.map { it.replace(',', '\t') }) }
                "pdf" -> { kind = "pdf"; showPdf(u) }
                "png", "jpg", "jpeg", "webp", "bmp" -> { kind = "img"; bmp = BitmapFactory.decodeStream(ins()); box.addView(ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true }) }
                else -> { kind = ""; info("Formato no compatible. Los .doc/.xls antiguos deben guardarse antes como .docx/.xlsx.") }
            }
        } catch (ex: Exception) { kind = ""; info("No se pudo abrir: ${ex.message}") }
    }

    private fun text(l: List<String>) {
        orig = l.joinToString("\n")
        edit = EditText(this).apply { setText(l.joinToString("\n")); setBackgroundColor(Color.WHITE); gravity = android.view.Gravity.TOP; textSize = 14f }
        box.addView(edit, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun showPdf(u: Uri) {
        val pfd = contentResolver.openFileDescriptor(u, "r")!!; val r = PdfRenderer(pfd); val w = resources.displayMetrics.widthPixels
        for (i in 0 until r.pageCount) {
            val pg = r.openPage(i); val bm = Bitmap.createBitmap(w, w * pg.height / pg.width, Bitmap.Config.ARGB_8888)
            bm.eraseColor(Color.WHITE); pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); pg.close()
            box.addView(ImageView(this).apply { setImageBitmap(bm); adjustViewBounds = true; setPadding(0, 0, 0, dp(8)) })
        }
        r.close(); pfd.close()
    }

    private fun lines() = edit?.text?.toString()?.split("\n") ?: emptyList()

    private fun mime(f: String) = when (f) {
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pdf" -> "application/pdf"; "txt" -> "text/plain"; "png" -> "image/png"; "jpg" -> "image/jpeg"; "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun writerFor(f: String): (OutputStream) -> Unit {
        val ls = lines(); val b = bmp; val u = uri; val me = this
        return when (f) {
            "docx" -> fun(o: OutputStream) { Conv.writeDocx(ls, o) }
            "xlsx" -> fun(o: OutputStream) { Conv.writeXlsx(ls, o) }
            "txt" -> fun(o: OutputStream) { o.write(ls.joinToString("\n").toByteArray()) }
            "pdf" -> when (kind) {
                "img" -> fun(o: OutputStream) { Conv.imageToPdf(b!!, o) }
                "pdf" -> fun(o: OutputStream) { contentResolver.openInputStream(u!!)!!.use { it.copyTo(o) } }
                else -> fun(o: OutputStream) { Conv.textToPdf(ls, o) }
            }
            else -> if (kind == "pdf") fun(o: OutputStream) { Conv.pdfToImages(me, u!!, f, o) } else fun(o: OutputStream) { Conv.saveBitmap(b!!, f, o) }
        }
    }

    // Siempre pregunta dónde guardar la copia
    private fun export(f: String) {
        val cloud = f.startsWith("c:"); val fmt = f.removePrefix("c:")
        val zip = !cloud && kind == "pdf" && (fmt == "png" || fmt == "jpg")
        writer = if (cloud) cloudWriter(fmt) else writerFor(fmt)
        val base = name.substringBeforeLast('.')
        val title = if (zip) base + "_paginas.zip" else base + (if (fmt == Conv.ext(name)) "_copia" else "") + "." + fmt
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (zip) "application/zip" else mime(fmt)).putExtra(Intent.EXTRA_TITLE, title), 2)
    }

    private fun prefs() = getSharedPreferences("deoffice", MODE_PRIVATE)

    // Si editaste el texto, se envía a la nube tu versión editada; si no, el archivo original (conserva su formato)
    private fun cloudWriter(to: String): (OutputStream) -> Unit {
        val key = prefs().getString("key", "") ?: ""; val u = uri!!; val n = name
        val dirty = edit != null && edit!!.text.toString() != orig && (kind == "doc" || kind == "sheet")
        val from = if (dirty) (if (kind == "doc") "docx" else "xlsx") else Conv.ext(n)
        val data = if (dirty) ByteArrayOutputStream().also { writerFor(from)(it) }.toByteArray() else null
        val upName = n.substringBeforeLast('.') + "." + from
        return fun(o: OutputStream) {
            Cloud.convert(key, upName, data?.inputStream() ?: contentResolver.openInputStream(u)!!, from, to, o)
        }
    }

    private fun askKey(then: (() -> Unit)? = null) {
        val e = EditText(this).apply { setText(prefs().getString("key", "")); hint = "Clave API de CloudConvert"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Conversión en la nube")
            .setMessage("Crea una cuenta gratis en cloudconvert.com y genera una clave API (permisos task.read y task.write). Pégala aquí; se guarda solo en tu teléfono.")
            .setView(e).setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ -> prefs().edit().putString("key", e.text.toString().trim()).apply(); then?.invoke() }.show()
    }

    private fun pick(f: String) {
        if (f.startsWith("c:") && (prefs().getString("key", "") ?: "").isBlank()) askKey { export(f) } else export(f)
    }

    private fun convert() {
        val o: List<Pair<String, String>> = when (kind) {
            "doc" -> listOf("PDF" to "pdf", "XLSX" to "xlsx", "TXT" to "txt", "PDF ☁ (conserva el formato)" to "c:pdf")
            "sheet" -> listOf("PDF" to "pdf", "DOCX" to "docx", "TXT" to "txt", "PDF ☁ (conserva el formato)" to "c:pdf")
            "txt" -> listOf("PDF" to "pdf", "DOCX" to "docx", "XLSX" to "xlsx")
            "pdf" -> listOf("PNG" to "png", "JPG" to "jpg", "DOCX ☁" to "c:docx", "XLSX ☁" to "c:xlsx")
            "img" -> listOf("PNG" to "png", "JPG" to "jpg", "WEBP" to "webp", "PDF" to "pdf")
            else -> return toast("Abre un archivo primero")
        }
        AlertDialog.Builder(this).setTitle("Convertir a…").setItems(o.map { it.first }.toTypedArray()) { _, i -> pick(o[i].second) }.show()
    }

    // Abre el diálogo de impresión de Android: impresoras WiFi/red/Mopria, WiFi Direct o "Guardar como PDF"
    private fun print() {
        if (kind == "") return toast("Abre un archivo primero")
        val w = writerFor("pdf"); val f = File(cacheDir, "imprimir.pdf"); val n = name
        Thread {
            try {
                FileOutputStream(f).use { w(it) }
                runOnUiThread { (getSystemService(PRINT_SERVICE) as PrintManager).print(n, FileAdapter(f), null) }
            } catch (e: Exception) { runOnUiThread { toast("Error: ${e.message}") } }
        }.start()
    }

    private class FileAdapter(val f: File) : PrintDocumentAdapter() {
        override fun onLayout(o: PrintAttributes?, n: PrintAttributes?, c: CancellationSignal?, cb: LayoutResultCallback, e: Bundle?) {
            if (c?.isCanceled == true) { cb.onLayoutCancelled(); return }
            cb.onLayoutFinished(PrintDocumentInfo.Builder(f.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(), true)
        }
        override fun onWrite(p: Array<out PageRange>?, d: ParcelFileDescriptor, c: CancellationSignal?, cb: WriteResultCallback) {
            FileInputStream(f).use { i -> FileOutputStream(d.fileDescriptor).use { o -> i.copyTo(o) } }
            cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        }
    }
}
