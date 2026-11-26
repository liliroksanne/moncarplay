package com.lilyroksanne.mon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.sin
import kotlin.random.Random

/**
 * Low-RAM, zero-allocation-per-frame Matrix ASCII avatar view.
 * - Does NOT retain full-resolution Bitmaps in RAM after computing compact ByteArray luminance grids.
 * - Deduplicates repeated frame resource IDs.
 * - Draws characters with a preallocated CharArray(1) to eliminate String GC churn.
 * - Pauses rendering automatically when hidden or stopped.
 */
class MatrixSkullView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var matrixColor: Int = Color.parseColor("#8A2BE2")
        set(value) { field = value; updatePaints() }
    var skullColor: Int = Color.parseColor("#DA70D6")
        set(value) { field = value; updatePaints() }
    var matrixAlpha: Int = 85
        set(value) { field = value; updatePaints() }
    var skullAlpha: Int = 255
        set(value) { field = value; updatePaints() }

    var initialFadeDurationMillis: Long = 1500L
    var frameIntervalMillis: Long = 65L // ~15 FPS: smooth retro feel with ~40% less CPU
    var matrixChars: String = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@#$%&*+|@-/\\<>"

    private var isModemSoundActive = false

    var minWaitSeconds: Float = 0.25f
    var maxWaitSeconds: Float = 0.65f

    private val asciiRamp = " .:-=+*#%@AYys"
    private val charBuf = CharArray(1)

    private val paintText = Paint().apply {
        typeface = Typeface.MONOSPACE
        isAntiAlias = false
        textSize = 24f
    }

    private val paintSkull = Paint().apply {
        typeface = Typeface.MONOSPACE
        isAntiAlias = false
        textSize = 24f
    }

    private val paintGlitch = Paint().apply {
        typeface = Typeface.MONOSPACE
        isAntiAlias = false
        textSize = 24f
        color = Color.WHITE
    }

    private var lastActivityTime = System.currentTimeMillis()
    private var isBizarreIdleGlitchActive = false
    private var bizarreGlitchEndTime = 0L

    private var frameResIds: List<Int> = emptyList()
    private var grayMapsList: List<ByteArray> = emptyList()
    private var gridRows = 0
    private var gridCols = 0
    private var cachedCharWidth = 14f
    private var cachedCharHeight = 28.8f
    private var currentFrameIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private var viewCreationTime = System.currentTimeMillis()
    private var nextFrameSwitchTime = 0L
    private var isAnimationRunning = false

    private val runnable = object : Runnable {
        override fun run() {
            if (!isAnimationRunning) return
            val now = System.currentTimeMillis()
            if (grayMapsList.isNotEmpty() && now >= nextFrameSwitchTime) {
                currentFrameIndex = (currentFrameIndex + 1) % grayMapsList.size
                val randomSeconds = Random.nextDouble(minWaitSeconds.toDouble(), maxWaitSeconds.toDouble())
                nextFrameSwitchTime = now + (randomSeconds * 1000).toLong()
            }
            invalidate()
            handler.postDelayed(this, frameIntervalMillis)
        }
    }

    init {
        updatePaints()
    }

    fun setAnimationRunning(running: Boolean) {
        if (isAnimationRunning == running) return
        isAnimationRunning = running
        handler.removeCallbacks(runnable)
        if (running && isAttachedToWindow && visibility == VISIBLE) {
            handler.post(runnable)
        }
    }

    fun setModemSoundActive(active: Boolean) {
        isModemSoundActive = active
        resetIdleTimer()
    }

    fun resetIdleTimer() {
        lastActivityTime = System.currentTimeMillis()
        isBizarreIdleGlitchActive = false
        bizarreGlitchEndTime = 0L
    }

    private fun updatePaints() {
        paintText.color = matrixColor
        paintText.alpha = matrixAlpha
        paintSkull.color = skullColor
        paintSkull.alpha = skullAlpha
    }

    fun setImageFrames(resIds: List<Int>) {
        frameResIds = resIds.toList()
        grayMapsList = emptyList()
        currentFrameIndex = 0
        viewCreationTime = System.currentTimeMillis()
        nextFrameSwitchTime = viewCreationTime + 500L
        invalidate()
    }

    private fun processBitmapsOnce() {
        if (frameResIds.isEmpty() || width <= 0 || height <= 0) return

        cachedCharWidth = paintText.measureText("W").coerceAtLeast(1f)
        cachedCharHeight = (paintText.textSize * 1.2f).coerceAtLeast(1f)

        val targetCols = (width / cachedCharWidth).toInt()
        val targetRows = (height / cachedCharHeight).toInt()
        if (targetCols <= 0 || targetRows <= 0) return

        gridCols = targetCols
        gridRows = targetRows

        val cacheByResId = HashMap<Int, ByteArray>()
        val result = ArrayList<ByteArray>(frameResIds.size)

        for (resId in frameResIds) {
            val existing = cacheByResId[resId]
            if (existing != null) {
                result.add(existing)
                continue
            }

            val bmp = decodeCompactBitmap(resId, targetCols, targetRows) ?: continue
            val grayMap = try {
                buildGrayMap(bmp, targetCols, targetRows)
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
            cacheByResId[resId] = grayMap
            result.add(grayMap)
        }

        grayMapsList = result
    }

    private fun decodeCompactBitmap(resId: Int, targetCols: Int, targetRows: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, resId, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetCols * 2 &&
                    bounds.outHeight / (sample * 2) >= targetRows * 2
                ) {
                    sample *= 2
                }
                inSampleSize = sample
            }
            BitmapFactory.decodeResource(resources, resId, opts)?.let { return it }
        }
        return ContextCompat.getDrawable(context, resId)?.toBitmap(
            width = targetCols.coerceAtLeast(1),
            height = targetRows.coerceAtLeast(1),
            config = Bitmap.Config.RGB_565
        )
    }

    private fun buildGrayMap(bmp: Bitmap, targetCols: Int, targetRows: Int): ByteArray {
        val scale = minOf(
            targetCols * cachedCharWidth / bmp.width,
            targetRows * cachedCharHeight / bmp.height
        )
        val scaledWidth = (bmp.width * scale / cachedCharWidth).toInt().coerceIn(1, targetCols)
        val scaledHeight = (bmp.height * scale / cachedCharHeight).toInt().coerceIn(1, targetRows)

        val scaledBitmap = Bitmap.createScaledBitmap(bmp, scaledWidth, scaledHeight, true)
        val grayMap = ByteArray(targetRows * targetCols) { 0xFF.toByte() }
        val pixels = IntArray(scaledWidth * scaledHeight)
        try {
            scaledBitmap.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)
            val offsetY = (targetRows - scaledHeight) / 2
            val offsetX = (targetCols - scaledWidth) / 2

            for (y in 0 until scaledHeight) {
                val rowOffset = (y + offsetY) * targetCols + offsetX
                val srcOffset = y * scaledWidth
                for (x in 0 until scaledWidth) {
                    val pixel = pixels[srcOffset + x]
                    val red = (pixel shr 16) and 0xFF
                    val green = (pixel shr 8) and 0xFF
                    val blue = pixel and 0xFF
                    val gray = (red * 77 + green * 150 + blue * 29) shr 8
                    grayMap[rowOffset + x] = gray.toByte()
                }
            }
        } finally {
            if (scaledBitmap !== bmp && !scaledBitmap.isRecycled) scaledBitmap.recycle()
        }
        return grayMap
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        grayMapsList = emptyList()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setAnimationRunning(true)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        setAnimationRunning(visibility == VISIBLE)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (grayMapsList.isEmpty()) {
            processBitmapsOnce()
            if (grayMapsList.isEmpty()) return
        }

        if (currentFrameIndex >= grayMapsList.size) {
            currentFrameIndex = 0
        }

        val currentGrayMap = grayMapsList[currentFrameIndex]
        val rows = gridRows
        val cols = gridCols
        if (rows <= 0 || cols <= 0) return

        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - viewCreationTime
        val initialFadeFactor = (elapsed / initialFadeDurationMillis.toFloat()).coerceIn(0f, 1f)

        val idleMillis = currentTime - lastActivityTime
        val idleThresholdMillis = 27_000L

        if (idleMillis > idleThresholdMillis) {
            if (!isBizarreIdleGlitchActive && currentTime > bizarreGlitchEndTime) {
                if (Random.nextFloat() < 0.05f) {
                    isBizarreIdleGlitchActive = true
                    bizarreGlitchEndTime = currentTime + Random.nextLong(1000L, 2700L)
                }
            } else if (isBizarreIdleGlitchActive && currentTime > bizarreGlitchEndTime) {
                isBizarreIdleGlitchActive = false
                bizarreGlitchEndTime = currentTime + Random.nextLong(3000L, 7000L)
            }
        } else {
            isBizarreIdleGlitchActive = false
            bizarreGlitchEndTime = 0L
        }

        val charWidth = cachedCharWidth
        val charHeight = cachedCharHeight
        val rampLen = asciiRamp.length
        val matrixLen = matrixChars.length

        val analogInterferenceActive = isModemSoundActive && Random.nextFloat() < 0.4f
        val glitchBlockStartY = if (analogInterferenceActive) Random.nextInt(0, rows) else -1
        val glitchBlockHeight = if (analogInterferenceActive) Random.nextInt(2, 10) else 0
        val analogShift = if (analogInterferenceActive) (Random.nextFloat() * 80 - 40).toInt() else 0
        val useGlitchColor = analogInterferenceActive && Random.nextFloat() < 0.3f

        val bizarreInterferenceActive = isBizarreIdleGlitchActive && Random.nextFloat() < 0.1f
        val bizarreInvertMap = isBizarreIdleGlitchActive && Random.nextBoolean()
        val bizarreAsciiShift = if (isBizarreIdleGlitchActive) Random.nextInt(0, rampLen) else 0

        for (y in 0 until rows) {
            val isRowGlitched = analogInterferenceActive && y >= glitchBlockStartY && y < (glitchBlockStartY + glitchBlockHeight)
            val bizarreWaveShift = if (bizarreInterferenceActive) (sin(y / 5.0) * 40).toInt() else 0
            val currentAnalogShift = if (isRowGlitched) analogShift else 0
            val posY = y * charHeight + charHeight
            val rowOffset = y * cols

            for (x in 0 until cols) {
                val rawGray = currentGrayMap[rowOffset + x].toInt() and 0xFF
                val baseGray = if (bizarreInvertMap) 255 - rawGray else rawGray
                val posX = x * charWidth + currentAnalogShift + bizarreWaveShift

                if (Random.nextFloat() < 0.13f) {
                    charBuf[0] = matrixChars[Random.nextInt(matrixLen)]
                    canvas.drawText(charBuf, 0, 1, posX, posY, paintText)
                }

                if (baseGray < 240) {
                    val noise = (Random.nextFloat() * 12f - 6f).toInt()
                    val dynamicGray = (baseGray + noise).coerceIn(0, 255)

                    var index = (dynamicGray * (rampLen - 1)) / 255
                    if (isBizarreIdleGlitchActive) {
                        index = (index + bizarreAsciiShift) % rampLen
                    }

                    val constantInterference = if (Random.nextFloat() < 0.25f) {
                        Random.nextFloat() * 0.4f + 0.5f
                    } else {
                        Random.nextFloat() * 0.15f + 0.85f
                    }
                    val fadeGlitchFactor = if (isModemSoundActive && Random.nextFloat() < 0.75f) 0.1f else 1.0f
                    val bizarreFadeFactor = if (bizarreInterferenceActive && Random.nextFloat() < 0.5f) 0.0f else 1.0f
                    val combinedVisibility = initialFadeFactor * constantInterference * fadeGlitchFactor * bizarreFadeFactor

                    if (Random.nextFloat() < combinedVisibility) {
                        val activePaint = if (isRowGlitched && useGlitchColor) {
                            paintGlitch.color = if (Random.nextBoolean()) Color.WHITE else Color.RED
                            paintGlitch
                        } else if (bizarreInterferenceActive) {
                            paintGlitch.color = if (Random.nextFloat() < 0.05f) 0xFF00FF41.toInt() else skullColor
                            paintGlitch
                        } else {
                            paintSkull
                        }
                        charBuf[0] = asciiRamp[index]
                        canvas.drawText(charBuf, 0, 1, posX, posY, activePaint)
                    }
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        setAnimationRunning(false)
    }
}
