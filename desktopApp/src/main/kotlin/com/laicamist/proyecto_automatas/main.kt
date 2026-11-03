package com.laicamist.proyecto_automatas

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import edu.itssg.compiler.data.db.DatabaseDriverFactory
import edu.itssg.compiler.ui.di.AppContainer
import kotlinx.coroutines.Dispatchers

fun main() {
    val container = AppContainer(
        databaseDriverFactory = DatabaseDriverFactory(),
        databaseDispatcher = Dispatchers.IO
    )

    application {
        Window(
            onCloseRequest = {
                container.close()
                exitApplication()
            },
            title = "J-Autómata Studio",
        ) {
            App(container)
        }
    }
}