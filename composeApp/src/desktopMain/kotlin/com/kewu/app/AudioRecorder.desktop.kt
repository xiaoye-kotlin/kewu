package com.kewu.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Desktop 端录音实现（JDK 自带 Java Sound API，无需额外依赖）。
 * 与 Android 端保持同一契约：startRecording 接收 .pcm 路径，stopRecording 返回同名的 .wav 路径。
 */
actual class AudioRecorder actual constructor(context: Any) {
    private var line: TargetDataLine? = null
    private var recording = false
    private var recordingFile: File? = null

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
    }

    actual fun hasPermission(): Boolean = true

    actual suspend fun startRecording(outputFile: String): Boolean = withContext(Dispatchers.IO) {
        if (recording) return@withContext false
        try {
            val format = AudioFormat(SAMPLE_RATE.toFloat(), BITS_PER_SAMPLE, CHANNELS, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) return@withContext false
            val targetLine = AudioSystem.getLine(info) as TargetDataLine
            targetLine.open(format)
            recordingFile = File(outputFile).also { it.parentFile?.mkdirs() }
            targetLine.start()
            line = targetLine
            recording = true
            CoroutineScope(Dispatchers.IO).launch { writeAudioData() }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun writeAudioData() {
        val bufferSize = line?.bufferSize?.takeIf { it > 0 } ?: 4096
        val data = ByteArray(bufferSize)
        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(recordingFile)
            while (recording) {
                val read = line?.read(data, 0, bufferSize) ?: -1
                if (read > 0) fos.write(data, 0, read)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            try {
                fos?.close()
            } catch (_: IOException) {
            }
        }
    }

    actual suspend fun stopRecording(): String? = withContext(Dispatchers.IO) {
        if (!recording) return@withContext null
        try {
            recording = false
            line?.stop()
            line?.close()
            line = null
            val file = recordingFile
            recordingFile = null
            file?.let { raw ->
                val wav = File(raw.parent, raw.nameWithoutExtension + ".wav")
                if (convertPcmToWav(raw, wav)) {
                    raw.delete()
                    wav.absolutePath
                } else {
                    raw.absolutePath
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun convertPcmToWav(pcmFile: File, wavFile: File): Boolean {
        return try {
            val pcmData = pcmFile.readBytes()
            val wavData = ByteArray(44 + pcmData.size)
            val channels = CHANNELS
            val bitsPerSample = BITS_PER_SAMPLE
            val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8

            "RIFF".toByteArray().copyInto(wavData, 0)
            intToBytes(36 + pcmData.size).copyInto(wavData, 4)
            "WAVE".toByteArray().copyInto(wavData, 8)
            "fmt ".toByteArray().copyInto(wavData, 12)
            intToBytes(16).copyInto(wavData, 16)
            shortToBytes(1).copyInto(wavData, 20)
            shortToBytes(channels.toShort()).copyInto(wavData, 22)
            intToBytes(SAMPLE_RATE).copyInto(wavData, 24)
            intToBytes(byteRate).copyInto(wavData, 28)
            shortToBytes((channels * bitsPerSample / 8).toShort()).copyInto(wavData, 32)
            shortToBytes(bitsPerSample.toShort()).copyInto(wavData, 34)
            "data".toByteArray().copyInto(wavData, 36)
            intToBytes(pcmData.size).copyInto(wavData, 40)
            pcmData.copyInto(wavData, 44)

            wavFile.writeBytes(wavData)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToBytes(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )

    actual fun isRecording(): Boolean = recording

    actual fun getSampleRate(): Int = SAMPLE_RATE
}
