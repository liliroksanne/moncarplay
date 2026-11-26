package com.lilyroksanne.mon

import java.text.Normalizer
import java.util.Locale

internal object RoadChat {
    enum class Command { PLAY, PAUSE, NEXT, PREVIOUS }

    fun command(input: String): Command? = when (normalize(input)) {
        "pon musica", "reproduce musica", "reproducir", "continua la musica",
        "reanuda la musica", "mon pon musica" -> Command.PLAY
        "pausa", "pausa la musica", "deten la musica", "mon pausa la musica" -> Command.PAUSE
        "siguiente", "siguiente cancion", "cambia de cancion" -> Command.NEXT
        "anterior", "cancion anterior" -> Command.PREVIOUS
        else -> null
    }

    fun reply(input: String): String {
        val message = normalize(input)
        return when {
            Regex("\\b(hola|buenas|buenos dias)\\b").containsMatchIn(message) ->
                "Hola, Lily. Mon lista para rodar."
            "como estas" in message -> "Sistemas listos. ¿Ponemos música?"
            "gracias" in message -> "Para eso estoy, Lily."
            "adios" in message || "hasta luego" in message -> "Hasta la próxima, Lily."
            "hora" in message.split(' ') ->
                "Son las ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())}."
            else -> "Aquí estoy, Lily. Prueba «pon música», «pausa» o «siguiente»."
        }
    }

    private fun normalize(input: String): String =
        Normalizer.normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}

