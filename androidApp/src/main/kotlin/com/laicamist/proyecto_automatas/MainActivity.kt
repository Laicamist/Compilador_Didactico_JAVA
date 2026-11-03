package com.laicamist.proyecto_automatas

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import edu.itssg.compiler.data.db.DatabaseDriverFactory
import edu.itssg.compiler.ui.di.AppContainer
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = AppContainerHolder.get(applicationContext)
        setContent {
            App(container)
        }
    }
}

/**
 * Un solo [AppContainer] por proceso: la Activity se recrea al rotar la pantalla, pero la base de datos
 * y los ViewModels deben seguir siendo los mismos.
 */
private object AppContainerHolder {
    @Volatile
    private var instance: AppContainer? = null

    fun get(context: Context): AppContainer =
        instance ?: synchronized(this) {
            instance ?: AppContainer(
                databaseDriverFactory = DatabaseDriverFactory(context.applicationContext),
                databaseDispatcher = Dispatchers.IO
            ).also { instance = it }
        }
}