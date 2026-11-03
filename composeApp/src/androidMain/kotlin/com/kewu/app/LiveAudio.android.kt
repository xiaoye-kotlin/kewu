package com.kewu.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * [LiveAudio] 的 Android 实现（2026-11-03 实时语音识别）：AudioRecord 采 PCM16 / 16kHz / 单声道，
 * 每 100ms 一块回调给实时识别，同时写成 WAV（先写 44 字节占位头，停止时回填长度）供语音气泡回放。
 * 只占一路麦克风（Android 不允许两路同时录，所以不能再另开 AudioRecorder）。权限由调用方先申请。
 */
internal object LiveAudioAndroid {
    private const val RATE = 16_000
    private const val CHUNK = RATE * 2 / 10 // 100ms

    @Volatile private var running = false
    @Volatile private var lastLevel = 0f
    private var worker: Thread? = null
    private var path: String? = null

    fun register() {
        LiveAudio.start = { wav, onChunk -> start(wav, onChunk) }
        LiveAudio.stop = { stop() }
        LiveAudio.level = { lastLevel }
    }

    @SuppressLint("MissingPermission")
    private fun start(wav: String, onChunk: (ByteArray) -> Unit): Boolean {
        if (running) return false
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, CHUNK * 2))
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        val file = File(wav).apply { parentFile?.mkdirs() }
        val out = runCatching { RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) } }.getOrNull()
            ?: run { rec.release(); return false }
        runCatching { rec.startRecording() }.onFailure { rec.release(); out.close(); return false }
        running = true
        path = file.absolutePath
        worker = thread(name = "kewu-live-audio") {
            val buf = ByteArray(CHUNK)
            var total = 0L
            try {
                while (running) {
                    var n = 0
                    while (n < CHUNK && running) {
                        val r = rec.read(buf, n, CHUNK - n)
                        if (r <= 0) break
                        n += r
                    }
                    if (n <= 0) continue
                    val chunk = buf.copyOf(n)
                    out.write(chunk)
                    total += n
                    lastLevel = level(chunk)
                    runCatching { onChunk(chunk) }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                runCatching { writeHeader(out, total) }
                runCatching { out.close() }
                lastLevel = 0f
            }
        }
        return true
    }

    private fun stop(): String? {
        if (!running) return null
        running = false
        worker?.join(1_500)
        worker = null
        return path?.takeIf { File(it).length() > 44 }
    }

    private fun level(pcm: ByteArray): Float {
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val v = abs((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8))
            if (v > peak) peak = v
            i += 8
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }

    private fun writeHeader(f: RandomAccessFile, dataLen: Long) {
        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        f.seek(0)
        f.write("RIFF".toByteArray()); f.write(le32(36 + dataLen)); f.write("WAVE".toByteArray())
        f.write("fmt ".toByteArray()); f.write(le32(16)); f.write(le16(1)); f.write(le16(1))
        f.write(le32(RATE.toLong())); f.write(le32(RATE * 2L)); f.write(le16(2)); f.write(le16(16))
        f.write("data".toByteArray()); f.write(le32(dataLen))
    }
}
