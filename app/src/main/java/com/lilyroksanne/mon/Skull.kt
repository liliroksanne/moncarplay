package com.lilyroksanne.mon

import android.Manifest
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Bundle as OSBundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.os.Build
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.lilyroksanne.mon.util.MonLogger
import com.lilyroksanne.mon.util.HardwareKeyboardHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Skull : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val importMp3 = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importTracks(uris)
    }

    private fun importTracks(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            viewModel.music.addFiles(uris)?.let { message ->
                android.widget.Toast.makeText(this@Skull, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun receiveSharedMp3(intent: Intent) {
        val streams = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                androidx.core.content.IntentCompat.getParcelableArrayListExtra(
                    intent, Intent.EXTRA_STREAM, android.net.Uri::class.java
                ).orEmpty()
            else -> return
        }
        val clip = intent.clipData
        val clips = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
        importTracks((streams + clips).filter { it.scheme == "content" })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveSharedMp3(intent)
    }

    private val musicDownloads by lazy { MusicDownloads(applicationContext) }

    private val TAG = "SkullActivity"
    private val viewModel: MainViewModel by viewModels()

    private lateinit var dialogueBox: MatrixDialogueBox
    private lateinit var etUserInput: EditText
    private lateinit var bottomWrapper: LinearLayout
    private lateinit var tvLastMessages: TextView
    private lateinit var ttsSwitch: Switch
    private lateinit var micButton: Button

    private lateinit var sharedPreferences: SharedPreferences

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isAiBusy = false
    private var awaitingDownloadTitle = false

    private val sentMessagesHistory = mutableListOf<String>()

    private var aiJob: Job? = null
    private var isAppInForeground = false


    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            MonLogger.d(TAG, "=== Permiso de micrófono concedido por el usuario ===")
            startListening()
        } else {
            MonLogger.e(TAG, "=== Permiso de micrófono denegado por el usuario ===")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MonLogger.init(this)
        MonLogger.d(TAG, "=== onCreate: Iniciando SkullActivity ===")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
            MonLogger.d(TAG, "onCreate: Configurado flags de pantalla de bloqueo para API >= O_MR1")
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
            MonLogger.d(TAG, "onCreate: Configurado flags de pantalla de bloqueo para API antigua")
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sharedPreferences = getSharedPreferences("MonPrefs", Context.MODE_PRIVATE)

        MonLogger.d(TAG, "onCreate: Inicializando TextToSpeech (Loquendo)")
        tts = TextToSpeech(this, this)

        MonLogger.d(TAG, "onCreate: Inicializando SpeechRecognizer (Voz a Texto)")
        initializeSpeechRecognizer()

        // === ESCALAR INTERFAZ DEPENDIENDO DE LA PANTALLA ===
        val density = resources.displayMetrics.density
        val dpToPx = { dp: Int -> (dp * density).toInt() }
        val dpToPxf = { dp: Float -> dp * density }

        // En pantallas pequeñas como 540 de ancho, esto reducirá las fuentes para que quepan bien.


        val dashboard = LinearLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val rootLayout = FrameLayout(this)
        val musicPanel = Mp3Panel(this, viewModel.music) {
            importMp3.launch(arrayOf("audio/*", "application/octet-stream"))
        }

        val matrixView = MatrixSkullView(this).apply {
            id = android.view.View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            frameIntervalMillis = 40L
            minWaitSeconds = 0.47f
            maxWaitSeconds = 0.9f
            setImageFrames(
                listOf(
                    R.drawable.lily_idle_1,
                    R.drawable.lily_idle_2,
                    R.drawable.lily_idle_3,
                    R.drawable.lily_idle_1,
                )
            )
        }

        dialogueBox = MatrixDialogueBox(this).apply {
            skullView = matrixView
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
              // setMargins(dpToPx(12), (screenHeight * 0.18).toInt(), dpToPx(12), dpToPx(16))

            }
            typingVolume = 0.127f
            MonLogger.d(TAG, "onCreate: Configurando diálogo inicial neutral (sin saludo automático)")
            startDialogue(
                listOf(
                    "..."
                )
            )
            onDialogueFinished = {}
        }

        bottomWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                setMargins(dpToPx(12), 0, dpToPx(12), dpToPx(16))
            }
        }

        val topControlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dpToPx(8), 0, dpToPx(8), dpToPx(8))
            }
            gravity = Gravity.END
        }

        val savedVoiceState = sharedPreferences.getBoolean("voice_enabled", true)

        ttsSwitch = Switch(this).apply {
            text = " Voz de Mon "
            setTextColor(Color.parseColor("#DA70D6"))
            typeface = Typeface.MONOSPACE
            isChecked = savedVoiceState
            setThumbTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#DA70D6")))
            setTrackTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#5A189A")))
            textSize = 14f
            setOnCheckedChangeListener { _, isChecked ->
                MonLogger.d(TAG, "ttsSwitch: Cambio de estado de voz Loquendo a -> $isChecked")
                sharedPreferences.edit().putBoolean("voice_enabled", isChecked).apply()
            }
        }

        topControlsLayout.addView(ttsSwitch)

        tvLastMessages = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dpToPx(8), 0, dpToPx(8), dpToPx(8))
            }
            setTextColor(Color.parseColor("#B19CD9"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            visibility = android.view.View.GONE
        }

        val inputBackground = GradientDrawable().apply {
            setColor(Color.parseColor("#FF0F051D"))
            setStroke(dpToPx(2), Color.parseColor("#DA70D6"))
            cornerRadius = dpToPxf(24f)
        }

        val inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = inputBackground
            setPadding(dpToPx(10), dpToPx(4), dpToPx(6), dpToPx(4))
            gravity = Gravity.CENTER_VERTICAL
        }

        etUserInput = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            hint = "Escribe un mensaje..."
            setHintTextColor(Color.parseColor("#AA93D6"))
            setTextColor(Color.parseColor("#FFFFFF"))

            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            typeface = Typeface.MONOSPACE
            textSize = 16f

            isSingleLine = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 2
            filters = arrayOf(android.text.InputFilter.LengthFilter(2048))
            imeOptions = EditorInfo.IME_ACTION_SEND

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    MonLogger.d(TAG, "etUserInput: Acción IME_ACTION_SEND detectada desde teclado")
                    triggerSend()
                    true
                } else {
                    false
                }
            }
        }

        val sendButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(dpToPx(8), 0, 0, 0)
            }
            text = "▶"
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 16f
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#7B2CBF"))
                cornerRadius = dpToPxf(20f)
            }
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            setOnClickListener {
                MonLogger.d(TAG, "sendButton: Click en botón de envío manual")
                triggerSend()
            }
        }

        val micRow = FrameLayout(this)
        micButton = Button(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(208), dpToPx(88), Gravity.CENTER_HORIZONTAL)
            text = "Hablar"
            contentDescription = "Hablar con Mon"
            isAllCaps = false
            textSize = 20f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            val micIcon = ContextCompat.getDrawable(this@Skull, R.drawable.ic_mic_mon)
            setCompoundDrawablesWithIntrinsicBounds(null, micIcon, null, null)
            compoundDrawablePadding = dpToPx(4)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(8))
            val fill = GradientDrawable().apply {
                cornerRadius = dpToPxf(30f)
                color = ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(Color.rgb(75, 51, 95), Color.rgb(120, 41, 172))
                )
                setStroke(dpToPx(2), Color.rgb(223, 155, 248))
            }
            background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), fill, null)
            setOnClickListener {
                if (isListening) stopListening() else checkPermissionAndStartListening()
            }
        }
        micRow.addView(micButton)
        inputContainer.addView(etUserInput)
        inputContainer.addView(sendButton)

        bottomWrapper.addView(topControlsLayout)
        bottomWrapper.addView(tvLastMessages)
        bottomWrapper.addView(micRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpToPx(12) })
        bottomWrapper.addView(inputContainer)
        // Configurar detección de teclado físico (T9 / QWERTY) al iniciar
        HardwareKeyboardHelper.setupHardwareKeyboard(
            editText = etUserInput,
            onEnterPressed = { triggerSend() }
        )
        rootLayout.addView(matrixView)
        rootLayout.addView(dialogueBox)
        rootLayout.addView(bottomWrapper)
        dashboard.addView(rootLayout)
        dashboard.addView(musicPanel)
        var previousPanelWidth = -1
        dashboard.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val wide = width >= dpToPx(540) && width > bottom - top
            val panelWidth = if (wide) (width * 0.30f).toInt().coerceAtMost(dpToPx(360)) else 0
            if (panelWidth != previousPanelWidth) {
                previousPanelWidth = panelWidth
                dashboard.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                if (wide) {
                    rootLayout.bringToFront()
                    rootLayout.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    musicPanel.layoutParams = LinearLayout.LayoutParams(panelWidth, LinearLayout.LayoutParams.MATCH_PARENT)
                } else {
                    musicPanel.bringToFront()
                    rootLayout.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                    musicPanel.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                micButton.translationX = -panelWidth / 2f
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(dashboard) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            dashboard.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, ime))
            val controlsVisibility = if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
            musicPanel.visibility = controlsVisibility
            micRow.visibility = controlsVisibility
            insets
        }
        setContentView(dashboard)
        DriveSyncJob.schedule(applicationContext)
        if (savedInstanceState == null) receiveSharedMp3(intent)
        lifecycleScope.launch {
            MonLogger.d(TAG, "onCreate: Consultando repositorio de memoria para cargar historial inicial")

            val history = viewModel.memoryRepo.getLastChatMessages(12)
            if (history.isNotEmpty()) {
                sentMessagesHistory.addAll(history)
                tvLastMessages.visibility = android.view.View.VISIBLE
                val filteredHistory = sentMessagesHistory.filter { it.startsWith("lily") }
                tvLastMessages.text = filteredHistory.takeLast(2).joinToString("\n")
                MonLogger.d(TAG, "onCreate: Historial cargado exitosamente (${history.size} mensajes)")
            } else {
                MonLogger.d(TAG, "onCreate: Historial de chat vacío o nulo")
            }
        }
    }

    private fun initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: OSBundle?) {
                    if (isListening) etUserInput.hint = "Escuchando..."
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    if (isListening) micButton.text = "Procesando"
                }

                override fun onError(error: Int) {
                    if (!isListening) return
                    resetMicrophone()
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "No entendí. Toca el micrófono e inténtalo de nuevo."
                        else -> "El reconocimiento de voz no está disponible en este momento."
                    }
                    dialogueBox.startDialogue(listOf(message))
                }

                override fun onResults(results: OSBundle?) {
                    if (!isListening) return
                    resetMicrophone()
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (text.isNotBlank() && isAppInForeground && !isAiBusy) {
                        etUserInput.setText(text)
                        triggerSend()
                    }
                }

                override fun onPartialResults(partialResults: OSBundle?) {}
                override fun onEvent(eventType: Int, params: OSBundle?) {}
            })
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        MonLogger.d(TAG, "onConfigurationChanged: Cambio en teclado o orientación detectado")
        HardwareKeyboardHelper.setupHardwareKeyboard(
            editText = etUserInput,
            onEnterPressed = { triggerSend() }
        )
    }
    private fun checkPermissionAndStartListening() {
        if (isAiBusy || !isAppInForeground) return
        if (speechRecognizer == null) {
            dialogueBox.startDialogue(listOf("El estéreo necesita un servicio de reconocimiento de voz."))
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (isListening || isAiBusy || !isAppInForeground) return
        val recognizer = speechRecognizer ?: return
        hideKeyboard()
        isListening = true
        micButton.text = "Escuchando"
        micButton.contentDescription = "Escuchando. Toca para cancelar"
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            recognizer.startListening(intent)
        } catch (error: Exception) {
            resetMicrophone()
            dialogueBox.startDialogue(listOf("No pude abrir el micrófono."))
            MonLogger.e(TAG, "Error al abrir el micrófono", error)
        }
    }

    private fun stopListening() {
        if (!isListening) return
        resetMicrophone()
        speechRecognizer?.cancel()
    }

    private fun resetMicrophone() {
        isListening = false
        micButton.text = "Hablar"
        micButton.contentDescription = "Hablar con Mon"
        etUserInput.hint = "Escribe un mensaje..."
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            MonLogger.d(TAG, "onInit TTS: SUCCESS")
            val engine = tts ?: return
            var result = engine.setLanguage(Locale("es", "MX"))
            if (result < TextToSpeech.LANG_AVAILABLE) result = engine.setLanguage(Locale("es"))
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
                tts?.setSpeechRate(1.15f)
                tts?.setPitch(0.95f)
                MonLogger.d(TAG, "onInit TTS: Configurado idioma es-MX, velocidad 1.15, tono 0.95")

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        MonLogger.d(TAG, "TTS Utterance onStart: id=$utteranceId")
                    }
                    override fun onDone(utteranceId: String?) {
                        MonLogger.d(TAG, "TTS Utterance onDone: id=$utteranceId")
                        runOnUiThread {
                            isAiBusy = false
                            if (isAppInForeground) {
                                setInputState(true)
                            } else {
                                dialogueBox.stopModemSoundLoop()
                            }
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        MonLogger.e(TAG, "TTS Utterance onError: id=$utteranceId")
                        runOnUiThread {
                            isAiBusy = false
                            if (isAppInForeground) {
                                setInputState(true)
                            } else {
                                dialogueBox.stopModemSoundLoop()
                            }
                        }
                    }
                })
            } else {
                MonLogger.e(TAG, "onInit TTS: Idioma no soportado o datos faltantes")
            }
        } else {
            MonLogger.e(TAG, "onInit TTS: Falló la inicialización con estado $status")
        }
    }

    private fun speakLoquendo(text: String) {
        MonLogger.d(TAG, "speakLoquendo: Solicitado decir texto (longitud: ${text.length}, ttsSwitch=${ttsSwitch.isChecked}, isTtsReady=$isTtsReady)")
        dialogueBox.stopModemSoundLoop()
        if (ttsSwitch.isChecked && isTtsReady) {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "LoquendoUtteranceId")
            }
            if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "LoquendoUtteranceId") != TextToSpeech.SUCCESS) {
                setInputState(true)
            }
            MonLogger.d(TAG, "speakLoquendo: Comando speak enviado a motor TTS")
        } else {
            MonLogger.d(TAG, "speakLoquendo: TTS desactivado o no listo, liberando estado AI directamente")
            runOnUiThread {
                isAiBusy = false
                setInputState(true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
        MonLogger.d(TAG, "=== onResume: SkullActivity en primer plano ===")
        HardwareKeyboardHelper.setupHardwareKeyboard(
            editText = etUserInput,
            onEnterPressed = { triggerSend() }
        )

        MonLogger.d(TAG, "=== onResume: SkullActivity en primer plano ===")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            MonLogger.d(TAG, "onResume: Restaurado en modo PiP. Manteniendo estado.")
            return
        }


    }

    // =====================================
    // LÓGICA DE PICTURE-IN-PICTURE (PIP)
    // =====================================
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Check if the device supports Picture-in-Picture
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                // Entrar en modo PiP al presionar el botón de Home o al salir de la app
                try {
                    val pipParams = PictureInPictureParams.Builder().build()
                    enterPictureInPictureMode(pipParams)
                } catch (e: IllegalStateException) {
                    MonLogger.e(TAG, "onUserLeaveHint: Device doesn't support picture-in-picture mode", e)
                } catch (e: Exception) {
                    MonLogger.e(TAG, "onUserLeaveHint: Error entering PiP mode", e)
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        }
        if (isInPictureInPictureMode) {
            // Ocultar la barra inferior de controles en la ventana minimizada
            bottomWrapper.visibility = android.view.View.GONE
            stopListening()
            MonLogger.d(TAG, "onPictureInPictureModeChanged: Entrando a PiP, ocultando controles.")
        } else {
            // Restaurar los controles al volver a pantalla completa
            bottomWrapper.visibility = android.view.View.VISIBLE
            MonLogger.d(TAG, "onPictureInPictureModeChanged: Saliendo de PiP, restaurando controles.")
        }
    }


    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isAiBusy) {
            return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode

            // Detección de teclas de entrada física (Números 0-9, letras, *, #, borrar, soft keys)
            val isInputKey = (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) ||
                    (keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) ||
                    (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) ||
                    keyCode == KeyEvent.KEYCODE_STAR || keyCode == KeyEvent.KEYCODE_NUMPAD_MULTIPLY ||
                    keyCode == KeyEvent.KEYCODE_POUND || keyCode == KeyEvent.KEYCODE_SPACE ||
                    keyCode == KeyEvent.KEYCODE_SOFT_RIGHT || keyCode == KeyEvent.KEYCODE_DEL ||
                    keyCode == KeyEvent.KEYCODE_CLEAR || keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER

            if (isInputKey) {
                if (!etUserInput.isFocused) {
                    MonLogger.d(TAG, "dispatchKeyEvent: Enfocando etUserInput automáticamente por tecla $keyCode")
                    etUserInput.requestFocus()
                }
                hideKeyboard()
                val handled = etUserInput.dispatchKeyEvent(event)
                if (handled) {
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        isAppInForeground = false
        MonLogger.d(TAG, "=== onStop: SkullActivity pasó a segundo plano ===")
        stopListening()
        viewModel.music.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        isAppInForeground = false
        aiJob?.cancel()
        viewModel.music.onChange = null
        speechRecognizer?.destroy()
        dialogueBox.stopModemSoundLoop()
        tts?.stop()
        tts?.shutdown()
    }

    private fun setInputState(enabled: Boolean) {
        MonLogger.d(TAG, "setInputState: Cambiando estado de UI de entrada a -> habilitado=$enabled")
        runOnUiThread {
            etUserInput.isEnabled = enabled
            micButton.isEnabled = enabled
            micButton.text = if (enabled) "Hablar" else "Espera…"
            micButton.contentDescription = if (enabled) "Hablar con Mon" else "Mon está procesando"
            if (!enabled) {
                stopListening()
                isAiBusy = true
                etUserInput.hint = "Espera..."
                etUserInput.setHintTextColor(Color.parseColor("#AA93D6"))

            } else {
                isAiBusy = false
                dialogueBox.stopModemSoundLoop()
                etUserInput.hint = "Escribe un mensaje..."
                etUserInput.setHintTextColor(Color.parseColor("#AA93D6"))
                MonLogger.d(TAG, "setInputState: UI desbloqueada, sonido de módem apagado")
            }
        }
    }

    private fun addMessageToHistory(msg: String) {
        sentMessagesHistory.add(msg)
        if (sentMessagesHistory.size > 12) {
            sentMessagesHistory.removeAt(0)
        }
        MonLogger.d(TAG, "addMessageToHistory: Mensaje agregado al buffer local (Total en memoria: ${sentMessagesHistory.size})")
        runOnUiThread {
            tvLastMessages.visibility = android.view.View.VISIBLE
            val filteredHistory = sentMessagesHistory.filter { it.startsWith("lily") }
            tvLastMessages.text = filteredHistory.takeLast(2).joinToString("\n")
        }
    }

    private fun triggerSend() {
        val input = etUserInput.text.toString().trim()
        MonLogger.d(TAG, "triggerSend: Intentando enviar mensaje de usuario. Longitud: ${input.length}")

        if (input.isNotEmpty() && !isAiBusy) {
            stopListening()
            hideKeyboard()
            etUserInput.text.clear()

            val lowerInput = input.lowercase()
            MonLogger.d(TAG, "triggerSend: Texto procesado: '$input'")

            if (lowerInput.contains("silencio") || lowerInput.contains("ya no hables") || lowerInput.contains("apaga tu voz") || lowerInput.contains("desactiva tu voz")) {
                MonLogger.d(TAG, "triggerSend: Comando detectado -> Desactivar voz Loquendo")
                ttsSwitch.isChecked = false
            } else if (lowerInput.contains("quiero escuchar") || lowerInput.contains("activa tu voz") || lowerInput.contains("sigue con tu voz") || lowerInput.contains("te voy a marcar")) {
                MonLogger.d(TAG, "triggerSend: Comando detectado -> Activar voz Loquendo")
                ttsSwitch.isChecked = true
            }

            val dateFormat = SimpleDateFormat("yyMMdd-HHmmss", Locale.getDefault())
            val timestamp = dateFormat.format(Date())
            val linuxLine = "lily[$timestamp]$ $input"

            lifecycleScope.launch {
                viewModel.memoryRepo.saveChatMessage("lily", linuxLine)
                MonLogger.d(TAG, "triggerSend: Mensaje de Lily guardado en DB")
            }
            addMessageToHistory(linuxLine)

            respondToInput(input)
        } else {
            MonLogger.d(TAG, "triggerSend: Omitido (input vacío o AI ocupada)")
        }
    }

    private fun respondToInput(input: String) {
        aiJob?.cancel()
        setInputState(false)

        aiJob = lifecycleScope.launch {
            val command = RoadChat.command(input)
            val query = RoadChat.downloadQuery(input)
            if (query != null || (awaitingDownloadTitle && command == null)) {
                awaitingDownloadTitle = false
                if (query == null && input.equals("cancela", ignoreCase = true)) {
                    sayDownloadStatus("Va, cancelé la descarga.")
                } else {
                    findDownload(query ?: input)
                }
                return@launch
            }
            awaitingDownloadTitle = false
            val music = viewModel.music
            val response = when {
                command == null -> RoadChat.reply(input)
                music.currentTitle == null -> "Agrega un MP3 para empezar, Lily."
                else -> {
                    when (command) {
                        RoadChat.Command.PLAY -> music.play()
                        RoadChat.Command.PAUSE -> music.pause()
                        RoadChat.Command.NEXT -> music.step(1)
                        RoadChat.Command.PREVIOUS -> music.step(-1)
                    }
                    music.error ?: if (command == RoadChat.Command.PAUSE) "Música en pausa." else "Va, Lily."
                }
            }

            if (isAppInForeground) dialogueBox.startDialogue(listOf(response))
            speakLoquendo(response)

            val timestamp = SimpleDateFormat("yyMMdd-HHmmss", Locale.getDefault()).format(Date())
            val line = "mon[$timestamp]$ $response"
            try {
                viewModel.memoryRepo.saveChatMessage("mon", line)
                addMessageToHistory(line)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                MonLogger.e(TAG, "No se pudo guardar la respuesta local", error)
            }
        }
    }
    private suspend fun findDownload(query: String) {
        if (query.isBlank()) {
            awaitingDownloadTitle = true
            sayDownloadStatus("¿Qué canción quieres que descargue, Lily?")
            return
        }
        if (query.lowercase(Locale.ROOT) in setOf("todas", "todo", "todas las canciones", "todas las rolas")) {
            syncDriveNow()
            return
        }
        dialogueBox.startDialogue(listOf("Buscando «$query» en tu carpeta de Drive..."))
        try {
            val matches = withContext(Dispatchers.IO) { musicDownloads.search(query) }
            when {
                matches.isEmpty() -> sayDownloadStatus("No encontré «$query» en tu carpeta de Drive.")
                matches.size == 1 -> saveDownload(matches.first())
                isAppInForeground -> {
                    setInputState(true)
                    dialogueBox.startDialogue(listOf("Encontré varias pistas. Elige cuál guardar."))
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("¿Cuál quieres descargar?")
                        .setItems(matches.map { it.title }.toTypedArray()) { _, choice ->
                            setInputState(false)
                            aiJob = lifecycleScope.launch { saveDownload(matches[choice]) }
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
                else -> setInputState(true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            MonLogger.e(TAG, "No se pudo buscar en Drive", error)
            sayDownloadStatus("No pude buscar la canción. Revisa la conexión a Internet.")
        }
    }

    private suspend fun syncDriveNow() {
        dialogueBox.startDialogue(listOf("Revisando tu carpeta de Drive..."))
        try {
            val complete = withContext(Dispatchers.IO) { musicDownloads.sync() }
            sayDownloadStatus(if (complete) {
                "Terminé de revisar Drive. Abre Biblioteca para ver tus rolas."
            } else {
                "Algunas pistas no se pudieron guardar. Lo intentaré en la próxima revisión."
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            MonLogger.e(TAG, "No se pudo sincronizar Drive", error)
            sayDownloadStatus("No pude abrir tu carpeta pública de Drive. Revisa la conexión.")
        }
    }

    private suspend fun saveDownload(result: MusicDownloads.Result) {
        dialogueBox.startDialogue(listOf("Descargando «${result.title}»..."))
        try {
            val track = withContext(Dispatchers.IO) { musicDownloads.download(result) }
            viewModel.music.addDownload(track)
            val message = if (track.added) {
                "Guardé «${track.title}» en tu biblioteca."
            } else {
                "«${track.title}» ya está en tu biblioteca."
            }
            sayDownloadStatus(message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            MonLogger.e(TAG, "No se pudo descargar la pista", error)
            sayDownloadStatus("No pude guardar esa pista. Prueba con otra versión.")
        }
    }

    private fun sayDownloadStatus(message: String) {
        if (isAppInForeground) {
            dialogueBox.startDialogue(listOf(message))
            speakLoquendo(message)
        } else {
            setInputState(true)
        }
    }

    private fun hideKeyboard() {
        MonLogger.d(TAG, "hideKeyboard: Ocultando teclado virtual")
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etUserInput.windowToken, 0)
    }
}
