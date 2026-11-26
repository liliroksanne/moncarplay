package com.lilyroksanne.mon

import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cyberpunk Linux-console system header placed at the top of the left panel.
 * Replaces the hidden Android system status bar with live Wi-Fi, Bluetooth,
 * power/volume/RAM telemetry, and a Linux terminal date/time readout.
 */
internal class CyberStatusHeader(context: Context) : LinearLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFormat = SimpleDateFormat("EEE yyyy-MM-dd", Locale("es", "MX"))
    private val dateBuf = Date()
    private val memInfo = ActivityManager.MemoryInfo()

    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val activityManager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val tvPrompt: TextView
    private val tvClock: TextView
    private val tvTelemetry: TextView
    private val tvLockHint: TextView

    private var active = false
    private var tickCount = 0
    private var cachedTelemetry = "WIFI:--  BT:--  VOL:--"

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            updateClock()
            if (tickCount % 3 == 0) {
                updateTelemetry()
            }
            tickCount++
            handler.postDelayed(this, 1000L)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = GradientDrawable().apply {
            setColor(Color.argb(215, 7, 4, 16))
            setStroke(dp(1), Color.rgb(0, 210, 235))
            cornerRadius = dp(6).toFloat()
        }

        tvPrompt = TextView(context).apply {
            text = "root@mon-carplay:~# sys --live"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(57, 255, 20))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        tvClock = TextView(context).apply {
            textSize = 15f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.rgb(0, 240, 255))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        tvTelemetry = TextView(context).apply {
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(255, 110, 199))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        tvLockHint = TextView(context).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(255, 184, 0))
            maxLines = 1
            visibility = GONE
        }

        addView(tvPrompt)
        addView(tvClock)
        addView(tvTelemetry)
        addView(tvLockHint)

        updateClock()
        updateTelemetry()
    }

    fun start() {
        if (active) return
        active = true
        tickCount = 0
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun stop() {
        active = false
        handler.removeCallbacks(ticker)
    }

    fun setUnlockHint(message: String?) {
        if (message.isNullOrBlank()) {
            tvLockHint.visibility = GONE
        } else {
            tvLockHint.text = message
            tvLockHint.visibility = VISIBLE
        }
    }

    private fun updateClock() {
        dateBuf.time = System.currentTimeMillis()
        val time = clockFormat.format(dateBuf)
        val date = dateFormat.format(dateBuf).uppercase(Locale("es", "MX"))
        tvClock.text = "[$time] $date"
    }

    private fun updateTelemetry() {
        val netLabel = readNetworkLabel()
        val btLabel = readBluetoothLabel()
        val volLabel = readVolumeLabel()
        val pwrLabel = readPowerOrRamLabel()
        cachedTelemetry = "$netLabel  $btLabel  $volLabel  $pwrLabel"
        tvTelemetry.text = cachedTelemetry
    }

    private fun readNetworkLabel(): String {
        val activeNet = runCatching { connectivity.activeNetwork }.getOrNull()
        val caps = activeNet?.let { runCatching { connectivity.getNetworkCapabilities(it) }.getOrNull() }
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return "NET:OFF"
        }
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val bars = readWifiBars()
                "WIFI:$bars"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "4G:ON"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETH:ON"
            else -> "NET:ON"
        }
    }

    @Suppress("DEPRECATION")
    private fun readWifiBars(): String = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val rssi = wm?.connectionInfo?.rssi ?: return "███"
        when {
            rssi >= -55 -> "████"
            rssi >= -67 -> "███░"
            rssi >= -78 -> "██░░"
            else -> "█░░░"
        }
    }.getOrDefault("ON")

    private fun readBluetoothLabel(): String = runCatching {
        val bm = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (bm?.adapter?.isEnabled == true) "BT:ON" else "BT:OFF"
    }.getOrDefault("BT:--")

    private fun readVolumeLabel(): String = runCatching {
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        "VOL:${(cur * 100) / max}%"
    }.getOrDefault("VOL:--")

    private fun readPowerOrRamLabel(): String {
        val battery = runCatching {
            val intent = context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            if (level >= 0 && scale > 0) {
                val pct = (level * 100) / scale
                if (plugged != 0) "AC:$pct%" else "BAT:$pct%"
            } else null
        }.getOrNull()

        val ramMb = runCatching {
            activityManager.getMemoryInfo(memInfo)
            memInfo.availMem / (1024L * 1024L)
        }.getOrNull()

        return when {
            ramMb != null && battery != null -> "$battery ${ramMb}M"
            ramMb != null -> "RAM:${ramMb}M"
            battery != null -> battery
            else -> "PWR:OK"
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
