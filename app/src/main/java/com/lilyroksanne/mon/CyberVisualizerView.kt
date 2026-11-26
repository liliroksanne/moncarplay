package com.lilyroksanne.mon

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * Lightweight zero-allocation cyberpunk audio spectrum bar view.
 * Connects to Android's AudioFX Visualizer for real FFT frequency bands when
 * RECORD_AUDIO is granted and supported by the head unit, falling back smoothly
 * to a low-CPU rhythmic spectrum when unavailable.
 */
internal class CyberVisualizerView(context: Context) : View(context) {

    private val barCount = 18
    private val levels = FloatArray(barCount)
    private val targets = FloatArray(barCount)
    private val peaks = FloatArray(barCount)

    private val paintCyan = Paint().apply { color = Color.rgb(0, 240, 255); style = Paint.Style.FILL }
    private val paintPurple = Paint().apply { color = Color.rgb(181, 55, 242); style = Paint.Style.FILL }
    private val paintMagenta = Paint().apply { color = Color.rgb(255, 42, 133); style = Paint.Style.FILL }
    private val paintPeak = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val paintTrack = Paint().apply { color = Color.argb(65, 0, 240, 255); style = Paint.Style.FILL }
    private val paintBorder = Paint().apply {
        color = Color.argb(120, 0, 240, 255)
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density
    }

