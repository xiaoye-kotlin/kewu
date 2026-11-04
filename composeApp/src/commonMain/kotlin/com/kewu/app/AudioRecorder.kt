package com.kewu.app

/**
 * 跨平台录音接口
 */
expect class AudioRecorder(context: Any) {
    /**
     * 检查是否有录音权限
     */
    fun hasPermission(): Boolean

    /**
     * 开始录音
     * @param outputFile 输出文件路径
     * @return 是否成功开始
     */
    suspend fun startRecording(outputFile: String): Boolean

    /**
     * 停止录音
     * @return 录音文件路径，失败返回null
     */
    suspend fun stopRecording(): String?

    /**
     * 是否正在录音
     */
    fun isRecording(): Boolean

    /**
     * 获取采样率
     */
    fun getSampleRate(): Int

    /**
     * 录音期间持续追加写入的原始音频文件（2026-11-04 分段上传按偏移读用）。
     * Android/Desktop = [outputFile] 本身（裸 PCM）；iOS = 实际写入的 .wav。
     */
    fun rawAudioPath(outputFile: String): String

    /**
     * 原始文件里音频数据的起始偏移：iOS WAV 头 44 字节，Android/Desktop 裸 PCM = 0。
     * 分段上传读 [rawAudioPath] 时 = 数据流偏移 + 本值。
     */
    fun rawDataOffset(): Int
}

/**
 * 请求录音权限（Android特定）
 */
expect fun requestAudioPermission(activity: Any, onResult: (Boolean) -> Unit)
