package com.kewu.recserver.services

import com.kewu.recserver.model.QualityScore
import com.kewu.recserver.model.RecordingMeta

// 质量打分：采样率/时长/格式/体量/信噪比/电平六项，满分 1.0，分越高越适合当主音源
class QualityScorer {
    fun score(meta: RecordingMeta): QualityScore {
        var score = 0.0
        val factors = mutableListOf<String>()
        val sr = meta.sampleRate ?: 0
        when {
            sr >= 44100 -> { score += 0.15; factors += "采样率达标(>=44.1k)" }
            sr >= 16000 -> { score += 0.10; factors += "采样率可用(>=16k)" }
            else -> factors += "采样率偏低"
        }
        val dur = meta.durationSec ?: 0.0
        if (dur > 1.0) { score += 0.15; factors += "时长正常" } else factors += "时长过短"
        val fmt = (meta.format ?: "").lowercase()
        score += when (fmt) {
            in setOf("wav", "pcm", "flac") -> 0.15.also { factors += "格式友好($fmt)" }
            in setOf("m4a", "aac", "mp3") -> 0.10.also { factors += "格式可用($fmt)" }
            else -> 0.0.also { factors += "格式一般($fmt)" }
        }
        val size = meta.sizeBytes ?: 0
        if (size > 10_000) { score += 0.15; factors += "体量正常" } else factors += "体量偏小"
        val snr = meta.snrDb
        when {
            snr == null -> factors += "信噪比未知"
            snr >= 20 -> { score += 0.25; factors += "信噪比优秀(>=20dB)" }
            snr >= 10 -> { score += 0.15; factors += "信噪比良好(>=10dB)" }
            snr >= 5 -> { score += 0.05; factors += "信噪比一般(>=5dB)" }
            else -> factors += "信噪比差(<5dB)"
        }
        val level = meta.avgLevelDb
        when {
            level == null -> factors += "电平未知"
            level > -3 -> { score += 0.05; factors += "电平偏高(可能削波)" }
            level < -45 -> factors += "电平过低"
            else -> { score += 0.15; factors += "电平适中" }
        }
        return QualityScore(meta.recordingId, score.coerceIn(0.0, 1.0), factors)
    }
}
