package com.kewu.app

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.Foundation.NSURL
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS 录音（2026-10-26，首次接入真机 IPA）：AVAudioRecorder 直接写 16kHz / 16bit / 单声道 WAV，
 * 与 Android 端上传格式一致（服务端按 WAV 解析电平 / 信噪比）。MicrophoneManager 传进来的 .pcm 路径改成 .wav。
 */
@OptIn(ExperimentalForeignApi::class)
actual class AudioRecorder actual constructor(context: Any) {
    private var recorder: AVAudioRecorder? = null
    private var path: String? = null

    actual fun hasPermission(): Boolean =
        AVAudioSession.sharedInstance().recordPermission == AVAudioSessionRecordPermissionGranted

    actual suspend fun startRecording(outputFile: String): Boolean {
        if (recorder?.isRecording() == true) return false
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, error = null)
        session.setActive(true, error = null)
        val wav = outputFile.removeSuffix(".pcm") + ".wav"
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to kAudioFormatLinearPCM,
            AVSampleRateKey to 16000.0,
            AVNumberOfChannelsKey to 1,
            AVLinearPCMBitDepthKey to 16,
            AVLinearPCMIsFloatKey to false,
            AVLinearPCMIsBigEndianKey to false,
        )
        val url = NSURL.fileURLWithPath(wav)
        val r = AVAudioRecorder(uRL = url, settings = settings, error = null)
        if (!r.prepareToRecord() || !r.record()) return false
        recorder = r
        path = wav
        return true
    }

    actual suspend fun stopRecording(): String? {
        val r = recorder ?: return null
        r.stop()
        recorder = null
        return path
    }

    actual fun isRecording(): Boolean = recorder?.isRecording() == true

    actual fun getSampleRate(): Int = 16000
}

actual fun requestAudioPermission(activity: Any, onResult: (Boolean) -> Unit) {
    AVAudioSession.sharedInstance().requestRecordPermission { granted ->
        dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
    }
}
