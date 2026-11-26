package com.lilyroksanne.mon

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class MusicDownloads(private val context: Context) {
    data class Result(val title: String, val url: String)

    fun search(query: String): List<Result> {
        val request = Uri.parse("https://commons.wikimedia.org/w/api.php").buildUpon()
            .appendQueryParameter("action", "query")
            .appendQueryParameter("format", "json")
            .appendQueryParameter("formatversion", "2")
            .appendQueryParameter("generator", "search")
            .appendQueryParameter("gsrnamespace", "6")
            .appendQueryParameter("gsrsearch", "$query filetype:audio")
            .appendQueryParameter("gsrlimit", "8")
            .appendQueryParameter("prop", "imageinfo")
            .appendQueryParameter("iiprop", "url|mime")
            .build()
        val pages = connect(request.toString()) { connection ->
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(json).optJSONObject("query")?.optJSONArray("pages")
        } ?: return emptyList()
        return (0 until pages.length()).mapNotNull { index ->
            val page = pages.getJSONObject(index)
            val file = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
            val title = page.getString("title").removePrefix("File:").substringBeforeLast('.')
            val url = file.optString("url")
            if (Uri.parse(url).host == "upload.wikimedia.org" && file.optString("mime").startsWith("audio/")) {
                page.optInt("index", Int.MAX_VALUE) to Result(title, url)
            } else null
        }.sortedBy { it.first }.map { it.second }
    }

    fun download(url: String, title: String?): Pair<File, String> {
        val source = File.createTempFile("mon-download-", ".media", context.cacheDir)
        var pending: File? = null
        try {
            val filename = connect(url) { connection ->
                require(connection.contentLengthLong <= MAX_FILE_BYTES) { "El archivo supera 50 MB." }
                val type = connection.contentType.orEmpty()
                require(!type.startsWith("text/") && !type.startsWith("application/json")) {
                    "Pásame el enlace directo a un archivo de audio o MP4."
                }
                source.outputStream().buffered().use { copyMedia(connection, it) }
                Uri.parse(connection.url.toString()).lastPathSegment.orEmpty()
            }
            val directory = File(context.filesDir, "mp3").apply { mkdirs() }
            val output = File.createTempFile("track-", ".mp3", directory)
            pending = output
            Mp3Conversion.writeMp3(context, source, output)
            pending = null
            val label = title ?: filename.substringBeforeLast('.').ifBlank { "Pista descargada" }
            return output to label.take(160)
        } finally {
            source.delete()
            pending?.delete()
        }
    }

    private fun copyMedia(connection: HttpsURLConnection, output: OutputStream) {
        connection.inputStream.buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                require(total <= MAX_FILE_BYTES) { "El archivo supera 50 MB." }
                output.write(buffer, 0, count)
            }
        }
    }

    private fun <T> connect(address: String, use: (HttpsURLConnection) -> T): T {
        var url = URL(address)
        repeat(6) {
            require(url.protocol.equals("https", ignoreCase = true) && url.userInfo == null && url.host.isNotBlank()) {
                "Usa un enlace HTTPS directo."
            }
            val connection = url.openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 12_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "MonEstereo/2.4 (Android music player)")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = checkNotNull(connection.getHeaderField("Location"))
                    url = URL(url, location)
                } else {
                    check(status in 200..299) { "El servidor respondió con el código $status." }
                    return use(connection)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("El enlace tiene demasiadas redirecciones.")
    }

    private companion object {
        const val MAX_FILE_BYTES = 50L * 1024 * 1024
    }
}
