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
}

/**
 * 请求录音权限（Android特定）
 */
expect fun requestAudioPermission(activity: Any, onResult: (Boolean) -> Unit)
