package com.lilyroksanne.mon

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Network
import android.net.Uri
import android.text.Html
import com.lilyroksanne.mon.util.MonLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

internal class MusicDownloads(private val context: Context, private val network: Network? = null) {
    data class Result(val id: String, val title: String, val extension: String, val resourceKey: String?)
    data class SavedTrack(val file: File, val title: String, val genre: String?, val added: Boolean = false)

    val index = context.getSharedPreferences("DriveMusicIndex", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "rolas")

    suspend fun search(query: String): List<Result> {
        val needle = normalized(query)
        if (needle.isBlank()) return emptyList()
        return listFiles().mapNotNull { file ->
            val candidate = normalized(file.title)
            val score = when {
                candidate == needle -> 0
                candidate.contains(needle) -> 1
                needle.split(' ').all { it in candidate.split(' ') } -> 2
                else -> return@mapNotNull null
            }
            score to file
        }.sortedBy { it.first }.take(8).map { it.second }
    }

    suspend fun sync(): Boolean {
        var complete = true
        for (file in listFiles()) {
            currentCoroutineContext().ensureActive()
            try {
                download(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                complete = false
                MonLogger.e("DriveMusic", "Fallo al sincronizar ${file.title}", error)
            }
        }
        if (complete) index.edit().putLong(LAST_SYNC, System.currentTimeMillis()).apply()
        return complete
    }

    fun savedTracks(): List<SavedTrack> = index.all.keys
        .filter { it.startsWith(FILE_PREFIX) }
        .sorted()
        .mapNotNull { savedTrack(it) }

    private suspend fun listFiles(): List<Result> {
        val folders = ArrayDeque<Pair<String, String?>>().apply { add(FOLDER_ID to null) }
        val visited = mutableSetOf<String>()
        val tracks = linkedMapOf<String, Result>()
        while (folders.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (id, resourceKey) = folders.removeFirst()
            if (!visited.add(id)) continue
            val address = Uri.parse("https://drive.google.com/embeddedfolderview").buildUpon()
                .appendQueryParameter("id", id)
                .apply { resourceKey?.let { appendQueryParameter("resourcekey", it) } }
                .build()
            val connection = open(address.toString())
            val html = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            check(html.contains("class=\"flip-entries\"")) { "Drive no devolvió la lista pública." }
            for (anchor in LINKS.findAll(html)) {
                val link = Uri.parse(decodeHtml(anchor.groupValues[1]))
                if (link.scheme != "https" || link.host != "drive.google.com") continue
                val name = TITLE.find(anchor.groupValues[2])?.groupValues?.get(1)
                    ?.let(::decodeHtml)?.trim().orEmpty()
                val parts = link.pathSegments
                val idFromLink = parts.getOrNull(2)?.takeIf { ID.matches(it) } ?: continue
                val key = link.getQueryParameter("resourcekey")
                when {
                    parts.take(2) == listOf("drive", "folders") -> folders.add(idFromLink to key)
                    parts.take(2) == listOf("file", "d") && name.isNotBlank() -> {
                        val extension = name.substringAfterLast('.').lowercase(Locale.ROOT)
                        if (extension in AUDIO_EXTENSIONS) tracks[idFromLink] =
                            Result(idFromLink, name.substringBeforeLast('.'), extension, key)
                    }
                }
            }
        }
        return tracks.values.toList()
    }

    suspend fun download(result: Result): SavedTrack = downloadLock.withLock {
        val indexKey = FILE_PREFIX + result.id
        savedTrack(indexKey)?.let { return@withLock it }
        directory.mkdirs()
        val pending = File.createTempFile("download-", ".${result.extension}", directory)
        try {
            val address = Uri.parse("https://drive.usercontent.google.com/download").buildUpon()
                .appendQueryParameter("id", result.id)
                .appendQueryParameter("export", "download")
                .appendQueryParameter("confirm", "t")
                .apply { result.resourceKey?.let { appendQueryParameter("resourcekey", it) } }
                .build()
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = open(address.toString())
            try {
                require(!connection.contentType.orEmpty().startsWith("text/")) { "Drive no entregó el audio." }
                require(connection.contentLengthLong <= MAX_BYTES) { "La pista supera 200 MB." }
                connection.inputStream.buffered().use { input ->
                    pending.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            require(total <= MAX_BYTES) { "La pista supera 200 MB." }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val saved = File(directory, "$hash.${result.extension}")
            val added = !saved.exists()
            val genre = readAudioGenre(if (added) pending else saved)
            if (added) check(pending.renameTo(saved))
            index.edit().putString(indexKey, JSONObject()
                .put("file", saved.name).put("title", result.title.take(160))
                .put("genre", genre.orEmpty()).toString()).apply()
            SavedTrack(saved, result.title.take(160), genre, added)
        } finally {
            pending.delete()
        }
    }

    fun savedTrack(key: String): SavedTrack? {
        if (!key.startsWith(FILE_PREFIX)) return null
        val value = index.getString(key, null) ?: return null
        val record = runCatching { JSONObject(value) }.getOrNull() ?: return null
        val filename = record.optString("file")
        if (!LOCAL_NAME.matches(filename)) return null
        val file = File(directory, filename)
        if (!file.isFile) return null
        return SavedTrack(file, record.optString("title"), record.optString("genre").ifBlank { null })
    }

    private fun readAudioGenre(file: File): String? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val formats = (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
            }
            require(formats.any { it.startsWith("audio/") } && formats.none { it.startsWith("video/") })
        } finally {
            extractor.release()
        }
        return runCatching {
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(file.path)
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            } finally {
                metadata.release()
            }
        }.getOrNull()
    }

    private fun open(address: String): HttpsURLConnection {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https" && url.userInfo == null &&
                (url.host in setOf("drive.google.com", "drive.usercontent.google.com") ||
                    url.host.endsWith(".googleusercontent.com")))
            val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 12_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "MonEstereo/2.6 (Android music player)")
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    url = URL(url, checkNotNull(connection.getHeaderField("Location")))
                } else {
                    check(status in 200..299) { "Drive respondió con el código $status." }
                    return connection
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        error("Drive devolvió demasiadas redirecciones.")
    }

    private fun decodeHtml(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()

    private fun normalized(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    companion object {
        const val LAST_SYNC = "lastSync"
        private const val FILE_PREFIX = "file:"
        private const val FOLDER_ID = "1tie4ifdAoqr4sF_yOsKjwvPIX59E_uV1"
        private const val MAX_BYTES = 200L * 1024 * 1024
        private val downloadLock = Mutex()
        private val LINKS = Regex("""<a\b[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        private val TITLE = Regex("""<div\b[^>]*class="flip-entry-title"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        private val ID = Regex("[A-Za-z0-9_-]+")
        private val LOCAL_NAME = Regex("[a-f0-9]{64}\\.[a-z0-9]+")
        private val AUDIO_EXTENSIONS = setOf("mp3", "ogg", "oga", "opus", "flac", "wav", "m4a", "aac", "webm")
    }
}
