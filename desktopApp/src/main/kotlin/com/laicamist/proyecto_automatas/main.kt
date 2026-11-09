package com.laicamist.proyecto_automatas

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import edu.itssg.compiler.data.db.DatabaseDriverFactory
import edu.itssg.compiler.ui.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.awt.Dimension

fun main() {
    val container = AppContainer(
        databaseDriverFactory = DatabaseDriverFactory(),
        databaseDispatcher = Dispatchers.IO
    )

    application {
        Window(
            onCloseRequest = {
                // Espera (máx. 1.5 s) a que se guarde el último cambio de ajustes antes de cerrar la base.
                runBlocking { container.shutdown() }
                exitApplication()
            },
            title = "J-Autómata Studio",
            state = rememberWindowState(width = 1280.dp, height = 800.dp, position = WindowPosition.PlatformDefault),
            // Ícono de la ventana y de la barra de tareas al correr desde IntelliJ (run).
            icon = appIconPainter(),
        ) {
            // Debajo de este tamaño la barra superior y la de fases ya no caben y el espacio de trabajo se vuelve
            // difícil de usar (el diseño compacto es para teléfonos, no para ventanas de escritorio diminutas).
            LaunchedEffect(Unit) { window.minimumSize = Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT) }
            App(container)
        }
    }
}

/** Tamaño mínimo de la ventana, en píxeles. */
private const val MIN_WINDOW_WIDTH = 960
private const val MIN_WINDOW_HEIGHT = 620
