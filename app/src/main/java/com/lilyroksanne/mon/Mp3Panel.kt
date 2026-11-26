package com.lilyroksanne.mon

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Cyberpunk Left Panel containing:
 * - Full-panel album artwork background + dark neon scrim (NOT behind the avatar)
 * - Linux console system status header (clock/date, Wi-Fi, BT, Volume, Battery/RAM)
 * - Music info + real-time audio frequency bars (CyberVisualizerView)
 * - Playback controls + Hablar voice button
 * - Bottom Drive background sync progress / success / error diagnostic bar
 */
internal class Mp3Panel(
    context: Context,
    private val music: LocalMp3Player,
    microphone: View,
    private val onPlayButtonTap: () -> Boolean,
    private val retrySync: () -> Unit,
    private val onMusicChanged: () -> Unit,
    addFiles: () -> Unit
) : FrameLayout(context) {

    private val neonCyan = Color.rgb(0, 240, 255)
    private val neonMagenta = Color.rgb(255, 42, 133)
    private val neonPurple = Color.rgb(181, 55, 242)
    private val neonGreen = Color.rgb(57, 255, 20)
    private val neonAmber = Color.rgb(255, 184, 0)
    private val neonRed = Color.rgb(255, 51, 92)

    private var currentCoverBitmap: Bitmap? = null
    private var sync = DriveSyncStatus.State()

    private val coverView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        colorFilter = ColorMatrixColorFilter(
            floatArrayOf(
                0.28f, 0.25f, 0.08f, 0f, 4f,
                0.10f, 0.22f, 0.16f, 0f, 6f,
                0.30f, 0.42f, 0.28f, 0f, 16f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private val coverScrim = View(context).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xD605020C.toInt(), 0xB8080414.toInt(), 0xEE04010A.toInt())
        )
    }

    private val rightBorderLine = View(context).apply {
        setBackgroundColor(Color.argb(175, 0, 240, 255))
    }

    private val statusHeader = CyberStatusHeader(context)
    private val visualizer = CyberVisualizerView(context)

    private val track = label(16f, Color.WHITE).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private val status = label(11f, Color.rgb(180, 225, 245)).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private val previous = cyberButton("⏮", "Pista anterior", neonCyan) { music.step(-1) }
    private val toggle = cyberButton("▶", "Reproducir", neonMagenta) {
        // Always check the secret exit sequence (3x Hablar + 1x Play) first!
        if (!onPlayButtonTap()) {
            if (music.currentTitle != null) {
                if (music.isActive) music.pause() else music.play()
            }
        }
    }
    private val next = cyberButton("⏭", "Siguiente pista", neonCyan) { music.step(1) }
    private val addMp3Btn = cyberButton("+ MP3", "Agregar MP3", neonPurple) { addFiles() }
    private val library = cyberButton("BIBLIOTECA", "Abrir biblioteca por género", neonPurple, ::showLibrary)

    private val syncCardBorder = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(Color.argb(225, 8, 5, 18))
        setStroke(dp(1), neonCyan)
    }

    private val syncTitle = label(11.5f, neonCyan).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private val syncNote = label(10.5f, Color.rgb(210, 215, 235)).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private val syncProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(neonCyan)
        indeterminateTintList = ColorStateList.valueOf(neonMagenta)
        progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 0, 240, 255))
    }

    init {
        setBackgroundColor(Color.rgb(6, 3, 14))

        addView(coverView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(coverScrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(12), dp(8))
        }

        // 1. Cyberpunk Linux Console System Header (replaces top status bar)
        content.addView(
            statusHeader,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        // 2. Audio Deck Card
        val deckHeader = label(10.5f, neonCyan).apply {
            text = "// AUDIO_DECK :: FREQ_ANALYZER"
            maxLines = 1
        }
        content.addView(
            deckHeader,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        )

        content.addView(
            track,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            }
        )
        content.addView(
            status,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            }
        )

        // Real-time Cyberpunk Frequency Bars
        content.addView(
            visualizer,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
        )

        previous.textSize = 20f
        toggle.textSize = 22f
        next.textSize = 20f
        addRow(content, previous, toggle, next, height = 52)
        addRow(content, addMp3Btn, library, height = 40)

        // 3. Hablar voice button directly in the left panel
        content.addView(
            microphone,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        )

        // Flexible spacer pushes Drive diagnostic bar cleanly to bottom
        content.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))

        // 4. Bottom Cyberpunk Drive Sync Progress & Diagnostic Bar
        val syncCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = syncCardBorder
            isFocusable = true
            isClickable = true
            setOnClickListener { showSyncDetails() }

            addView(syncTitle)
            addView(
                syncProgress,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(6)).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                }
            )
            addView(syncNote)
        }
        content.addView(
            syncCard,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        )

        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            rightBorderLine,
            LayoutParams(dp(2), LayoutParams.MATCH_PARENT, Gravity.END)
        )

        renderSync(sync)
        music.onChange = ::refresh
        refresh()
    }

    fun setArtwork(bitmap: Bitmap?) {
        val old = currentCoverBitmap
        currentCoverBitmap = bitmap
        coverView.setImageBitmap(bitmap)
        if (old != null && old !== bitmap && !old.isRecycled) {
            old.recycle()
        }
    }

    fun clearArtwork() {
        setArtwork(null)
    }

    fun setPanelActive(active: Boolean) {
        if (active) statusHeader.start() else statusHeader.stop()
        visualizer.setActive(active)
    }

    fun setUnlockHint(message: String?) {
        statusHeader.setUnlockHint(message)
    }

    fun renderSync(state: DriveSyncStatus.State) {
        sync = state
        val accentColor = when (state.phase) {
            DriveSyncStatus.Phase.SEARCHING, DriveSyncStatus.Phase.DOWNLOADING -> neonCyan
            DriveSyncStatus.Phase.COMPLETE -> neonGreen
            DriveSyncStatus.Phase.WAITING -> neonAmber
            DriveSyncStatus.Phase.ERROR -> neonRed
            DriveSyncStatus.Phase.IDLE -> neonPurple
        }
        syncCardBorder.setStroke(dp(1), accentColor)
        syncTitle.setTextColor(accentColor)
        syncTitle.text = "DRIVE // ${state.summary}"

        syncProgress.progressTintList = ColorStateList.valueOf(accentColor)
        syncProgress.indeterminateTintList = ColorStateList.valueOf(accentColor)
        syncProgress.isIndeterminate = state.busy && state.percent == null
        if (!syncProgress.isIndeterminate) {
            syncProgress.progress = state.percent ?: when (state.phase) {
                DriveSyncStatus.Phase.COMPLETE -> 100
                DriveSyncStatus.Phase.ERROR -> 100
                else -> 0
            }
        }

        syncNote.setTextColor(
            when {
                state.phase == DriveSyncStatus.Phase.ERROR || state.errors.isNotEmpty() -> Color.rgb(255, 130, 155)
                state.downloaded.isNotEmpty() -> Color.rgb(155, 255, 165)
                else -> Color.rgb(195, 225, 240)
            }
        )
        syncNote.text = when {
            state.phase == DriveSyncStatus.Phase.DOWNLOADING -> "▼ ${state.title}"
            state.phase == DriveSyncStatus.Phase.SEARCHING -> ">> Consultando carpeta pública..."
            state.errors.isNotEmpty() -> "✕ ${state.errors.last()}"
            state.downloaded.isNotEmpty() -> "✓ Descargó (${state.downloaded.size}): ${state.downloaded.last()}"
            state.phase == DriveSyncStatus.Phase.WAITING -> "⏳ Sin Internet · toca para ver log"
            state.phase == DriveSyncStatus.Phase.COMPLETE -> "✓ Sin rolas nuevas · toca para diagnóstico"
            else -> ">> Toca para diagnosticar / sincronizar"
        }
    }

    private fun showSyncDetails() {
        val detail = buildString {
            appendLine("Estado: ${sync.summary}")
            appendLine("Pistas detectadas en Drive: ${sync.totalFoundInDrive}")
            if (sync.title.isNotBlank()) appendLine("Actual: ${sync.title}")
            if (sync.downloaded.isNotEmpty()) {
                appendLine().appendLine("✓ DESCARGADAS (${sync.downloaded.size}):")
                sync.downloaded.forEach { appendLine("  • $it") }
            }
            if (sync.errors.isNotEmpty()) {
                appendLine().appendLine("✕ ERRORES (${sync.errors.size}):")
                sync.errors.forEach { appendLine("  • $it") }
            }
            if (sync.logs.isNotEmpty()) {
                appendLine().appendLine(">> LOG DE DIAGNÓSTICO:")
                sync.logs.takeLast(12).forEach { appendLine(it) }
            }
        }.trim()
        AlertDialog.Builder(context)
            .setTitle("DRIVE // DIAGNÓSTICO")
            .setMessage(detail)
            .setPositiveButton("Revisar ahora") { _, _ -> retrySync() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showLibrary() {
        val genres = music.genres
        AlertDialog.Builder(context)
            .setTitle("Biblioteca de música")
            .setItems((listOf("Todas las rolas") + genres).toTypedArray()) { _, choice ->
                showTracks(genres.getOrNull(choice - 1))
            }
            .setNeutralButton("Editar género") { _, _ -> chooseTrackToEdit() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showTracks(genre: String?) {
        val tracks = music.entries(genre)
        AlertDialog.Builder(context)
            .setTitle(genre ?: "Todas las rolas")
            .setItems(tracks.map { it.title }.toTypedArray()) { _, choice ->
                music.select(tracks[choice].position, genre)
            }
            .setNegativeButton("Volver") { _, _ -> showLibrary() }
            .show()
    }

    private fun chooseTrackToEdit() {
        val tracks = music.entries(null)
        AlertDialog.Builder(context)
            .setTitle("Elige una rola")
            .setItems(tracks.map { "${it.title} · ${it.genre}" }.toTypedArray()) { _, choice ->
                editGenre(tracks[choice])
            }
            .setNegativeButton("Volver") { _, _ -> showLibrary() }
            .show()
    }

    private fun editGenre(entry: LocalMp3Player.LibraryEntry) {
        val editor = android.widget.EditText(context).apply {
            isSingleLine = true
            hint = "Género"
            setText(entry.genre)
            selectAll()
        }
        AlertDialog.Builder(context)
            .setTitle(entry.title)
            .setView(editor)
            .setPositiveButton("Guardar") { _, _ ->
                music.setGenre(entry.position, editor.text.toString())
                showLibrary()
            }
            .setNegativeButton("Cancelar") { _, _ -> showLibrary() }
            .show()
    }

    private fun refresh() {
        val title = music.currentTitle
        track.text = title ?: "[ SIN PISTAS ]"
        status.text = music.error ?: if (title == null) {
            "Importa MP3 o espera sync de Drive"
        } else {
            "${music.currentGenre} · ${music.positionLabel} · ${if (music.isActive) "PLAYING" else "PAUSED"}"
        }
        previous.isEnabled = title != null
        // IMPORTANT: toggle stays ALWAYS enabled so the 3x Hablar + 1x Play exit combo works even with 0 tracks!
        toggle.isEnabled = true
        toggle.alpha = if (title != null) 1f else 0.72f
        next.isEnabled = title != null
        library.isEnabled = title != null
        toggle.text = if (music.isActive) "Ⅱ" else "▶"
        toggle.contentDescription = if (music.isActive) "Pausar" else "Reproducir"

        visualizer.updateAudioState(music.isActive, music.audioSessionId)
        onMusicChanged()
    }

    private fun addRow(parent: LinearLayout, vararg buttons: Button, height: Int) {
        val row = LinearLayout(context)
        buttons.forEachIndexed { index, button ->
            row.addView(
                button,
                LinearLayout.LayoutParams(0, dp(height), 1f).apply {
                    if (index > 0) marginStart = dp(6)
                }
            )
        }
        parent.addView(
            row,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        )
    }

    private fun label(size: Float, color: Int) = TextView(context).apply {
        textSize = size
        typeface = Typeface.MONOSPACE
        setTextColor(color)
    }

    private fun cyberButton(
        title: String,
        description: String,
        strokeColor: Int,
        action: () -> Unit
    ) = Button(context).apply {
        text = title
        textSize = 11.5f
        isAllCaps = false
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        contentDescription = description
        setTextColor(Color.WHITE)
        val fill = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            color = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.argb(140, 22, 14, 34), Color.argb(215, 16, 8, 34))
            )
            setStroke(dp(1), strokeColor)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x4400F0FF), fill, null)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
