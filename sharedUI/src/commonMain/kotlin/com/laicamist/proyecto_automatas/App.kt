package com.laicamist.proyecto_automatas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.itssg.compiler.ui.data.ThemeMode
import edu.itssg.compiler.ui.di.AppContainer
import edu.itssg.compiler.ui.files.SourceLoadResult
import edu.itssg.compiler.ui.files.rememberSourceFilePicker
import edu.itssg.compiler.ui.screens.ComingSoonScreen
import edu.itssg.compiler.ui.screens.InterimHomeScreen
import edu.itssg.compiler.ui.theme.JAutomataTheme
import edu.itssg.compiler.ui.viewmodel.AppScreen
import edu.itssg.compiler.ui.workspace.WorkspaceScreen
import kotlinx.coroutines.launch

/**
 * Raíz de la app. Crea los ViewModels (sobreviven a la rotación en Android) a partir del [container].
 *
 * Paso 3.2: tema J-Autómata, espacio de trabajo con el editor y un Inicio provisional. Historial y Ajustes
 * se muestran como pendientes hasta sus pasos.
 */
@Composable
fun App(container: AppContainer) {
    val appViewModel = viewModel { container.createAppViewModel() }
    val compilerViewModel = viewModel { container.createCompilerViewModel() }
    val historyViewModel = viewModel { container.createHistoryViewModel() }

    val appState by appViewModel.uiState.collectAsStateWithLifecycle()
    val workspaceState by compilerViewModel.uiState.collectAsStateWithLifecycle()
    val historyState by historyViewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun showMessage(text: String, isError: Boolean) {
        scope.launch {
            snackbarHostState.showSnackbar(
                message = text,
                withDismissAction = true,
                duration = if (isError) SnackbarDuration.Long else SnackbarDuration.Short
            )
        }
    }
    LaunchedEffect(compilerViewModel) {
        compilerViewModel.messages.collect { showMessage(it.text, it.isError) }
    }

    // Abrir desde el Inicio: carga el archivo en el espacio de trabajo y entra a él.
    val homePicker = rememberSourceFilePicker(container.securityConfig) { result ->
        when (result) {
            is SourceLoadResult.Success -> {
                compilerViewModel.openSource(title = result.title, source = result.text)
                appViewModel.navigateTo(AppScreen.WORKSPACE)
                result.notes.forEach { showMessage(it, false) }
            }
            is SourceLoadResult.Failure -> showMessage(result.message, true)
        }
    }
    /** Acción del Inicio que reemplaza un trabajo con cambios sin guardar (se confirma antes). */
    var pendingHomeAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun replaceWork(action: () -> Unit) {
        if (workspaceState.hasUnsavedChanges) pendingHomeAction = action else action()
    }

    // Mientras se leen los ajustes guardados se muestra solo el fondo, para no parpadear con el tema por defecto.
    val themeMode = if (appState.settingsLoaded) appState.settings.themeMode else ThemeMode.SYSTEM
    JAutomataTheme(themeMode) {
        val colors = JAutomataTheme.colors
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = colors.background
        ) { padding ->
            Box(Modifier.fillMaxSize().background(colors.background).padding(padding)) {
                if (!appState.settingsLoaded) return@Box
                when (appState.currentScreen) {
                    AppScreen.HOME -> {
                        val demo = appState.demo
                        InterimHomeScreen(
                            themeMode = appState.settings.themeMode,
                            onThemeModeChange = appViewModel::setThemeMode,
                            demoSummary = when {
                                demo.isLoading -> "Compilando el ejemplo del Inicio…"
                                demo.failure != null -> "El ejemplo no compiló: ${demo.failure}"
                                else -> "Ejemplo “${demo.focusStatement}”: ${demo.report?.tokens?.size ?: 0} tokens, " +
                                        "${demo.report?.intermediateCode?.quadruples?.size ?: 0} cuádruplos"
                            },
                            historySummary = when {
                                historyState.isLoading -> "Leyendo historial…"
                                else -> historyState.loadError ?: "Sesiones en el historial: ${historyState.allSessions.size}"
                            },
                            hasWorkInProgress = workspaceState.sourceCode.isNotBlank(),
                            workInProgressTitle = workspaceState.sessionTitle,
                            onNewWork = {
                                replaceWork {
                                    compilerViewModel.newWork()
                                    appViewModel.navigateTo(AppScreen.WORKSPACE)
                                }
                            },
                            onOpenFile = { replaceWork { homePicker.launch() } },
                            onContinueWork = { appViewModel.navigateTo(AppScreen.WORKSPACE) }
                        )
                    }

                    AppScreen.WORKSPACE -> WorkspaceScreen(
                        state = workspaceState,
                        viewModel = compilerViewModel,
                        settings = appState.settings,
                        securityConfig = container.securityConfig,
                        onFontSizeChange = appViewModel::setEditorFontSize,
                        onNavigateHome = { appViewModel.navigateTo(AppScreen.HOME) },
                        onMessage = ::showMessage
                    )

                    AppScreen.HISTORY -> ComingSoonScreen(
                        title = "Historial",
                        detail = "La pantalla del historial se implementa en un paso posterior de la Fase 3.",
                        onBack = { appViewModel.navigateBack() }
                    )

                    AppScreen.SETTINGS -> ComingSoonScreen(
                        title = "Ajustes",
                        detail = "La pantalla de ajustes se implementa en un paso posterior de la Fase 3.",
                        onBack = { appViewModel.navigateBack() }
                    )
                }
            }

            pendingHomeAction?.let { action ->
                AlertDialog(
                    onDismissRequest = { pendingHomeAction = null },
                    title = { Text("Cambios sin guardar") },
                    text = { Text("“${workspaceState.sessionTitle}” tiene cambios que no están en el historial. Si continúas, se perderán.") },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingHomeAction = null
                            action()
                        }) { Text("Descartar y continuar") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            pendingHomeAction = null
                            appViewModel.navigateTo(AppScreen.WORKSPACE)
                        }) { Text("Volver al trabajo") }
                    }
                )
            }
        }
    }
}
