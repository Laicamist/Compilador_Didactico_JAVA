package com.laicamist.proyecto_automatas

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import edu.itssg.compiler.data.db.DatabaseDriverFactory
import edu.itssg.compiler.ui.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

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
            // Ícono de la ventana y de la barra de tareas al correr desde IntelliJ (run).
            icon = appIconPainter(),
        ) {
            App(container)
        }
    }
}
