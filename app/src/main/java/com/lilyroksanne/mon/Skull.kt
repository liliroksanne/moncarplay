package com.lilyroksanne.mon

import android.Manifest
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.lilyroksanne.mon.util.HardwareKeyboardHelper
import com.lilyroksanne.mon.util.MonLogger
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
        allowTemporaryExternalIntent = false
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

    private lateinit var matrixView: MatrixSkullView
    private lateinit var musicPanel: Mp3Panel
    private lateinit var dialogueBox: MatrixDialogueBox
    private lateinit var etUserInput: EditText
    private lateinit var bottomWrapper: LinearLayout
    private lateinit var tvLastMessages: TextView
    private lateinit var ttsSwitch: Switch
    private lateinit var micButton: Button

    private lateinit var sharedPreferences: SharedPreferences
    private val timestampFormat = SimpleDateFormat("yyMMdd-HHmmss", Locale.US)

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingStartupSpeech: String? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isAiBusy = false
    private var awaitingDownloadTitle = false

    private val sentMessagesHistory = mutableListOf<String>()

    private var aiJob: Job? = null
    private var artworkJob: Job? = null
    private var isAppInForeground = false
    private var isWideLayout = true

    // === KIOSK EXIT LOCK STATE (3x Hablar + 1x Play) ===
    private val uiHandler = Handler(Looper.getMainLooper())
    private var exitUnlocked = false
    private var allowTemporaryExternalIntent = false
    private var comboHablarCount = 0
    private var lastComboTapMs = 0L
    private val comboTimeoutMs = 5_000L
    private val clearHintRunnable = Runnable {
        if (::musicPanel.isInitialized) musicPanel.setUnlockHint(null)
    }

    // Track last announced Drive sync runs so Mon announces check start and completion once
    private var announcedSearchRunId = -1L
    private var announcedFinishRunId = -1L
    private var announcedWaitingOnce = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        allowTemporaryExternalIntent = false
        if (isGranted) {
            startListening()
        } else {
            MonLogger.w(TAG, "Permiso de micrófono denegado")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MonLogger.init(this)

        configureLockscreenAndFullscreen()
        registerBackBlocker()

        sharedPreferences = getSharedPreferences("MonPrefs", Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)
        initializeSpeechRecognizer()

        val density = resources.displayMetrics.density
        val dpToPx = { dp: Int -> (dp * density).toInt() }
        val dpToPxf = { dp: Float -> dp * density }

        val dashboard = LinearLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Right panel keeps pure Matrix avatar background (album art lives in the left panel!)
        matrixView = MatrixSkullView(this).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            frameIntervalMillis = 65L
            minWaitSeconds = 0.45f
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

        // Cyberpunk Hablar button -> placed in the LEFT panel (Mp3Panel)
        micButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(64)
            )
            text = "HABLAR"
            contentDescription = "Hablar con Mon"
            isAllCaps = false
            textSize = 17f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            val micIcon = ContextCompat.getDrawable(this@Skull, R.drawable.ic_mic_mon)
            setCompoundDrawablesWithIntrinsicBounds(micIcon, null, null, null)
            compoundDrawablePadding = dpToPx(8)
            gravity = Gravity.CENTER
            setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
            val fill = GradientDrawable().apply {
                cornerRadius = dpToPxf(10f)
                color = ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(Color.rgb(55, 28, 72), Color.rgb(122, 18, 168))
                )
                setStroke(dpToPx(2), Color.rgb(0, 240, 255))
            }
            background = RippleDrawable(ColorStateList.valueOf(0x5500F0FF), fill, null)
            setOnClickListener { handleHablarClick() }
        }

        val albumArtwork = AlbumArtwork(applicationContext)
        var shownTrack: android.net.Uri? = null
        val music = viewModel.music

        musicPanel = Mp3Panel(
            context = this,
            music = music,
            microphone = micButton,
            onPlayButtonTap = ::handlePlayClickForUnlock,
            retrySync = {
                announceBot("Revisando Drive ahora mismo para buscar nuevas rolas, Lily.", unlockInputAfter = false)
                DriveSyncJob.start(applicationContext, force = true)
            },
            onMusicChanged = {
                val uri = music.currentUri
                val name = music.currentTitle
                if (uri != null && name != null && uri != shownTrack) {
                    shownTrack = uri
                    artworkJob?.cancel()
                    musicPanel.clearArtwork()
                    artworkJob = lifecycleScope.launch {
                        val artwork = try {
                            withContext(Dispatchers.IO) { albumArtwork.load(uri, name) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        if (shownTrack == uri) {
                            musicPanel.setArtwork(artwork)
                        } else if (artwork != null && !artwork.isRecycled) {
                            artwork.recycle()
                        }
                    }
                } else if (uri == null) {
                    shownTrack = null
                    artworkJob?.cancel()
                    musicPanel.clearArtwork()
                }
            }
        ) {
            allowTemporaryExternalIntent = true
            importMp3.launch(arrayOf("audio/*", "application/octet-stream"))
        }

        dialogueBox = MatrixDialogueBox(this).apply {
            skullView = matrixView
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
            }
            typingVolume = 0.12f
            startDialogue(listOf("Mon en línea. Iniciando enlace con Drive..."))
            onDialogueFinished = {}
        }

        bottomWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                setMargins(dpToPx(12), 0, dpToPx(12), dpToPx(12))
            }
        }

        val topControlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dpToPx(6), 0, dpToPx(6), dpToPx(6))
            }
            gravity = Gravity.END
        }

        val savedVoiceState = sharedPreferences.getBoolean("voice_enabled", true)

        ttsSwitch = Switch(this).apply {
            text = " Voz de Mon "
            setTextColor(Color.rgb(0, 240, 255))
            typeface = Typeface.MONOSPACE
            isChecked = savedVoiceState
            thumbTintList = ColorStateList.valueOf(Color.rgb(255, 42, 133))
            trackTintList = ColorStateList.valueOf(Color.rgb(70, 22, 110))
            textSize = 13f
            setOnCheckedChangeListener { _, isChecked ->
                sharedPreferences.edit().putBoolean("voice_enabled", isChecked).apply()
            }
        }
        topControlsLayout.addView(ttsSwitch)

        tvLastMessages = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dpToPx(8), 0, dpToPx(8), dpToPx(6))
            }
            setTextColor(Color.parseColor("#B19CD9"))
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
        }

        val inputBackground = GradientDrawable().apply {
            setColor(Color.parseColor("#EE0B0418"))
            setStroke(dpToPx(1), Color.rgb(0, 240, 255))
            cornerRadius = dpToPxf(16f)
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
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            hint = "Escribe un mensaje..."
            setHintTextColor(Color.parseColor("#9C89C8"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            typeface = Typeface.MONOSPACE
            textSize = 15f
            isSingleLine = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 2
            filters = arrayOf(android.text.InputFilter.LengthFilter(1024))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
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
                setMargins(dpToPx(6), 0, 0, 0)
            }
            text = "▶"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(Color.rgb(122, 18, 168))
                setStroke(dpToPx(1), Color.rgb(255, 42, 133))
                cornerRadius = dpToPxf(12f)
            }
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            setOnClickListener { triggerSend() }
        }

        inputContainer.addView(etUserInput)
        inputContainer.addView(sendButton)

        bottomWrapper.addView(topControlsLayout)
        bottomWrapper.addView(tvLastMessages)
        bottomWrapper.addView(inputContainer)

        HardwareKeyboardHelper.setupHardwareKeyboard(
            editText = etUserInput,
            onEnterPressed = { triggerSend() }
        )

        rootLayout.addView(matrixView)
        rootLayout.addView(dialogueBox)
        rootLayout.addView(bottomWrapper)

        dashboard.addView(musicPanel)
        dashboard.addView(rootLayout)

        var previousPanelWidth = -1
        dashboard.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val wide = width >= dpToPx(520) && width > bottom - top
            isWideLayout = wide
            val panelWidth = if (wide) {
                (width * 0.36f).toInt().coerceIn(dpToPx(260), dpToPx(420))
            } else {
                0
            }
            if (panelWidth != previousPanelWidth) {
                previousPanelWidth = panelWidth
                dashboard.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                if (wide) {
                    rootLayout.bringToFront()
                    musicPanel.layoutParams = LinearLayout.LayoutParams(panelWidth, LinearLayout.LayoutParams.MATCH_PARENT)
                    rootLayout.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                } else {
                    musicPanel.bringToFront()
                    rootLayout.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                    musicPanel.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
            }
        }

        // Edge-to-edge immersive: only add IME bottom inset so top system bar stays covered!
        ViewCompat.setOnApplyWindowInsetsListener(dashboard) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            dashboard.setPadding(0, 0, 0, ime)
            if (!isWideLayout) {
                musicPanel.visibility = if (insets.isVisible(WindowInsetsCompat.Type.ime())) View.GONE else View.VISIBLE
            } else {
                musicPanel.visibility = View.VISIBLE
            }
            insets
        }

        setContentView(dashboard)
        applyImmersiveFullscreen()

        if (savedInstanceState == null) receiveSharedMp3(intent)

        // Restore Drive diagnostic state, observe live updates, and trigger startup sync
        DriveSyncStatus.restore(applicationContext)
        DriveSyncJob.registerNetworkObserver(applicationContext)
        observeDriveSync()
        DriveSyncJob.start(applicationContext, force = true)

        lifecycleScope.launch {
            val history = viewModel.memoryRepo.getLastChatMessages(12)
            if (history.isNotEmpty()) {
                sentMessagesHistory.addAll(history)
                tvLastMessages.visibility = View.VISIBLE
                val filteredHistory = sentMessagesHistory.filter { it.startsWith("lily") }
                tvLastMessages.text = filteredHistory.takeLast(2).joinToString("\n")
            }
        }
    }

    private fun configureLockscreenAndFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun applyImmersiveFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    private fun registerBackBlocker() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (exitUnlocked) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    showTempUnlockHint("[LOCK] SALIR: 3x HABLAR + 1x PLAY")
                }
            }
        })
    }

    // === SECRET EXIT SEQUENCE: 3x HABLAR + 1x PLAY ===
    private fun handleHablarClick() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastComboTapMs > comboTimeoutMs) {
            comboHablarCount = 0
        }
        lastComboTapMs = now
        comboHablarCount = (comboHablarCount + 1).coerceAtMost(3)

        when (comboHablarCount) {
            1 -> {
                if (!isAiBusy) {
                    if (isListening) stopListening() else checkPermissionAndStartListening()
                }
            }
            2 -> {
                stopListening()
                showTempUnlockHint("[SEQ 2/3] HABLAR...")
            }
            3 -> {
                stopListening()
                showTempUnlockHint("[SEQ 3/3] PULSA PLAY PARA SALIR")
            }
        }
    }

    private fun handlePlayClickForUnlock(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val armed = comboHablarCount >= 3 && (now - lastComboTapMs) <= comboTimeoutMs
        comboHablarCount = 0
        lastComboTapMs = 0L
        uiHandler.removeCallbacks(clearHintRunnable)
        musicPanel.setUnlockHint(null)

        if (armed) {
            exitUnlocked = true
            announceBot("Secuencia aceptada, Lily. Saliendo de Mon.", unlockInputAfter = false)
            uiHandler.postDelayed({ performControlledExit() }, 650L)
            return true
        }
        return false
    }

    private fun showTempUnlockHint(hint: String) {
        musicPanel.setUnlockHint(hint)
        uiHandler.removeCallbacks(clearHintRunnable)
        uiHandler.postDelayed(clearHintRunnable, comboTimeoutMs)
    }

    private fun performControlledExit() {
        stopListening()
        viewModel.music.pause()

        // If Mon is currently set as the default Android Home launcher, finishing alone would relaunch Mon.
        // Launch another installed Home launcher (or Android Home settings) so Lily actually exits cleanly.
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val currentDefault = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val isMonDefaultHome = currentDefault?.activityInfo?.packageName == packageName

        if (isMonDefaultHome) {
            val otherHome = packageManager.queryIntentActivities(homeIntent, 0)
                .firstOrNull { it.activityInfo.packageName != packageName }
            if (otherHome != null) {
                val exitIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    component = ComponentName(otherHome.activityInfo.packageName, otherHome.activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { startActivity(exitIntent) }
            } else {
                runCatching {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            finish()
        } else {
            moveTaskToBack(true)
            finish()
        }
    }

    private fun observeDriveSync() {
        lifecycleScope.launch {
            DriveSyncStatus.state.collect { state ->
                musicPanel.renderSync(state)

                when (state.phase) {
                    DriveSyncStatus.Phase.SEARCHING -> {
                        if (state.runId != announcedSearchRunId) {
                            announcedSearchRunId = state.runId
                            announceBot(
                                "Hola Lily, voy a buscar en Drive si hay nuevas rolas que descargar.",
                                unlockInputAfter = false
                            )
                        }
                    }
                    DriveSyncStatus.Phase.WAITING -> {
                        if (!announcedWaitingOnce && announcedSearchRunId == -1L) {
                            announcedWaitingOnce = true
                            announceBot(
                                "Hola Lily, todavía no hay internet. En cuanto conecte voy a buscar si hay nuevas rolas que descargar.",
                                unlockInputAfter = false
                            )
                        }
                    }
                    DriveSyncStatus.Phase.COMPLETE -> {
                        if (state.runId > 0L && state.runId != announcedFinishRunId) {
                            announcedFinishRunId = state.runId
                            val msg = if (state.downloaded.isNotEmpty()) {
                                val count = state.downloaded.size
                                val sample = state.downloaded.take(2).joinToString(", ")
                                "Listo Lily, descargué $count ${if (count == 1) "rola nueva" else "rolas nuevas"}: $sample."
                            } else {
                                "Ya revisé Drive, Lily. No hay rolas nuevas que descargar, todo al día."
                            }
                            announceBot(msg, unlockInputAfter = false)
                        }
                    }
                    DriveSyncStatus.Phase.ERROR -> {
                        if (state.runId > 0L && state.runId != announcedFinishRunId) {
                            announcedFinishRunId = state.runId
                            val msg = if (state.downloaded.isNotEmpty()) {
                                "Descargué ${state.downloaded.size} rolas, Lily, pero hubo errores en Drive. Revisa la barra de abajo."
                            } else {
                                "Lily, hubo un error al buscar rolas en Drive. Toca la barra de descarga para ver qué falló."
                            }
                            announceBot(msg, unlockInputAfter = false)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun announceBot(text: String, unlockInputAfter: Boolean) {
        if (!isAppInForeground) return
        if (isListening || isAiBusy) {
            dialogueBox.startDialogue(listOf(text))
            return
        }
        dialogueBox.startDialogue(listOf(text))
        speakGirlVoice(text, releaseInputOnDone = unlockInputAfter)
    }

    private fun initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (isListening) etUserInput.hint = "Escuchando..."
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    if (isListening) micButton.text = "PROCESANDO..."
                }

                override fun onError(error: Int) {
                    if (!isListening) return
                    resetMicrophone()
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "No entendí. Toca Hablar e inténtalo de nuevo."
                        else -> "El reconocimiento de voz no está disponible en este momento."
                    }
                    dialogueBox.startDialogue(listOf(message))
                }

                override fun onResults(results: Bundle?) {
                    if (!isListening) return
                    resetMicrophone()
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (text.isNotBlank() && isAppInForeground && !isAiBusy) {
                        etUserInput.setText(text)
                        triggerSend()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        HardwareKeyboardHelper.setupHardwareKeyboard(
            editText = etUserInput,
            onEnterPressed = { triggerSend() }
        )
        applyImmersiveFullscreen()
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
            allowTemporaryExternalIntent = true
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (isListening || isAiBusy || !isAppInForeground) return
        val recognizer = speechRecognizer ?: return
        hideKeyboard()
        isListening = true
        micButton.text = "ESCUCHANDO..."
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
        runCatching { speechRecognizer?.cancel() }
    }

    private fun resetMicrophone() {
        isListening = false
        micButton.text = "HABLAR"
        micButton.contentDescription = "Hablar con Mon"
        etUserInput.hint = "Escribe un mensaje..."
    }

    // === FEMALE SPANISH TTS CONFIGURATION ===
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            MonLogger.e(TAG, "onInit TTS: Falló la inicialización con estado $status")
            return
        }
        val engine = tts ?: return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        var result = engine.setLanguage(Locale("es", "MX"))
        if (result < TextToSpeech.LANG_AVAILABLE) result = engine.setLanguage(Locale("es", "US"))
        if (result < TextToSpeech.LANG_AVAILABLE) result = engine.setLanguage(Locale("es"))

        if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
            val selectedFemaleVoice = pickFemaleSpanishVoice(engine)
            if (selectedFemaleVoice != null) {
                runCatching { engine.voice = selectedFemaleVoice }
                engine.setPitch(1.10f)
                engine.setSpeechRate(1.06f)
                MonLogger.i(TAG, "TTS voz femenina seleccionada: ${selectedFemaleVoice.name}")
            } else {
                // Fallback: raise pitch slightly so even default stereo voice sounds feminine
                engine.setPitch(1.26f)
                engine.setSpeechRate(1.06f)
                MonLogger.i(TAG, "TTS usando voz por defecto con tono femenino (pitch 1.26)")
            }

            isTtsReady = true

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == UTTERANCE_CHAT) {
                        runOnUiThread {
                            isAiBusy = false
                            if (isAppInForeground) setInputState(true) else dialogueBox.stopModemSoundLoop()
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == UTTERANCE_CHAT) {
                        runOnUiThread {
                            isAiBusy = false
                            if (isAppInForeground) setInputState(true) else dialogueBox.stopModemSoundLoop()
                        }
                    }
                }
            })

            pendingStartupSpeech?.let { queued ->
                pendingStartupSpeech = null
                runOnUiThread {
                    if (isAppInForeground) speakGirlVoice(queued, releaseInputOnDone = false)
                }
            }
        } else {
            MonLogger.e(TAG, "onInit TTS: Idioma español no soportado o datos faltantes")
        }
    }

    private fun pickFemaleSpanishVoice(engine: TextToSpeech): Voice? {
        val voices = runCatching { engine.voices }.getOrNull()?.filter {
            it.locale?.language.equals("es", ignoreCase = true)
        }.orEmpty()
        if (voices.isEmpty()) return null

        val femaleHints = listOf(
            "female", "femenin", "woman", "girl", "chica",
            "monica", "paulina", "angelica", "sofia", "lucia", "helena", "sabina", "marisol", "esperanza",
            "sfb", "esf", "fsc", "eea", "eef", "es-mx-x-sfb", "es-us-x-sfb", "es-es-x-eea", "es-es-x-eef"
        )
        val maleHints = listOf(
            "male", "hombre", "man", "jorge", "diego", "carlos", "juan", "alvaro",
            "esd", "esc", "eed", "eeb", "edm", "es-mx-x-edm", "es-us-x-esd"
        )

        fun score(v: Voice): Int {
            val name = v.name.lowercase(Locale.ROOT)
            val features = runCatching { v.features.joinToString(" ").lowercase(Locale.ROOT) }.getOrDefault("")
            val combined = "$name $features"
            var pts = 0
            if (femaleHints.any { combined.contains(it) }) pts += 100
            if (maleHints.any { combined.contains(it) }) pts -= 150
            when (v.locale.country.uppercase(Locale.ROOT)) {
                "MX" -> pts += 30
                "US" -> pts += 20
                "ES" -> pts += 12
                else -> pts += 5
            }
            if (!v.isNetworkConnectionRequired) pts += 15
            if (v.quality >= Voice.QUALITY_HIGH) pts += 8
            return pts
        }

        val best = voices.maxByOrNull(::score) ?: return null
        return best.takeIf { score(it) > 0 }
    }

    private fun speakGirlVoice(text: String, releaseInputOnDone: Boolean) {
        dialogueBox.stopModemSoundLoop()
        if (!ttsSwitch.isChecked) {
            if (releaseInputOnDone) {
                runOnUiThread {
                    isAiBusy = false
                    setInputState(true)
                }
            }
            return
        }
        if (!isTtsReady) {
            if (!releaseInputOnDone) {
                pendingStartupSpeech = text
            } else {
                runOnUiThread {
                    isAiBusy = false
                    setInputState(true)
                }
            }
            return
        }

        val utteranceId = if (releaseInputOnDone) UTTERANCE_CHAT else UTTERANCE_ANNOUNCE
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val queued = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (queued != TextToSpeech.SUCCESS && releaseInputOnDone) {
            setInputState(true)
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
        exitUnlocked = false
        allowTemporaryExternalIntent = false
        applyImmersiveFullscreen()
        matrixView.setAnimationRunning(true)
        musicPanel.setPanelActive(true)
        HardwareKeyboardHelper.setupHardwareKeyboard(
            editText = etUserInput,
            onEnterPressed = { triggerSend() }
        )
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Kiosk lock: do NOT enter PiP or yield voluntarily while exit is locked!
        if (!exitUnlocked && !allowTemporaryExternalIntent) {
            MonLogger.i(TAG, "Salida bloqueada por modo kiosko (usa 3x Hablar + 1x Play).")
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isAiBusy) return super.dispatchKeyEvent(event)

        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            val isInputKey = (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) ||
                (keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) ||
                (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) ||
                keyCode == KeyEvent.KEYCODE_STAR || keyCode == KeyEvent.KEYCODE_NUMPAD_MULTIPLY ||
                keyCode == KeyEvent.KEYCODE_POUND || keyCode == KeyEvent.KEYCODE_SPACE ||
                keyCode == KeyEvent.KEYCODE_SOFT_RIGHT || keyCode == KeyEvent.KEYCODE_DEL ||
                keyCode == KeyEvent.KEYCODE_CLEAR || keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER

            if (isInputKey) {
                if (!etUserInput.isFocused) etUserInput.requestFocus()
                hideKeyboard()
                if (etUserInput.dispatchKeyEvent(event)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && ::musicPanel.isInitialized) {
            musicPanel.clearArtwork()
        }
    }

    override fun onStop() {
        super.onStop()
        isAppInForeground = false
        stopListening()
        matrixView.setAnimationRunning(false)
        musicPanel.setPanelActive(false)
        viewModel.music.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        isAppInForeground = false
        uiHandler.removeCallbacksAndMessages(null)
        aiJob?.cancel()
        artworkJob?.cancel()
        DriveSyncJob.unregisterNetworkObserver(applicationContext)
        viewModel.music.onChange = null
        runCatching { speechRecognizer?.destroy() }
        dialogueBox.stopModemSoundLoop()
        tts?.stop()
        tts?.shutdown()
    }

    private fun setInputState(enabled: Boolean) {
        runOnUiThread {
            etUserInput.isEnabled = enabled
            // Keep micButton clickable even while busy so Lily can NEVER be locked out of the 3x Hablar exit combo!
            micButton.isEnabled = true
            micButton.text = if (enabled) "HABLAR" else "ESPERA…"
            micButton.contentDescription = if (enabled) "Hablar con Mon" else "Mon está procesando"
            if (!enabled) {
                stopListening()
                isAiBusy = true
                etUserInput.hint = "Espera..."
            } else {
                isAiBusy = false
                dialogueBox.stopModemSoundLoop()
                etUserInput.hint = "Escribe un mensaje..."
            }
        }
    }

    private fun addMessageToHistory(msg: String) {
        sentMessagesHistory.add(msg)
        if (sentMessagesHistory.size > 12) {
            sentMessagesHistory.removeAt(0)
        }
        runOnUiThread {
            tvLastMessages.visibility = View.VISIBLE
            val filteredHistory = sentMessagesHistory.filter { it.startsWith("lily") }
            tvLastMessages.text = filteredHistory.takeLast(2).joinToString("\n")
        }
    }

    private fun triggerSend() {
        val input = etUserInput.text.toString().trim()
        if (input.isNotEmpty() && !isAiBusy) {
            stopListening()
            hideKeyboard()
            etUserInput.text.clear()

            val lowerInput = input.lowercase(Locale.ROOT)
            if (lowerInput.contains("silencio") || lowerInput.contains("ya no hables") ||
                lowerInput.contains("apaga tu voz") || lowerInput.contains("desactiva tu voz")
            ) {
                ttsSwitch.isChecked = false
            } else if (lowerInput.contains("quiero escuchar") || lowerInput.contains("activa tu voz") ||
                lowerInput.contains("sigue con tu voz") || lowerInput.contains("te voy a marcar")
            ) {
                ttsSwitch.isChecked = true
            }

            val timestamp = timestampFormat.format(Date())
            val linuxLine = "lily[$timestamp]$ $input"

            lifecycleScope.launch {
                viewModel.memoryRepo.saveChatMessage("lily", linuxLine)
            }
            addMessageToHistory(linuxLine)
            respondToInput(input)
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
            speakGirlVoice(response, releaseInputOnDone = true)

            val timestamp = timestampFormat.format(Date())
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
        speakGirlVoice("Buscando $query en tu carpeta de Drive, Lily.", releaseInputOnDone = false)
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
        dialogueBox.startDialogue(listOf("Voy a buscar en Drive si hay nuevas rolas que descargar..."))
        speakGirlVoice("Voy a buscar en Drive si hay nuevas rolas que descargar, Lily.", releaseInputOnDone = false)
        try {
            val complete = withContext(Dispatchers.IO) { musicDownloads.sync() }
            val current = DriveSyncStatus.state.value
            val summaryMsg = when {
                complete && current.downloaded.isNotEmpty() ->
                    "Terminé de revisar Drive. Descargué ${current.downloaded.size} rolas nuevas."
                complete ->
                    "Terminé de revisar Drive. No había rolas nuevas, todo está al día."
                else ->
                    "Algunas pistas fallaron al descargar. Checa la barra de abajo a la izquierda."
            }
            sayDownloadStatus(summaryMsg)
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
            speakGirlVoice(message, releaseInputOnDone = true)
        } else {
            setInputState(true)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etUserInput.windowToken, 0)
    }

    private companion object {
        const val UTTERANCE_CHAT = "MonChatUtterance"
        const val UTTERANCE_ANNOUNCE = "MonAnnounceUtterance"
    }
}
