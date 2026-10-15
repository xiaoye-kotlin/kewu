package com.kewu.server.services

import com.kewu.server.model.QualityScore
import com.kewu.server.model.RecordingMeta

// 质量打分：现在按采样率/时长/格式/体量给个综合分，真实清晰度以后接音频分析再补
class QualityScorer {
    fun score(meta: RecordingMeta): QualityScore {
        var score = 0.0
        val factors = mutableListOf<String>()
        val sr = meta.sampleRate ?: 0
        when {
            sr >= 44100 -> { score += 0.35; factors += "采样率达标(>=44.1k)" }
            sr >= 16000 -> { score += 0.20; factors += "采样率可用(>=16k)" }
            else -> factors += "采样率偏低"
        }
        val dur = meta.durationSec ?: 0.0
        if (dur > 1.0) { score += 0.25; factors += "时长正常" } else factors += "时长过短"
        val fmt = (meta.format ?: "").lowercase()
        score += when (fmt) {
            in setOf("wav", "pcm", "flac") -> 0.20.also { factors += "格式友好($fmt)" }
            in setOf("m4a", "aac", "mp3") -> 0.12.also { factors += "格式可用($fmt)" }
            else -> 0.0.also { factors += "格式一般($fmt)" }
        }
        val size = meta.sizeBytes ?: 0
        if (size > 10_000) score += 0.20 else factors += "体量偏小"
        return QualityScore(meta.recordingId, score.coerceIn(0.0, 1.0), factors)
    }
}
