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

    suspend fun sync(report: DriveSyncStatus = DriveSyncStatus): Boolean {
        if (!syncLock.tryLock()) return true
        try {
            report.searching()
            val allFiles = listFiles()
            val files = allFiles.filter { savedTrack(FILE_PREFIX + it.id) == null }
            report.foundSummary(allFiles.size, files.size)

            var complete = true
            for ((idx, file) in files.withIndex()) {
                currentCoroutineContext().ensureActive()
                report.downloading(file.title, idx + 1, files.size)
                try {
                    val saved = download(file) { pct, bytes -> report.progress(pct, bytes) }
                    if (saved.added) report.downloaded(saved.title)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    complete = false
                    report.failure(file.title, error)
                    MonLogger.e("DriveMusic", "Fallo al sincronizar ${file.title}", error)
                }
            }
            if (complete) index.edit().putLong(LAST_SYNC, System.currentTimeMillis()).apply()
            report.finished(context)
            return complete
        } catch (cancelled: CancellationException) {
            report.waitingAfterInterruption()
            throw cancelled
        } catch (error: Exception) {
            report.failure("Google Drive", error)
            report.finished(context)
            MonLogger.e("DriveMusic", "No se pudo consultar la carpeta pública", error)
            return false
        } finally {
            syncLock.unlock()
        }
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
                .fragment("list")
                .build()

            val connection = open(address.toString())
            val html = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            if (html.contains("ServiceLogin") || html.contains("accounts.google.com/v3/signin")) {
                error("Drive pidió inicio de sesión; verifica que la carpeta sea pública.")
            }

            check(html.contains("flip-entries") || html.contains("flip-entry") || html.contains("embeddedfolderview")) {
                "Drive devolvió una página inesperada (sin lista de carpeta)."
            }

            for (anchor in LINKS.findAll(html)) {
                val rawHref = decodeHtml(anchor.groupValues[1])
                val link = runCatching { Uri.parse(rawHref) }.getOrNull() ?: continue
                if (link.scheme != "https" || link.host !in setOf("drive.google.com", "docs.google.com")) continue

                val body = anchor.groupValues[2]
                val name = TITLE.find(body)?.groupValues?.get(1)
                    ?.let(::decodeHtml)?.trim()
                    ?.ifBlank { null }
                    ?: decodeHtml(body.replace(HTML_TAGS, " ")).trim()

                val parts = link.pathSegments
                val key = link.getQueryParameter("resourcekey")

                val folderId = extractFolderId(parts, link)
                if (folderId != null) {
                    folders.add(folderId to key)
                    continue
                }

                val fileId = extractFileId(parts, link) ?: continue
                if (name.isNotBlank()) {
                    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (extension in AUDIO_EXTENSIONS) {
                        tracks[fileId] = Result(fileId, name.substringBeforeLast('.'), extension, key)
                    }
                }
            }
        }
        return tracks.values.toList()
    }

    private fun extractFolderId(parts: List<String>, link: Uri): String? {
        val idx = parts.indexOf("folders")
        if (idx >= 0) {
            return parts.getOrNull(idx + 1)?.takeIf { ID.matches(it) }
        }
        if (parts.firstOrNull() == "folderview") {
            return link.getQueryParameter("id")?.takeIf { ID.matches(it) }
        }
        return null
    }

    private fun extractFileId(parts: List<String>, link: Uri): String? {
        val fileIdx = parts.indexOf("file")
        if (fileIdx >= 0 && parts.getOrNull(fileIdx + 1) == "d") {
            return parts.getOrNull(fileIdx + 2)?.takeIf { ID.matches(it) }
        }
        if (parts.firstOrNull() == "open" || parts.firstOrNull() == "uc") {
            return link.getQueryParameter("id")?.takeIf { ID.matches(it) }
        }
        return null
    }

    suspend fun download(
        result: Result,
        onProgress: (percent: Int?, bytes: Long) -> Unit = { _, _ -> }
    ): SavedTrack = downloadLock.withLock {
        val indexKey = FILE_PREFIX + result.id
        savedTrack(indexKey)?.let { return@withLock it }
        directory.mkdirs()
        val pending = File.createTempFile("download-", ".${result.extension}", directory)
        try {
            val initialAddress = buildDownloadUrl(result.id, result.resourceKey, confirm = "t", uuid = null)
            val digest = MessageDigest.getInstance("SHA-256")
            var connection = open(initialAddress)
            try {
                // Google Drive sometimes returns an HTML virus-scan / confirmation form even with confirm=t
                if (connection.contentType.orEmpty().lowercase(Locale.ROOT).startsWith("text/")) {
                    val html = connection.inputStream.bufferedReader().use { reader ->
                        val chars = CharArray(65536)
                        val read = reader.read(chars)
                        if (read > 0) String(chars, 0, read) else ""
                    }
                    connection.disconnect()

                    val uuid = HIDDEN_UUID.find(html)?.groupValues?.get(1)
                    val confirmToken = HIDDEN_CONFIRM.find(html)?.groupValues?.get(1)
                    if (uuid != null || confirmToken != null) {
                        val confirmUrl = buildDownloadUrl(
                            result.id,
                            result.resourceKey,
                            confirm = confirmToken ?: "t",
                            uuid = uuid
                        )
                        connection = open(confirmUrl)
                    } else {
                        val pageTitle = HTML_TITLE.find(html)?.groupValues?.get(1)?.let(::decodeHtml)?.trim()
                        val detail = if (!pageTitle.isNullOrBlank()) " ($pageTitle)" else ""
                        error("Drive devolvió HTML en lugar del audio$detail.")
                    }
                }

                val contentType = connection.contentType.orEmpty().lowercase(Locale.ROOT)
                require(!contentType.startsWith("text/")) {
                    "Drive no entregó el audio (recibido $contentType)."
                }

                val size = connection.contentLengthLong
                var reportedPct = -1
                var lastReportedBytes = 0L
                require(size <= MAX_BYTES) { "La pista supera 200 MB." }

                connection.inputStream.buffered().use { input ->
                    pending.outputStream().buffered().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            require(total <= MAX_BYTES) { "La pista supera 200 MB." }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            if (size > 0) {
                                val percent = ((100L * total) / size).toInt().coerceIn(0, 100)
                                if (percent != reportedPct) {
                                    reportedPct = percent
                                    onProgress(percent, total)
                                }
                            } else if (total - lastReportedBytes >= 128 * 1024) {
                                lastReportedBytes = total
                                onProgress(null, total)
                            }
                        }
                        require(total > 1024L) { "Archivo descargado vacío o corrupto (${total}B)." }
                    }
                }
            } finally {
                connection.disconnect()
            }

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val saved = File(directory, "$hash.${result.extension}")
            val added = !saved.exists()
            val genre = readAudioGenre(if (added) pending else saved)
            if (added) {
                if (!pending.renameTo(saved)) {
                    pending.copyTo(saved, overwrite = true)
                }
            }
            index.edit().putString(
                indexKey,
                JSONObject()
                    .put("file", saved.name)
                    .put("title", result.title.take(160))
                    .put("genre", genre.orEmpty())
                    .toString()
            ).apply()
            SavedTrack(saved, result.title.take(160), genre, added)
        } finally {
            pending.delete()
        }
    }

    private fun buildDownloadUrl(id: String, resourceKey: String?, confirm: String, uuid: String?): String =
        Uri.parse("https://drive.usercontent.google.com/download").buildUpon()
            .appendQueryParameter("id", id)
            .appendQueryParameter("export", "download")
            .appendQueryParameter("confirm", confirm)
            .apply {
                resourceKey?.let { appendQueryParameter("resourcekey", it) }
                uuid?.let { appendQueryParameter("uuid", it) }
            }
            .build()
            .toString()

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
            // Note: do NOT reject files with an attached album-art image/video track!
            require(formats.any { it.startsWith("audio/") }) {
                "El archivo no contiene pista de audio válida (${formats.joinToString().ifBlank { "sin pistas" }})."
            }
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
        repeat(8) {
            if (url.host == "accounts.google.com") {
                error("Google Drive pidió inicio de sesión (verifica permisos públicos).")
            }
            require(
                url.protocol == "https" && url.userInfo == null &&
                    (url.host in ALLOWED_HOSTS || url.host.endsWith(".googleusercontent.com"))
            ) { "Host inesperado al conectar con Drive: ${url.host}" }

            val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 11; CarPlay) MonEstereo/2.8"
                )
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    val location = connection.getHeaderField("Location")
                        ?: error("Redirección de Drive sin cabecera Location (HTTP $status).")
                    url = URL(url, location)
                } else {
                    check(status in 200..299) { "Drive respondió con código HTTP $status." }
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
        private val ALLOWED_HOSTS = setOf(
            "drive.google.com",
            "docs.google.com",
            "drive.usercontent.google.com"
        )
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val downloadLock = Mutex()
        private val syncLock = Mutex()
        private val LINKS = Regex("""<a\b[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        private val TITLE = Regex("""<div\b[^>]*class="flip-entry-title"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        private val HTML_TAGS = Regex("<[^>]+>")
        private val HTML_TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val HIDDEN_UUID = Regex("""name="uuid"\s+value="([^"]+)"""")
        private val HIDDEN_CONFIRM = Regex("""name="confirm"\s+value="([^"]+)"""")
        private val ID = Regex("[A-Za-z0-9_-]{10,}")
        private val LOCAL_NAME = Regex("[a-f0-9]{64}\\.[a-z0-9]+")
        private val AUDIO_EXTENSIONS = setOf("mp3", "ogg", "oga", "opus", "flac", "wav", "m4a", "aac", "webm")
    }
}
