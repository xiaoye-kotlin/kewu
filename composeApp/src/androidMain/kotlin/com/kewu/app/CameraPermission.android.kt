package com.kewu.app

import androidx.activity.ComponentActivity

actual fun requestCameraPermission(activity: Any, onResult: (Boolean) -> Unit) {
    if (activity is ComponentActivity && activity is MainActivity) {
        activity.requestCameraPermission(onResult)
    } else {
        onResult(false)
    }
}
