# Mon para estéreo Android

Mon conserva el avatar Matrix, el chat morado y la voz del sistema. El chat responde en español con frases predefinidas y funciona sin una cuenta ni conexión. Está pensado para instalarse directamente en una pantalla que ejecute aplicaciones Android, como un estéreo con Android 8.1 o posterior.

## Música local

1. Abre Mon y pulsa **Agregar MP3**.
2. Selecciona uno o varios archivos en el almacenamiento del estéreo o en una USB que aparezca en el selector de Android.
3. Pulsa **Biblioteca** para elegir una pista, o usa los botones de reproducción, pausa, anterior y siguiente.

Mon conserva la biblioteca entre sesiones. El archivo original debe seguir disponible. La reproducción se pausa al salir de la app.

Puedes escribir «hola», «pon música», «pausa», «siguiente canción» o «canción anterior». El interruptor **Micrófono** usa el reconocedor del equipo cuando está disponible. La voz sintetizada requiere un motor de texto a voz con español instalado.

En una pantalla horizontal de 1280 × 720 el chat y el reproductor se muestran lado a lado con una densidad típica de estéreo. En ventanas estrechas, el reproductor queda debajo del chat.

## Compilación

Abre el proyecto en Android Studio con el SDK configurado, o ejecuta desde la raíz:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

La APK de depuración se genera en `app/build/outputs/apk/debug/`.
