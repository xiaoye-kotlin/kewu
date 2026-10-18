package com.kewu.app

import androidx.activity.ComponentActivity

actual fun requestAudioPermission(activity: Any, onResult: (Boolean) -> Unit) {
    if (activity is ComponentActivity && activity is MainActivity) {
        activity.requestAudioPermission(onResult)
    } else {
        onResult(false)
    }
}
