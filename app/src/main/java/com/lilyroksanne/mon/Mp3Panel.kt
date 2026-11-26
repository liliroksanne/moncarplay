package com.lilyroksanne.mon

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

internal class Mp3Panel(
    context: Context,
    private val music: LocalMp3Player,
    addFiles: () -> Unit
) : LinearLayout(context) {
    private val accent = Color.rgb(218, 112, 214)
    private val track = label(20f, Color.WHITE).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val status = label(13f, Color.rgb(200, 176, 225))
    private val previous = playerButton("⏮", "Pista anterior") { music.step(-1) }
    private val toggle = playerButton("▶", "Reproducir") {
        if (music.isActive) music.pause() else music.play()
    }
    private val next = playerButton("⏭", "Siguiente pista") { music.step(1) }
    private val library = playerButton("Biblioteca", "Abrir biblioteca por género", ::showLibrary)

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
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.rgb(24, 10, 37))
            setStroke(dp(1), Color.rgb(85, 43, 118))
        }
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(label(14f, accent).apply { text = "MÚSICA LOCAL" })
        addView(track, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        previous.textSize = 23f
        toggle.textSize = 26f
        next.textSize = 23f
        addRow(previous, toggle, next, height = 68)
        addRow(playerButton("Agregar MP3", "Agregar MP3") { addFiles() }, library)
        music.onChange = ::refresh
        refresh()
    }

    private fun refresh() {
        val title = music.currentTitle
        track.text = title ?: "Sin pistas"
        status.text = music.error ?: if (title == null) {
            "Importa MP3 o pide «descarga» y el nombre de la canción."
        } else {
            "${music.currentGenre} · ${music.positionLabel} · ${if (music.isActive) "Reproduciendo" else "En pausa"}"
        }
        previous.isEnabled = title != null
        toggle.isEnabled = title != null
        next.isEnabled = title != null
        library.isEnabled = title != null
        toggle.text = if (music.isActive) "Ⅱ" else "▶"
        toggle.contentDescription = if (music.isActive) "Pausar" else "Reproducir"
    }

    private fun addRow(vararg buttons: Button, height: Int = 52) {
        val row = LinearLayout(context)
        buttons.forEachIndexed { index, button ->
            row.addView(button, LayoutParams(0, dp(height), 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
    }

    private fun label(size: Float, color: Int) = TextView(context).apply {
        textSize = size
        typeface = Typeface.MONOSPACE
        setTextColor(color)
    }

    private fun playerButton(title: String, description: String, action: () -> Unit) =
        Button(context).apply {
            text = title
            textSize = 13f
            isAllCaps = false
            typeface = Typeface.MONOSPACE
            contentDescription = description
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.rgb(52, 35, 67), Color.rgb(98, 41, 150))
            )
            minWidth = 0
            minimumWidth = 0
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { action() }
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
