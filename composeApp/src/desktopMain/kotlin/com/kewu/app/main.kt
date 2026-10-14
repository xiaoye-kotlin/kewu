package com.kewu.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "课悟 · Kewu 学生端",
    ) {
        App()
    }
}
