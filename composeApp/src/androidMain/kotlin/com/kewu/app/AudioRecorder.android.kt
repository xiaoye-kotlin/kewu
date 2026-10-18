package com.kewu.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

actual class AudioRecorder actual constructor(context: Any) {
    private val androidContext: Context = context as Context
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingFile: File? = null

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
    }

    actual fun hasPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            androidContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    actual suspend fun startRecording(outputFile: String): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext false
        if (isRecording) return@withContext false

        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR

            if (bufferSize <= 0) return@withContext false

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            recordingFile = File(outputFile)
            recordingFile?.parentFile?.mkdirs()

            audioRecord?.startRecording()
            isRecording = true

            // 后台写入音频数据
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                writeAudioDataToFile(bufferSize)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun writeAudioDataToFile(bufferSize: Int) {
        val data = ByteArray(bufferSize)
        var fos: FileOutputStream? = null

        try {
            fos = FileOutputStream(recordingFile)

            while (isRecording) {
                val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (read > 0) {
                    fos.write(data, 0, read)
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            try {
                fos?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    actual suspend fun stopRecording(): String? = withContext(Dispatchers.IO) {
        if (!isRecording) return@withContext null

        try {
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            val file = recordingFile
            recordingFile = null

            // 转换为WAV格式（添加WAV头）
            file?.let { rawFile ->
                val wavFile = File(rawFile.parent, rawFile.nameWithoutExtension + ".wav")
                if (convertPcmToWav(rawFile, wavFile)) {
                    rawFile.delete()
                    wavFile.absolutePath
                } else {
                    rawFile.absolutePath
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

            // WAV文件头
            val channels = 1
            val bitsPerSample = 16
            val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8

            // RIFF header
            "RIFF".toByteArray().copyInto(wavData, 0)
            intToBytes(36 + pcmData.size).copyInto(wavData, 4)
            "WAVE".toByteArray().copyInto(wavData, 8)

            // fmt chunk
            "fmt ".toByteArray().copyInto(wavData, 12)
            intToBytes(16).copyInto(wavData, 16) // fmt chunk size
            shortToBytes(1).copyInto(wavData, 20) // PCM format
            shortToBytes(channels.toShort()).copyInto(wavData, 22)
            intToBytes(SAMPLE_RATE).copyInto(wavData, 24)
            intToBytes(byteRate).copyInto(wavData, 28)
            shortToBytes((channels * bitsPerSample / 8).toShort()).copyInto(wavData, 32)
            shortToBytes(bitsPerSample.toShort()).copyInto(wavData, 34)

            // data chunk
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

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToBytes(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
    }

    actual fun isRecording(): Boolean = isRecording

    actual fun getSampleRate(): Int = SAMPLE_RATE
}
