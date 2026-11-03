package com.laicamist.proyecto_automatas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.itssg.compiler.ui.di.AppContainer

/**
 * Raíz de la app. Crea los ViewModels (sobreviven a la rotación en Android) a partir del [container].
 *
 * Paso 3.1: solo conecta el estado. Las pantallas Inicio, Espacio de trabajo, Historial y Ajustes llegan en el
 * Paso 3.2; mientras tanto se muestra un resumen que confirma que el compilador y la base de datos funcionan.
 */
@Composable
fun App(container: AppContainer) {
    val appViewModel = viewModel { container.createAppViewModel() }
    val compilerViewModel = viewModel { container.createCompilerViewModel() }
    val historyViewModel = viewModel { container.createHistoryViewModel() }

    val appState by appViewModel.uiState.collectAsStateWithLifecycle()
    val workspaceState by compilerViewModel.uiState.collectAsStateWithLifecycle()
    val historyState by historyViewModel.uiState.collectAsStateWithLifecycle()

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val demo = appState.demo
                Text("J-Autómata Studio", style = MaterialTheme.typography.headlineMedium)
                Text("Pantalla actual: ${appState.currentScreen}")
                Text(
                    when {
                        demo.isLoading -> "Compilando el ejemplo del Inicio…"
                        demo.failure != null -> "El ejemplo no compiló: ${demo.failure}"
                        else -> "Ejemplo compilado: ${demo.report?.tokens?.size ?: 0} tokens, " +
                                "${demo.report?.intermediateCode?.quadruples?.size ?: 0} cuádruplos"
                    }
                )
                Text(
                    if (historyState.isLoading) "Leyendo historial…"
                    else historyState.loadError ?: "Sesiones en el historial: ${historyState.allSessions.size}"
                )
                Text("Espacio de trabajo: ${workspaceState.sessionTitle}")
            }
        }
    }
}