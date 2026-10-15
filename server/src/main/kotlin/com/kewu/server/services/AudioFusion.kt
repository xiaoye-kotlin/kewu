package com.kewu.server.services

import com.kewu.server.model.FusionResult
import com.kewu.server.model.RecordingMeta

// 多端录音择优融合：先挑分最高的当主干，其余标成补充音源，真拼接以后接 ffmpeg/WebRTC
class AudioFusion {
    fun fuse(sessionId: String, scored: List<Pair<RecordingMeta, Double>>): FusionResult {
        val best = scored.maxByOrNull { it.second }
        return FusionResult(
            sessionId = sessionId,
            primaryRecordingId = best?.first?.recordingId,
            mergedSources = scored.map { it.first.recordingId },
            strategy = if (scored.size > 1) "择优+多源标记" else "单源直出"
        )
    }
}
