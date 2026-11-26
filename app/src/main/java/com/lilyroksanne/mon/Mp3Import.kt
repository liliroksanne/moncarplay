package com.lilyroksanne.mon

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

internal class Mp3Import(private val context: Context) {
    data class Track(val uri: Uri, val title: String, val genre: String?)

    suspend fun copy(uri: Uri): Track {
        require(uri.scheme == "content")
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment.orEmpty()
        val directory = File(context.filesDir, "rolas").apply { mkdirs() }
        val pending = File.createTempFile("import-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = checkNotNull(resolver.openInputStream(uri))
            input.use {
                pending.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        require(total <= 200L * 1024 * 1024)
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(isMp3(pending))
            val metadata = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(pending.path)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) to
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                }
            }.getOrNull()
            val title = metadata?.first?.trim()?.takeIf(String::isNotEmpty)
                ?: name.substringBeforeLast('.').ifBlank { "Pista importada" }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val saved = File(directory, "$id.mp3")
            if (!saved.exists()) check(pending.renameTo(saved))
            return Track(Uri.fromFile(saved), title.take(160), metadata?.second?.take(80))
        } finally {
            pending.delete()
        }
    }

    private fun isMp3(file: File): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return extractor.trackCount == 1 &&
                extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_MPEG &&
                file.inputStream().use { input ->
                    val first = input.read()
                    val second = input.read()
                    val third = input.read()
                    (first == 73 && second == 68 && third == 51) ||
                        (first == 255 && second >= 0 && second and 0xe6 == 0xe2)
                }
        } finally {
            extractor.release()
        }
    }
}

