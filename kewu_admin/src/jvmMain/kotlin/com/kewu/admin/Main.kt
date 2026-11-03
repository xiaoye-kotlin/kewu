package com.kewu.admin

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kewu.admin.ui.AdminApp
import com.kewu.admin.ui.KewuAdminTheme

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "课悟总控端 · Kewu Admin",
        state = rememberWindowState(
            size = DpSize(1440.dp, 900.dp),
            position = WindowPosition(Alignment.Center),
        ),
    ) {
        KewuAdminTheme {
            AdminApp()
        }
    }
}
