package com.lilyroksanne.mon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

internal class AlbumArtwork(private val context: Context) {
    private data class Metadata(val title: String, val artist: String, val album: String, val picture: ByteArray?)
    private data class Release(val id: String, val score: Int)

    suspend fun load(track: Uri, fallbackTitle: String): Bitmap? {
        val directory = File(context.cacheDir, "album-art").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-256").digest(track.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cached = File(directory, "$key.jpg")
        if (cached.isFile) {
            decodeFileCompact(cached.path)?.let {
                cached.setLastModified(System.currentTimeMillis())
                return it
            }
        }
        val tags = readMetadata(track, fallbackTitle)
        val bitmap = tags.picture?.let(::decode) ?: fetchCover(tags) ?: return null
        runCatching {
            val pending = File.createTempFile("cover-", ".tmp", directory)
            try {
                pending.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                pending.renameTo(cached)
            } finally {
                pending.delete()
            }
            directory.listFiles { file -> file.extension == "jpg" }
                ?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
        }
        return bitmap
    }

    private fun readMetadata(track: Uri, fallback: String): Metadata {
        val parts = fallback.replace('_', ' ').split(" - ", limit = 2)
        val guessedTitle = parts.last()
        val guessedArtist = if (parts.size == 2) parts.first() else ""
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, track)
                Metadata(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() } ?: guessedTitle,
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?.takeIf { it.isNotBlank() } ?: guessedArtist,
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                    retriever.embeddedPicture
                )
            } finally {
                retriever.release()
            }
        }.getOrDefault(Metadata(guessedTitle, guessedArtist, "", null))
    }

    private suspend fun fetchCover(tags: Metadata): Bitmap? {
        val title = normalized(tags.title)
        if (title.isBlank()) return null
        val query = buildString {
            append("recording:\"").append(quote(tags.title)).append('"')
            if (tags.artist.isNotBlank()) append(" AND artist:\"").append(quote(tags.artist)).append('"')
        }
        val url = Uri.parse("https://musicbrainz.org/ws/2/recording/").buildUpon()
            .appendQueryParameter("fmt", "json")
            .appendQueryParameter("query", query)
            .appendQueryParameter("limit", "6")
            .build()
        val json = rateLimit.withLock {
            val wait = 1_100L - (SystemClock.elapsedRealtime() - previousRequest)
            if (wait > 0) delay(wait)
            previousRequest = SystemClock.elapsedRealtime()
            get(url.toString(), setOf("musicbrainz.org"), 512 * 1024)
        } ?: return null
        val records = JSONObject(json.toString(Charsets.UTF_8)).optJSONArray("recordings") ?: return null
        val album = normalized(tags.album)
        val releases = mutableListOf<Release>()
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            if (normalized(record.optString("title")) != title) continue
            val entries = record.optJSONArray("releases") ?: continue
            for (j in 0 until entries.length()) {
                val release = entries.optJSONObject(j) ?: continue
                val id = release.optString("id")
                if (!RELEASE_ID.matches(id)) continue
                val albumMatches = album.isNotBlank() && normalized(release.optString("title")) == album
                val official = release.optString("status").equals("Official", ignoreCase = true)
                val score = record.optInt("score") + (if (albumMatches) 150 else 0) + (if (official) 10 else 0)
                releases.add(Release(id, score))
            }
        }
        return releases.sortedByDescending { it.score }.distinctBy { it.id }.take(4).firstNotNullOfOrNull { release ->
            get("https://coverartarchive.org/release/${release.id}/front-250", COVER_HOSTS, 2 * 1024 * 1024)
                ?.let(::decode)
                ?: get("https://coverartarchive.org/release/${release.id}/front-500", COVER_HOSTS, 3 * 1024 * 1024)
                    ?.let(::decode)
        }
    }

    private fun get(address: String, hosts: Set<String>, limit: Int): ByteArray? {
        var url = URL(address)
        repeat(5) {
            if (url.protocol != "https" || url.userInfo != null ||
                hosts.none { url.host == it || url.host.endsWith(".$it") }) return null
            val connection = url.openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 6_000
                connection.setRequestProperty("User-Agent", "MonEstereo/2.8 (https://github.com/liliroksanne/moncarplay)")
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: return null
                    url = URL(url, location.replaceFirst(Regex("^http://"), "https://"))
                } else {
                    if (status !in 200..299 || connection.contentLengthLong > limit) return null
                    return connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (output.size() + count > limit) return null
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun decodeFileCompact(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
        if (edge <= 0) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = calculateSampleSize(edge)
        }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
        if (edge <= 0) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = calculateSampleSize(edge)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateSampleSize(edge: Int): Int {
        var sample = 1
        while (edge / sample > MAX_EDGE) {
            sample *= 2
        }
        return sample
    }

    private fun quote(value: String) = value.take(160).replace("\\", "\\\\").replace("\"", "\\\"")

    private fun normalized(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private companion object {
        const val MAX_EDGE = 480
        val rateLimit = Mutex()
        var previousRequest = -1_100L
        val RELEASE_ID = Regex("[a-fA-F0-9-]{36}")
        val COVER_HOSTS = setOf("coverartarchive.org", "archive.org")
    }
}