    private var visualizer: Visualizer? = null
    private var attachedSessionId: Int = 0
    private var hasRealFft = false
    private var isPlaying = false
    private var isVisibleActive = true
    private var phase = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val frameDelayMs = 66L // ~15 FPS for ultra-low CPU on CarPlay head units

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isVisibleActive) return
            var stillMoving = false
            phase += 0.24f

            for (i in 0 until barCount) {
                val target = if (isPlaying) {
                    if (hasRealFft) {
                        targets[i]
                    } else {
                        val wave1 = (sin(phase * 1.3f + i * 0.55f) + 1f) * 0.34f
                        val wave2 = (sin(phase * 2.7f - i * 0.9f) + 1f) * 0.22f
                        val bassBoost = if (i < 5) 0.22f else if (i < 12) 0.12f else 0.05f
                        (wave1 + wave2 + bassBoost).coerceIn(0.08f, 0.96f)
                    }
                } else {
                    0f
                }

                val current = levels[i]
                val next = if (target > current) {
                    current + (target - current) * 0.52f
                } else {
                    (current - 0.085f).coerceAtLeast(target)
                }
                levels[i] = next

                if (next >= peaks[i]) {
                    peaks[i] = next
                } else {
                    peaks[i] = (peaks[i] - 0.028f).coerceAtLeast(0f)
                }

                if (next > 0.01f || peaks[i] > 0.01f) stillMoving = true
            }

            invalidate()
            if (isPlaying || stillMoving) {
                handler.postDelayed(this, frameDelayMs)
            }
        }
    }

    fun updateAudioState(playing: Boolean, audioSessionId: Int) {
        val stateChanged = isPlaying != playing || attachedSessionId != audioSessionId
        isPlaying = playing

        if (playing && audioSessionId > 0) {
            if (attachedSessionId != audioSessionId || visualizer == null) {
                attachVisualizer(audioSessionId)
            } else {
                runCatching { visualizer?.enabled = true }
            }
        } else {
            runCatching { visualizer?.enabled = false }
            if (!playing) {
                hasRealFft = false
                targets.fill(0f)
            }
        }

        if (stateChanged && isVisibleActive) {
            handler.removeCallbacks(tickRunnable)
            handler.post(tickRunnable)
        }
    }

    private fun attachVisualizer(sessionId: Int) {
        releaseVisualizer()
        attachedSessionId = sessionId
        hasRealFft = false

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            val range = Visualizer.getCaptureSizeRange()
            val captureSize = range[0].coerceAtLeast(128)
            val vis = Visualizer(sessionId).apply {
                this.captureSize = captureSize
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            if (fft == null || fft.size < 4 || !isPlaying) return
                            processFft(fft)
                        }
                    },
                    (Visualizer.getMaxCaptureRate() / 2).coerceAtLeast(10000),
                    false,
                    true
                )
                enabled = true
            }
            visualizer = vis
            hasRealFft = true
        } catch (_: Exception) {
            releaseVisualizer()
            hasRealFft = false
        }
    }

    private fun processFft(fft: ByteArray) {
        val bins = fft.size / 2
        if (bins <= 2) return
        val usableBins = (bins - 1).coerceAtLeast(1)
        for (band in 0 until barCount) {
            // Logarithmic grouping across low, mid, and high frequencies
            val startFrac = band.toFloat() / barCount
            val endFrac = (band + 1).toFloat() / barCount
            val startBin = (1 + (usableBins - 1) * startFrac * startFrac).toInt().coerceIn(1, bins - 1)
            val endBin = (1 + (usableBins - 1) * endFrac * endFrac).toInt().coerceIn(startBin, bins - 1)

            var maxMag = 0f
            for (k in startBin..endBin) {
                val re = fft[2 * k].toFloat()
                val im = if (2 * k + 1 < fft.size) fft[2 * k + 1].toFloat() else 0f
                val mag = hypot(re, im)
                if (mag > maxMag) maxMag = mag
            }
            // Convert magnitude to smooth 0..1 bar height with slight treble compensation
            val normalized = (ln(1f + maxMag * (1.2f + band * 0.12f)) / 4.8f).coerceIn(0f, 1f)
            targets[band] = normalized
        }
        hasRealFft = true
    }

    fun setActive(active: Boolean) {
        isVisibleActive = active
        handler.removeCallbacks(tickRunnable)
        if (active) {
            if (isPlaying) {
                runCatching { visualizer?.enabled = true }
                handler.post(tickRunnable)
            }
        } else {
            runCatching { visualizer?.enabled = false }
        }
    }

    fun releaseVisualizer() {
        val vis = visualizer
        visualizer = null
        attachedSessionId = 0
        hasRealFft = false
        if (vis != null) {
            runCatching { vis.enabled = false }
            runCatching { vis.release() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pad = 3f * resources.displayMetrics.density
        canvas.drawRect(1f, 1f, w - 1f, h - 1f, paintBorder)

        val innerW = (w - pad * 2f).coerceAtLeast(1f)
        val innerH = (h - pad * 2f).coerceAtLeast(1f)
        val gap = 2f * resources.displayMetrics.density
        val barW = ((innerW - gap * (barCount - 1)) / barCount).coerceAtLeast(2f)
        val segments = 8
        val segGap = 1.5f * resources.displayMetrics.density
        val segH = ((innerH - segGap * (segments - 1)) / segments).coerceAtLeast(1.5f)
        val baseBottom = h - pad

        for (i in 0 until barCount) {
            val left = pad + i * (barW + gap)
            val right = left + barW

            // Subtle dark baseline segment
            canvas.drawRect(left, baseBottom - segH, right, baseBottom, paintTrack)

            val litSegments = (levels[i] * segments + 0.35f).toInt().coerceIn(0, segments)
            for (s in 0 until litSegments) {
                val segBottom = baseBottom - s * (segH + segGap)
                val segTop = segBottom - segH
                val paint = when {
                    s >= segments - 2 -> paintMagenta
                    s >= segments - 5 -> paintPurple
                    else -> paintCyan
                }
                canvas.drawRect(left, segTop, right, segBottom, paint)
            }

            if (peaks[i] > 0.04f) {
                val peakY = (baseBottom - peaks[i] * innerH).coerceIn(pad, baseBottom - 2f)
                canvas.drawRect(left, peakY, right, peakY + 2f * resources.displayMetrics.density, paintPeak)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(tickRunnable)
        releaseVisualizer()
    }
}
