package com.kewu.recserver.services

import kotlin.math.log10
import kotlin.math.sqrt

// 录音分析：解析 WAV/PCM 算平均电平（dBFS）和信噪比估计，m4a/aac/mp3 这类压缩格式服务端解不了，用客户端上报的值
class AudioAnalyzer {

    data class Result(
        val avgLevelDb: Double?,
        val snrDb: Double?,
        val durationSec: Double?
    )

    fun analyze(bytes: ByteArray, format: String?): Result {
        val wav = parseWav(bytes) ?: return Result(null, null, null)
        val rms = rmsOf(wav.samples)
        val avgLevelDb = if (rms > 0.0) (20 * log10(rms)).coerceIn(-120.0, 0.0) else -120.0
        return Result(avgLevelDb, estimateSnr(wav), wav.durationSec)
    }

    // 解析出来的 WAV：采样已归一化到 -1~1，多声道交错存放
    private class WavData(
        val sampleRate: Int,
        val channels: Int,
        val samples: FloatArray,
        val durationSec: Double
    )

    private fun parseWav(bytes: ByteArray): WavData? {
        if (bytes.size < 44) return null
        if (!matchTag(bytes, 0, "RIFF") || !matchTag(bytes, 8, "WAVE")) return null
        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var isFloat = false
        var pcm: ByteArray? = null
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = readLe32(bytes, pos + 4).toInt()
            val body = pos + 8
            if (chunkSize < 0 || body + chunkSize > bytes.size) break
            when (chunkId) {
                "fmt " -> {
                    val audioFormat = readLe16(bytes, body)
                    channels = readLe16(bytes, body + 2)
                    sampleRate = readLe32(bytes, body + 4).toInt()
                    bits = readLe16(bytes, body + 14)
                    isFloat = audioFormat == 3
                    if (audioFormat != 1 && audioFormat != 3) return null // 只认 PCM 和 IEEE float
                }
                "data" -> pcm = bytes.copyOfRange(body, body + chunkSize)
            }
            pos = body + chunkSize + (chunkSize % 2) // 每个 chunk 按 2 字节对齐
        }
        val data = pcm ?: return null
        if (channels <= 0 || sampleRate <= 0 || data.isEmpty()) return null
        val bytesPerSample = bits / 8
        if (bytesPerSample != 2 && bytesPerSample != 4) return null
        val totalSamples = data.size / bytesPerSample
        val frames = totalSamples / channels
        if (frames <= 0) return null
        val samples = FloatArray(totalSamples)
        for (i in 0 until totalSamples) {
            val off = i * bytesPerSample
            samples[i] = when {
                bytesPerSample == 4 && isFloat -> Float.fromBits(readLe32(data, off).toInt())
                bytesPerSample == 4 -> (readLe32(data, off).toInt() / 2147483648.0).toFloat()
                else -> (readLe16(data, off).toShort() / 32768.0).toFloat()
            }
        }
        return WavData(sampleRate, channels, samples, frames.toDouble() / sampleRate)
    }

    // 信噪比估计：按 20ms 分帧算能量，安静的底噪取 10 分位、有效语音取 90 分位，两者相减
    private fun estimateSnr(wav: WavData): Double? {
        val frameLen = wav.sampleRate / 50
        if (frameLen <= 0) return null
        val frames = wav.samples.size / frameLen
        if (frames < 8) return null
        val energies = DoubleArray(frames)
        for (f in 0 until frames) {
            var sum = 0.0
            for (i in 0 until frameLen) {
                val s = wav.samples[f * frameLen + i].toDouble()
                sum += s * s
            }
            energies[f] = sum / frameLen
        }
        energies.sort()
        val noise = energies[(frames * 0.1).toInt().coerceIn(0, frames - 1)]
        val signal = energies[(frames * 0.9).toInt().coerceIn(0, frames - 1)]
        if (noise <= 1e-12) return 60.0 // 底噪几乎是数字静音，直接给满
        return (10 * log10(signal / noise)).coerceIn(0.0, 60.0)
    }

    private fun rmsOf(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) {
            val d = s.toDouble()
            sum += d * d
        }
        return sqrt(sum / samples.size)
    }

    private fun readLe16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readLe32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun matchTag(b: ByteArray, off: Int, tag: String): Boolean {
        if (off + 4 > b.size) return false
        return (0..3).all { b[off + it].toInt().toChar().uppercaseChar() == tag[it].uppercaseChar() }
    }
}
