package com.laicamist.proyecto_automatas

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Proyecto_Automatas",
    ) {
        App()
    }
}