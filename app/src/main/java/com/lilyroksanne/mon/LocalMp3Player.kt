package com.lilyroksanne.mon

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class LocalMp3Player(
    private val context: Context,
    private val preferences: SharedPreferences
) {
    private data class Track(val uri: Uri, val title: String, val genre: String)
    data class LibraryEntry(val position: Int, val title: String, val genre: String)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val importer = Mp3Import(context)
    private val drive = MusicDownloads(context)
    private val driveUpdates = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val saved = key?.let(drive::savedTrack) ?: return@OnSharedPreferenceChangeListener
        runOnMain { addDownload(saved) }
    }
    private val importLock = Mutex()
    private var queueGenre: String? = null

    private val library = mutableListOf<Track>()
    private var index = 0
    private var player: MediaPlayer? = null
    private var ready = false
    private var playWhenReady = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                runOnMain { pause() }
            }
        }
        .build()

    var onChange: (() -> Unit)? = null
    var error: String? = null
        private set
    val genres: List<String> get() = library.map { it.genre }.distinct().sorted()
    val currentGenre: String? get() = library.getOrNull(index)?.genre
    val currentUri: Uri? get() = library.getOrNull(index)?.uri
    val currentTitle: String? get() = library.getOrNull(index)?.title
    val positionLabel: String get() = if (library.isEmpty()) "" else "${index + 1} / ${library.size}"
    val isActive: Boolean get() = playWhenReady
    val audioSessionId: Int get() = runCatching { player?.audioSessionId ?: 0 }.getOrDefault(0)

    init {
        val saved = runCatching { JSONArray(preferences.getString("tracks", "[]") ?: "[]") }
            .getOrDefault(JSONArray())
        for (i in 0 until saved.length()) {
            val entry = saved.optJSONObject(i) ?: continue
            val uri = entry.optString("uri")
            val title = entry.optString("title")
            if (uri.isNotBlank() && title.isNotBlank()) {
                library.add(Track(Uri.parse(uri), title, cleanGenre(entry.optString("genre"))))
            }
        }
        drive.savedTracks().forEach(::addDownloadInternal)
        drive.index.registerOnSharedPreferenceChangeListener(driveUpdates)
        index = preferences.getInt("index", 0).coerceIn(0, library.lastIndex.coerceAtLeast(0))
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun notifyChanged() {
        runOnMain { onChange?.invoke() }
    }

    suspend fun addFiles(uris: List<Uri>): String? = importLock.withLock {
        if (uris.isEmpty()) return@withLock null
        var added = 0
        var skipped = 0
        for (uri in uris.distinct()) {
            try {
                val copy = withContext(Dispatchers.IO) { importer.copy(uri) }
                withContext(Dispatchers.Main) {
                    if (library.none { it.uri == copy.uri }) {
                        val oldPosition = library.indexOfFirst { it.uri == uri }
                        val imported = Track(copy.uri, copy.title, cleanGenre(copy.genre))
                        if (oldPosition >= 0) library[oldPosition] = imported else library.add(imported)
                        added++
                        saveLibrary()
                        onChange?.invoke()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                skipped++
            }
        }
        when {
            skipped > 0 -> "Guardadas: $added. No se pudieron importar $skipped archivos MP3."
            added == 0 -> "Estas pistas ya están en tu biblioteca."
            added == 1 -> "Guardé una rola en la biblioteca."
            else -> "Guardé $added rolas en la biblioteca."
        }
    }

    fun addDownload(download: MusicDownloads.SavedTrack): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { addDownloadInternal(download) }
            return true
        }
        return addDownloadInternal(download)
    }

    private fun addDownloadInternal(download: MusicDownloads.SavedTrack): Boolean {
        val uri = Uri.fromFile(download.file)
        if (library.any { it.uri == uri }) return false
        library.add(Track(uri, download.title, cleanGenre(download.genre)))
        saveLibrary()
        onChange?.invoke()
        return true
    }

    fun entries(genre: String?): List<LibraryEntry> = library.mapIndexedNotNull { position, track ->
        if (genre == null || track.genre == genre) LibraryEntry(position, track.title, track.genre)
        else null
    }

    fun setGenre(position: Int, genre: String) {
        val track = library.getOrNull(position) ?: return
        library[position] = track.copy(genre = cleanGenre(genre))
        if (position == index && queueGenre != null) queueGenre = library[position].genre
        saveLibrary()
        notifyChanged()
    }

    fun select(position: Int, genre: String? = null) {
        if (position in library.indices) {
            queueGenre = genre
            load(position)
        }
    }

    fun step(delta: Int) {
        val queue = entries(queueGenre)
        if (queue.isEmpty()) return
        val current = queue.indexOfFirst { it.position == index }
        val goal = if (current == -1) 0 else Math.floorMod(current + delta, queue.size)
        load(queue[goal].position)
    }

    private fun cleanGenre(genre: String?): String {
        val cleaned = genre?.trim().orEmpty().take(80)
        if (cleaned.isEmpty()) return UNTAGGED
        return library.firstOrNull { it.genre.equals(cleaned, ignoreCase = true) }?.genre ?: cleaned
    }

    fun play() {
        if (library.isEmpty() || playWhenReady) return
        val current = player
        if (current == null) {
            load(index)
        } else {
            playWhenReady = true
            if (ready) start(current)
            notifyChanged()
        }
    }

    fun pause() {
        playWhenReady = false
        if (ready) runCatching { player?.pause() }
        audioManager.abandonAudioFocusRequest(focusRequest)
        notifyChanged()
    }

    private fun load(position: Int) {
        releasePlayer()
        index = position
        preferences.edit().putInt("index", index).apply()
        error = null
        playWhenReady = true
        val next = MediaPlayer()
        player = next
        try {
            next.setAudioAttributes(audioAttributes)
            next.setDataSource(context, library[index].uri)
            next.setOnPreparedListener {
                if (it === player) {
                    ready = true
                    if (playWhenReady) start(it)
                    notifyChanged()
                }
            }
            next.setOnCompletionListener {
                if (it !== player) return@setOnCompletionListener
                val after = entries(queueGenre).firstOrNull { entry -> entry.position > index }
                if (after != null) {
                    load(after.position)
                } else {
                    it.seekTo(0)
                    pause()
                }
            }
            next.setOnErrorListener { source, _, _ ->
                if (source === player) reportError("No pude abrir este MP3. Revisa el archivo o elige otra pista.")
                true
            }
            next.prepareAsync()
            notifyChanged()
        } catch (_: Exception) {
            reportError("No pude abrir este MP3. Revisa el archivo o elige otra pista.")
        }
    }

    private fun start(current: MediaPlayer) {
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            playWhenReady = false
            error = "El audio no está disponible en este momento."
            return
        }
        error = null
        try {
            current.start()
        } catch (_: IllegalStateException) {
            reportError("No pude reproducir este MP3.")
        }
    }

    private fun reportError(message: String) {
        releasePlayer()
        error = message
        notifyChanged()
    }

    private fun saveLibrary() {
        val contents = JSONArray()
        library.forEach { track ->
            contents.put(
                JSONObject()
                    .put("uri", track.uri.toString())
                    .put("title", track.title)
                    .put("genre", track.genre)
            )
        }
        preferences.edit().putString("tracks", contents.toString()).apply()
    }

    private fun releasePlayer() {
        playWhenReady = false
        ready = false
        runCatching { player?.release() }
        player = null
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    fun close() {
        drive.index.unregisterOnSharedPreferenceChangeListener(driveUpdates)
        onChange = null
        releasePlayer()
    }

    private companion object {
        const val UNTAGGED = "Sin género"
    }
}
