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
import edu.itssg.compiler.ui.export.ExportDialog
import edu.itssg.compiler.ui.export.ExportTarget
import edu.itssg.compiler.ui.files.SourceLoadResult
import edu.itssg.compiler.ui.files.rememberSourceFilePicker
import edu.itssg.compiler.ui.history.HistoryScreen
import edu.itssg.compiler.ui.home.HomeScreen
import edu.itssg.compiler.ui.navigation.PlatformBackHandler
import edu.itssg.compiler.ui.state.ResultTab
import edu.itssg.compiler.ui.state.TreeKind
import edu.itssg.compiler.ui.viewmodel.AppViewModel
import edu.itssg.compiler.ui.viewmodel.HelpTopic
import edu.itssg.compiler.ui.settings.SettingsScreen
import edu.itssg.compiler.ui.theme.JAutomataTheme
import edu.itssg.compiler.ui.viewmodel.AppScreen
import edu.itssg.compiler.ui.workspace.WorkspaceScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Raíz de la app. Crea los ViewModels (sobreviven a la rotación en Android) a partir del [container].
 *
 * Pantallas: Inicio (con las hojas "¿Cómo funciona?" y el tour), espacio de trabajo, Historial y Ajustes.
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
    LaunchedEffect(historyViewModel) {
        historyViewModel.messages.collect { showMessage(it.text, it.isError) }
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

    /** Compilación que se está exportando (diálogo Exportar), desde el espacio de trabajo o el Historial. */
    var exportTarget by remember { mutableStateOf<ExportTarget?>(null) }
    fun exportSession(title: String, source: String) {
        scope.launch {
            try {
                exportTarget = ExportTarget(title, container.compilerFactory().compileAsync(source))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("No se pudo compilar “$title” para exportarlo: ${e.message ?: e::class.simpleName}", true)
            }
        }
    }
    fun replaceWork(action: () -> Unit) {
        if (workspaceState.hasUnsavedChanges) pendingHomeAction = action else action()
    }

    // Android: Atrás cierra la ayuda o el tour, o regresa a la pantalla anterior; en Inicio lo atiende el sistema.
    PlatformBackHandler(enabled = appState.handlesSystemBack) { appViewModel.onSystemBack() }

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
                    AppScreen.HOME -> HomeScreen(
                        themeMode = appState.settings.themeMode,
                        animationsEnabled = appState.settings.animationsEnabled,
                        demo = appState.demo,
                        openHelpTopic = appState.openHelpTopic,
                        tourStep = appState.tourStep,
                        historySummary = when {
                            historyState.isLoading && historyState.allSessions.isEmpty() -> "Leyendo historial…"
                            historyState.loadError != null -> historyState.loadError ?: ""
                            historyState.allSessions.isEmpty() -> "Aún no hay sesiones guardadas."
                            historyState.allSessions.size == 1 -> "1 sesión en el historial"
                            else -> "${historyState.allSessions.size} sesiones en el historial"
                        },
                        hasWorkInProgress = workspaceState.sourceCode.isNotBlank(),
                        workInProgressTitle = workspaceState.sessionTitle,
                        onThemeModeChange = appViewModel::setThemeMode,
                        onNewWork = {
                            replaceWork {
                                compilerViewModel.newWork()
                                appViewModel.navigateTo(AppScreen.WORKSPACE)
                            }
                        },
                        onOpenFile = { replaceWork { homePicker.launch() } },
                        onContinueWork = { appViewModel.navigateTo(AppScreen.WORKSPACE) },
                        onOpenHistory = { appViewModel.navigateTo(AppScreen.HISTORY) },
                        onOpenSettings = { appViewModel.navigateTo(AppScreen.SETTINGS) },
                        onOpenHelp = appViewModel::openHelp,
                        onCloseHelp = appViewModel::closeHelp,
                        onPreviousHelp = appViewModel::previousHelpTopic,
                        onNextHelp = appViewModel::nextHelpTopic,
                        onSeeExample = { content ->
                            // Abre el ejemplo del Inicio en el espacio de trabajo, en el panel del tema.
                            replaceWork {
                                compilerViewModel.openSource(title = "Demo.java", source = AppViewModel.DEMO_SOURCE)
                                compilerViewModel.onTabSelected(content.exampleTab)
                                if (content.exampleTab == ResultTab.TREE) {
                                    compilerViewModel.onTreeKindSelected(
                                        if (content.topic == HelpTopic.TREE) TreeKind.SEMANTIC else TreeKind.SYNTACTIC
                                    )
                                }
                                appViewModel.navigateTo(AppScreen.WORKSPACE)
                            }
                        },
                        onTourNext = appViewModel::nextTourStep,
                        onTourSkip = appViewModel::finishTour
                    )

                    AppScreen.WORKSPACE -> WorkspaceScreen(
                        state = workspaceState,
                        viewModel = compilerViewModel,
                        settings = appState.settings,
                        securityConfig = container.securityConfig,
                        onFontSizeChange = appViewModel::setEditorFontSize,
                        onNavigateHome = { appViewModel.navigateTo(AppScreen.HOME) },
                        onMessage = ::showMessage,
                        onExport = {
                            workspaceState.report?.let { report ->
                                exportTarget = ExportTarget(
                                    title = workspaceState.sessionTitle,
                                    report = report,
                                    notice = if (workspaceState.isReportStale) {
                                        "El código cambió desde la última compilación: se exporta la versión compilada."
                                    } else null
                                )
                            }
                        }
                    )

                    AppScreen.HISTORY -> HistoryScreen(
                        state = historyState,
                        nowMillis = container.nowMillis(),
                        onBack = { appViewModel.navigateBack() },
                        onQueryChange = historyViewModel::onQueryChange,
                        onFilterChange = historyViewModel::onFilterChange,
                        onSortChange = historyViewModel::onSortChange,
                        onRetry = historyViewModel::refresh,
                        onOpen = { session ->
                            replaceWork {
                                compilerViewModel.openSource(
                                    title = session.title,
                                    source = session.sourceCode,
                                    alreadySaved = true
                                )
                                appViewModel.navigateTo(AppScreen.WORKSPACE)
                            }
                        },
                        onExport = { session -> exportSession(session.title, session.sourceCode) },
                        onRequestDelete = historyViewModel::requestDeletion,
                        onConfirmDelete = historyViewModel::confirmDeletion,
                        onCancelDelete = historyViewModel::cancelDeletion
                    )

                    AppScreen.SETTINGS -> SettingsScreen(
                        settings = appState.settings,
                        securityConfig = container.securityConfig,
                        historyCount = if (historyState.isLoading && historyState.allSessions.isEmpty()) null
                        else historyState.allSessions.size,
                        onBack = { appViewModel.navigateBack() },
                        onThemeModeChange = appViewModel::setThemeMode,
                        onEditorFontSizeChange = appViewModel::setEditorFontSize,
                        onAnimationsEnabledChange = appViewModel::setAnimationsEnabled,
                        onClearHistory = historyViewModel::clearAll,
                        onRestartTour = appViewModel::restartTour
                    )
                }
            }

            exportTarget?.let { target ->
                ExportDialog(
                    target = target,
                    nowMillis = container.nowMillis,
                    onDismiss = { exportTarget = null },
                    onMessage = ::showMessage
                )
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
