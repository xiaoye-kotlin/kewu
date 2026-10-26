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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Android 录音：AudioRecord 16bit 单声道 44.1kHz → PCM 落盘 → 停止时转 WAV。
 *
 * 2026-10-26 稳定性修复：
 *   - 写线程是可 join 的 [writer] Job：停止时先等它把最后一个缓冲写完再转 WAV（旧版不等，结尾会被截断）；
 *   - AudioRecord 构造 / 启动失败（麦克风被占等）时立即 release，不泄漏；状态字段 @Volatile；
 *   - PCM→WAV 流式拷贝（旧版 readBytes + 再拷一份，45 分钟约 238MB × 2 → 中低端机 OOM）；
 *   - 录音期间启动麦克风前台服务 [RecordingService]：Android 9+ 后台读麦克风只能拿到静音，
 *     旧版课堂录音一锁屏 / 切后台，后面全是静音。
 */
actual class AudioRecorder actual constructor(context: Any) {
    private val androidContext: Context = context as Context
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var recordingFile: File? = null
    private var writer: Job? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // 2026-10-26：44100 → 16000。华为云 SIS 课堂转写用 chinese_16k_media 模型，要求 16kHz；文件也小 64%，上传更快
        private const val SAMPLE_RATE = 16000
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

        var record: AudioRecord? = null
        try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
            if (bufferSize <= 0) return@withContext false

            record = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return@withContext false
            }
            val file = File(outputFile).also { it.parentFile?.mkdirs() }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                return@withContext false
            }
            audioRecord = record
            recordingFile = file
            isRecording = true
            RecordingService.start(androidContext)
            writer = ioScope.launch { writeAudioDataToFile(record, file, bufferSize) }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            runCatching { record?.release() }
            audioRecord = null
            isRecording = false
            false
        }
    }

    private fun writeAudioDataToFile(record: AudioRecord, file: File, bufferSize: Int) {
        val data = ByteArray(bufferSize)
        try {
            BufferedOutputStream(FileOutputStream(file)).use { out ->
                while (isRecording) {
                    val read = record.read(data, 0, bufferSize)
                    if (read > 0) out.write(data, 0, read)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual suspend fun stopRecording(): String? = withContext(Dispatchers.IO) {
        if (!isRecording) return@withContext null
        try {
            isRecording = false
            writer?.join() // 等写线程把最后一个缓冲写完、关掉文件
            writer = null
            audioRecord?.let { r -> runCatching { r.stop() }; r.release() }
            audioRecord = null

            val file = recordingFile
            recordingFile = null
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
        } finally {
            RecordingService.stop(androidContext)
        }
    }

    /** 流式 PCM → WAV：先写 44 字节头（长度由 PCM 文件大小算出），再分块拷贝数据，内存占用恒定。 */
    private fun convertPcmToWav(pcmFile: File, wavFile: File): Boolean {
        return try {
            val pcmSize = pcmFile.length()
            if (pcmSize > Int.MAX_VALUE - 44) return false
            val channels = 1
            val bitsPerSample = 16
            val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
            val header = ByteArray(44)
            "RIFF".toByteArray().copyInto(header, 0)
            intToBytes(36 + pcmSize.toInt()).copyInto(header, 4)
            "WAVE".toByteArray().copyInto(header, 8)
            "fmt ".toByteArray().copyInto(header, 12)
            intToBytes(16).copyInto(header, 16)
            shortToBytes(1).copyInto(header, 20)
            shortToBytes(channels.toShort()).copyInto(header, 22)
            intToBytes(SAMPLE_RATE).copyInto(header, 24)
            intToBytes(byteRate).copyInto(header, 28)
            shortToBytes((channels * bitsPerSample / 8).toShort()).copyInto(header, 32)
            shortToBytes(bitsPerSample.toShort()).copyInto(header, 34)
            "data".toByteArray().copyInto(header, 36)
            intToBytes(pcmSize.toInt()).copyInto(header, 40)

            BufferedOutputStream(FileOutputStream(wavFile)).use { out ->
                out.write(header)
                BufferedInputStream(FileInputStream(pcmFile)).use { it.copyTo(out, 64 * 1024) }
            }
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
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun shortToBytes(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte(),
    )

    actual fun isRecording(): Boolean = isRecording

    actual fun getSampleRate(): Int = SAMPLE_RATE
}
