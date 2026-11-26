package com.lilyroksanne.mon

import java.text.DateFormat
import java.text.Normalizer
import java.util.Date
import java.util.Locale

internal object RoadChat {
    enum class Command { PLAY, PAUSE, NEXT, PREVIOUS }

    fun command(input: String): Command? = when (withoutName(input)) {
        "pon musica", "pon una rola", "pon una cancion", "reproduce musica", "reproducir",
        "continua la musica", "reanuda la musica", "dale play", "play" -> Command.PLAY
        "pausa", "pausa la musica", "deten la musica", "para la musica", "pausala" -> Command.PAUSE
        "siguiente", "siguiente cancion", "siguiente rola", "cambia de cancion",
        "cambia la rola", "otra rola" -> Command.NEXT
        "anterior", "cancion anterior", "rola anterior", "regresa una cancion" -> Command.PREVIOUS
        else -> null
    }

    private val download = Regex(
        """^[¿¡\s]*(?:(?:oye|o[ií]|hey|mon)[,:\s]+)*(?:descarga(?:me)?|desc[aá]rgame|baja(?:me)?|b[aá]jame)(?=\s|[.!?]|$)\s*""",
        RegexOption.IGNORE_CASE
    )
    private val songPrefix = Regex(
        """^(?:la\s+de|una\s+canci[oó]n\s+de|la\s+canci[oó]n(?:\s+de)?|la\s+rola(?:\s+de)?|m[uú]sica(?:\s+de)?)(?:\s+|$)""",
        RegexOption.IGNORE_CASE
    )

    fun downloadQuery(input: String): String? {
        val command = download.find(input) ?: return null
        return input.substring(command.range.last + 1).trim()
            .replaceFirst(songPrefix, "").trim(' ', '.', '!', '?', '¿', '¡', '"', '«', '»')
    }

    fun reply(input: String): String {
        val message = withoutName(input)
        if (clock.matches(message)) {
            return "Son las ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}, Lily."
        }
        return conversations.firstOrNull { (pattern, _) -> pattern.containsMatchIn(message) }
            ?.second?.random() ?: fallback.random()
    }

    private fun withoutName(input: String): String =
        Normalizer.normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .removePrefix("mon ")

    private val clock = Regex("(?:(?:dime|sabes) )?(?:que hora es|la hora|hora)(?: por favor)?")

