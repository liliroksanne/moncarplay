package com.lilyroksanne.mon

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import mobi.cangol.mobile.utils.LameUtils
import java.io.File

internal object Mp3Conversion {
    private const val MAX_PCM_BYTES = 220L * 1024 * 1024

    fun writeMp3(context: Context, source: File, destination: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("El archivo no contiene audio.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
            val plainMp3 = source.inputStream().use { input ->
                val first = input.read()
                val second = input.read()
                val third = input.read()
                (first == 73 && second == 68 && third == 51) ||
                    (first == 255 && second >= 0 && second and 0xe6 == 0xe2)
            }
            if (mime == MediaFormat.MIMETYPE_AUDIO_MPEG && plainMp3) {
                source.copyTo(destination, overwrite = true)
                return
            }
            val pcm = File.createTempFile("mon-audio-", ".pcm", context.cacheDir)
            try {
                val decoded = decode(extractor, format, mime, pcm)
                val channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                require(channels in 1..2) { "La pista necesita audio mono o estéreo." }
                val encoded = synchronized(this) {
                    LameUtils(channels, rate, 128).raw2mp3(pcm.path, destination.path)
                }
                check(encoded && destination.length() > 0) { "No pude convertir el audio a MP3." }
            } finally {
                pcm.delete()
            }
        } finally {
            extractor.release()
        }
    }

    private fun decode(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        mime: String,
        pcm: File
    ): MediaFormat {
        val decoder = MediaCodec.createDecoderByType(mime)
        try {
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var format = inputFormat
            var bytesWritten = 0L
            var lastProgress = android.os.SystemClock.elapsedRealtime()
            pcm.outputStream().buffered().use { output ->
                val buffer = ByteArray(16 * 1024)
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = checkNotNull(decoder.getInputBuffer(inputIndex))
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                            lastProgress = android.os.SystemClock.elapsedRealtime()
                        }
                    }
                    val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        format = decoder.outputFormat
                        require(!format.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT
                        ) { "El formato de audio decodificado no es compatible." }
                    } else if (outputIndex >= 0) {
                        if (info.size > 0) {
                            bytesWritten += info.size
                            require(bytesWritten <= MAX_PCM_BYTES) { "La pista es demasiado larga." }
                            val samples = checkNotNull(decoder.getOutputBuffer(outputIndex))
                            samples.position(info.offset)
                            samples.limit(info.offset + info.size)
                            while (samples.hasRemaining()) {
                                val count = minOf(samples.remaining(), buffer.size)
                                samples.get(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                        lastProgress = android.os.SystemClock.elapsedRealtime()
                    }
                    check(android.os.SystemClock.elapsedRealtime() - lastProgress < 15_000) {
                        "El lector de audio dejó de responder."
                    }
                }
            }
            require(bytesWritten > 0) { "El archivo no contiene muestras de audio." }
            return format
        } finally {
            decoder.release()
        }
    }
}

