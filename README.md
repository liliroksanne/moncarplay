# Mon para estéreo Android

Mon es un copiloto con chat local, avatar Matrix y reproductor de MP3. En horizontal, la música queda a la izquierda y el avatar a la derecha. El botón grande **Hablar**, centrado abajo, transcribe una frase y la envía al chat. **Voz de Mon** activa la respuesta hablada. Ambos usan los servicios instalados en el equipo y funcionan en español cuando esos servicios están disponibles.

## Pasar rolas por Bluetooth

1. Vincula tu celular Android y el estéreo desde los ajustes de Bluetooth. El estéreo debe admitir la recepción de archivos por Bluetooth.
2. Desde la app Archivos del celular, selecciona uno o varios MP3 y usa **Compartir → Bluetooth**. Elige el nombre del estéreo y acepta la transferencia en la pantalla.
3. En Mon, pulsa **Agregar MP3** y busca los archivos recibidos. También puedes seleccionarlos en la app Archivos del estéreo y usar **Compartir → Mon**, si el gestor muestra esa opción.

Mon copia las pistas a su carpeta interna `rolas`. Puedes quitar la copia de la carpeta Bluetooth o desconectar la USB después de importar. La app conserva la biblioteca y evita añadir archivos idénticos dos veces.

Abre **Biblioteca** para elegir un género o ver todas las rolas. Mon lee la etiqueta de género del MP3. Con **Editar género** puedes corregirla o asignarle una a una pista que aparece en **Sin género**. Al seleccionar una pista desde un género, anterior y siguiente recorren ese grupo. La música se pausa al salir de la app.

Prueba «qué onda», «quién eres», «cuéntame un chiste», «modo turbo», «pon una rola», «pausa» y «siguiente rola».

## Sincronización con Google Drive

Mon revisa la carpeta pública `musica` con conexión a Internet. Android programa una revisión periódica cada 15 minutos, sujeta a la disponibilidad de conexión y a las reglas de batería del equipo. Al abrir Mon, puede programar la primera revisión sin esperar todo el intervalo.

Las pistas nuevas quedan disponibles sin conexión en **Biblioteca**. Mon evita guardar copias idénticas y conserva las pistas locales si se eliminan de Drive. Busca también en subcarpetas públicas. Para pedir una grabación concreta, di «descárgame la de…» o «bájame…» y el nombre tal como aparece en Drive. Di «descarga todas» para revisar la carpeta en ese momento.
## Compilación

Abre el proyecto en Android Studio y usa **Build → Generate App Bundles or APKs → Generate APKs**. La APK de depuración se genera en `app/build/outputs/apk/debug/`. La aplicación requiere Android 8.1 o posterior.
