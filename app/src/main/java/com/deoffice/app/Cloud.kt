package com.deoffice.app

import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

// Conversión en la nube con CloudConvert (API v2). La clave la pone el usuario en la app.
object Cloud {
    private const val API = "https://api.cloudconvert.com/v2"

    private fun call(method: String, url: String, key: String, body: String? = null): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 30000; c.readTimeout = 60000
        c.setRequestProperty("Authorization", "Bearer $key")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val txt = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code >= 400) throw IOException(when (code) {
            401 -> "Clave de CloudConvert inválida (toca «Nube» para cambiarla)"
            402, 429 -> "Se agotó el límite gratuito de hoy"
            else -> "Error del servicio ($code)"
        })
        return JSONObject(txt)
    }

    private fun task(d: JSONObject, name: String): JSONObject {
        val a = d.getJSONArray("tasks")
        return (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("name") == name }
    }

    private fun upload(url: String, p: JSONObject, name: String, input: InputStream) {
        val b = "----deoffice" + System.currentTimeMillis()
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.setChunkedStreamingMode(0)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
        c.outputStream.use { o ->
            p.keys().forEach { k -> o.write("--$b\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n${p.getString(k)}\r\n".toByteArray()) }
            o.write("--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            input.use { it.copyTo(o) }
            o.write("\r\n--$b--\r\n".toByteArray())
        }
        if (c.responseCode >= 400) throw IOException("No se pudo subir el archivo")
    }

    fun convert(key: String, name: String, input: InputStream, from: String, to: String, out: OutputStream) {
        if (key.isBlank()) throw IOException("Falta la clave de CloudConvert (botón «Nube»)")
        val job = """{"tasks":{"up":{"operation":"import/upload"},"cv":{"operation":"convert","input":"up","input_format":"$from","output_format":"$to"},"ex":{"operation":"export/url","input":"cv"}}}"""
        val d = call("POST", "$API/jobs", key, job).getJSONObject("data")
        val form = task(d, "up").getJSONObject("result").getJSONObject("form")
        upload(form.getString("url"), form.getJSONObject("parameters"), name, input)
        val id = d.getString("id")
        repeat(90) {
            val s = call("GET", "$API/jobs/$id", key).getJSONObject("data")
            when (s.getString("status")) {
                "finished" -> {
                    val f = task(s, "ex").getJSONObject("result").getJSONArray("files").getJSONObject(0)
                    URL(f.getString("url")).openStream().use { it.copyTo(out) }
                    return
                }
                "error" -> throw IOException("La conversión en la nube falló (formato no admitido)")
            }
            Thread.sleep(2000)
        }
        throw IOException("La conversión tardó demasiado")
    }
}