    private val conversations = listOf(
        Regex("\\b(quien eres|como te llamas|presentate)\\b") to listOf(
            "Soy Mon. Tú llevas el volante, yo pongo la música.",
            "Mon, tu copiloto de cabecera. Mi cinturón es virtual.",
            "Me llamo Mon. Mi especialidad es hacerle compañía a Lily.",
            "Soy la voz del tablero. Antes de que preguntes, sí, también tengo buen gusto musical."
        ),
        Regex("\\b(eres kitt|auto increible|coche fantastico|modo turbo|turbo boost)\\b") to listOf(
            "Mi botón de turbo cambia de canción. ¿Quieres probarlo?",
            "El modo turbo del estéreo consiste en una buena batería y un buen solo.",
            "Motor imaginario listo. Banda sonora, totalmente real.",
            "El tablero tiene estilo. El volante sigue siendo tuyo."
        ),
        Regex("\\b(como estas|todo bien|estas lista|me escuchas|estas ahi)\\b") to listOf(
            "Aquí estoy. Lista para platicar y poner rolas.",
            "Todo tranquilo en el tablero, Lily.",
            "Te escucho. ¿Qué traes en mente?",
            "Presente. ¿Vamos con música o con plática?"
        ),
        Regex("\\b(buenas noches|manejar de noche|viaje nocturno)\\b") to listOf(
            "Buenas noches, Lily. Una buena rola cambia todo el camino.",
            "Turno nocturno, Lily. Te acompaño.",
            "Luces de ciudad y una buena batería. Suena bien.",
            "Aquí sigo, de copiloto en el turno de noche."
        ),
        Regex("\\b(hola|buenas|buenos dias|buenas tardes|que onda|que tal)\\b") to listOf(
            "Hola, Lily. Mon lista para rodar.",
            "Qué onda, Lily. Ya llegué al asiento de copiloto.",
            "Hola. ¿Arrancamos con una buena rola?",
            "Presente, Lily. Qué gusto compartir tablero otra vez.",
            "Qué tal, Lily. Tú el rumbo, yo la banda sonora."
        ),
        Regex("\\b(adios|hasta luego|nos vemos|ya llegamos|llegamos|bajate)\\b") to listOf(
            "Hasta la próxima, Lily.",
            "Buen viaje, Lily. Aquí te espero para el siguiente.",
            "Llegamos. Que el último coro se quede contigo.",
            "Cambio y fuera, Lily. La biblioteca te espera."
        ),
        Regex("\\b(gracias|te rifaste|buena copiloto|eres genial)\\b") to listOf(
            "Para eso estoy, Lily.",
            "De nada. Me ganas con una buena rola.",
            "A la orden, capitana.",
            "El gusto es mío. La siguiente rola va por tu cuenta."
        ),
        Regex("\\b(chiste|hazme reir|cuentame algo gracioso)\\b") to listOf(
            "¿Por qué el estéreo nunca discute? Porque siempre cambia de tono.",
            "Mi chiste de llantas era buenísimo, pero se me ponchó el remate.",
            "¿Qué le dijo una rola a otra? Nos vemos en la siguiente pista.",
            "Un coche entró a una biblioteca. Buscaba algo con buena tracción narrativa.",
            "Le pedí al GPS un chiste. Me dijo que recalculara el remate."
        ),
        Regex("\\b(trafico|embotellamiento|atasco)\\b") to listOf(
            "Aquí sí podemos adelantar. Dale a «siguiente rola».",
            "El tráfico va lento. La música puede ir a otro ritmo.",
            "Vamos con calma, Lily. Yo sigo aquí.",
            "El carril de la música siempre está abierto."
        ),
        Regex("\\b(a donde vamos|vamos|arranca|listos|carretera|viaje|paseo)\\b") to listOf(
            "Tú escoges el destino. Yo pongo el ambiente.",
            "Asiento de copiloto ocupado. ¿Qué rola abre el viaje?",
            "Lista para acompañarte, Lily.",
            "Todo viaje merece una canción de entrada."
        ),
        Regex("\\b(cansada|triste|estresada|aburrida|aburrido)\\b") to listOf(
            "Aquí estoy, Lily. Si quieres, ponemos algo tranquilo.",
            "Te hago compañía. Podemos quedarnos con una buena rola.",
            "Un rato de música también cuenta como plan.",
            "¿Una rola o un chiste, Lily?"
        ),
        Regex("\\b(que puedes hacer|ayuda|como funciona|comandos)\\b") to listOf(
            "Dime «pon música», «pausa», «siguiente rola» o «rola anterior». También cuento chistes.",
            "Puedo platicar, poner tus MP3 y dar la hora. Prueba «siguiente rola».",
            "Toca el micrófono y dime «pon una rola». La música vive en Biblioteca."
        ),
        Regex("\\b(bluetooth|importar|agregar mp3|pasar canciones)\\b") to listOf(
            "Mándame tus MP3 por Bluetooth. En el estéreo ábrelos con Compartir y elige Mon, o usa Agregar MP3.",
            "Para sumar rolas, toca Agregar MP3 y busca los archivos recibidos del celular."
        ),
        Regex("\\b(musica|rola|cancion|playlist|banda sonora)\\b") to listOf(
            "Tus rolas están en la biblioteca. Dime «pon música» y arrancamos.",
            "Una buena banda sonora hace el viaje. ¿Qué género quieres escuchar?",
            "Cada género tiene su momento. Échale un ojo a Biblioteca.",
            "El estéreo está listo. Di «pon una rola»."
        )
    )

    private val fallback = listOf(
        "Aquí estoy, Lily. Prueba «pon una rola» o «cuéntame un chiste».",
        "Esa me agarró en curva, Lily. ¿Quieres música?",
        "Te sigo, Lily. Si quieres, dime «siguiente rola».",
        "Dime otra, Lily. Tengo oído para las canciones y tiempo para la plática.",
        "Va, Lily. ¿Seguimos platicando o ponemos música?"
    )
}
