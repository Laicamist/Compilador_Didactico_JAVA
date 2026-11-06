package edu.itssg.compiler.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.JavaDidacticCompiler
import edu.itssg.compiler.core.security.CompilerSecurityConfig
import edu.itssg.compiler.ui.data.AppSettings
import edu.itssg.compiler.ui.data.SettingsStore
import edu.itssg.compiler.ui.data.ThemeMode
import edu.itssg.compiler.ui.state.CompilerPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pantallas de la app. */
enum class AppScreen { HOME, WORKSPACE, HISTORY, SETTINGS }

/** Elementos del menú inferior "¿Cómo funciona?" (cada uno abre su hoja deslizante). */
enum class HelpTopic(val displayName: String, val phase: CompilerPhase?) {
    LEXICAL("Léxico", CompilerPhase.LEXICAL),
    SYNTACTIC("Sintáctico", CompilerPhase.SYNTACTIC),
    SEMANTIC("Semántico", CompilerPhase.SEMANTIC),
    TABLES("Tablas", null),
    TREE("Árbol", null),
    INTERMEDIATE_CODE("Código intermedio", CompilerPhase.INTERMEDIATE_CODE),
    OPTIMIZATION("Optimización", CompilerPhase.OPTIMIZATION)
}

/** Globos del tour de bienvenida, en orden. */
enum class TourStep(val title: String) {
    PHASE_LINE("Así trabaja un compilador"),
    NEW_WORK("Empieza un nuevo trabajo"),
    HELP_MENU("¿Cómo funciona cada proceso?"),
    HISTORY("Tu historial")
}

/**
 * Ejemplo real que recorren las animaciones del Inicio y las hojas de ayuda.
 * [report] es la salida verdadera de [JavaDidacticCompiler] para [source].
 */
data class HomeDemoState(
    val source: String = AppViewModel.DEMO_SOURCE,
    val focusStatement: String = AppViewModel.DEMO_STATEMENT,
    val report: CompilationReport? = null,
    val isLoading: Boolean = true,
    val failure: String? = null
)

/**
 * @property settingsLoaded `false` mientras se leen los ajustes guardados; la UI puede esperar a que sea `true`
 *   para no mostrar un instante el tema por defecto.
 */
data class AppUiState(
    val backStack: List<AppScreen> = listOf(AppScreen.HOME),
    val settings: AppSettings = AppSettings(),
    val settingsLoaded: Boolean = false,
    val openHelpTopic: HelpTopic? = null,
    val tourStep: TourStep? = null,
    val demo: HomeDemoState = HomeDemoState(),
    val securityConfig: CompilerSecurityConfig = CompilerSecurityConfig()
) {
    val currentScreen: AppScreen get() = backStack.last()
    val canNavigateBack: Boolean get() = backStack.size > 1
}

/**
 * ViewModel de nivel app: navegación, ajustes, hojas de ayuda del Inicio, tour de bienvenida y el ejemplo
 * compilado que alimenta las animaciones.
 */
class AppViewModel(
    private val compilerFactory: () -> JavaDidacticCompiler,
    private val settingsStore: SettingsStore,
    securityConfig: CompilerSecurityConfig
) : ViewModel() {

    private val state = MutableStateFlow(
        AppUiState(
            settings = settingsStore.settings.value,
            settingsLoaded = settingsStore.isLoaded.value,
            securityConfig = securityConfig
        )
    )
    val uiState: StateFlow<AppUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings -> state.update { it.copy(settings = settings) } }
        }
        viewModelScope.launch {
            // El tour se decide con los ajustes ya leídos de la base, no con los valores por defecto.
            settingsStore.isLoaded.first { it }
            val showTour = !settingsStore.settings.value.tourCompleted
            state.update {
                it.copy(
                    settingsLoaded = true,
                    tourStep = if (showTour) it.tourStep ?: TourStep.entries.first() else it.tourStep
                )
            }
        }
        viewModelScope.launch { compileDemo() }
    }

    // Navegación

    /** Ir a [screen]. Volver a una pantalla que ya está en la pila regresa a ella; Inicio limpia la pila. */
    fun navigateTo(screen: AppScreen) {
        state.update { current ->
            val stack = current.backStack
            val newStack = when {
                screen == AppScreen.HOME -> listOf(AppScreen.HOME)
                stack.last() == screen -> stack
                screen in stack -> stack.subList(0, stack.indexOf(screen) + 1)
                else -> stack + screen
            }
            current.copy(backStack = newStack, openHelpTopic = null)
        }
    }

    /** @return `false` si ya está en Inicio (en Android, el sistema cierra la app). */
    fun navigateBack(): Boolean {
        if (!state.value.canNavigateBack) return false
        state.update { it.copy(backStack = it.backStack.dropLast(1)) }
        return true
    }

    // ------------------------------------------------------------------ Hojas de ayuda

    fun openHelp(topic: HelpTopic) = state.update { it.copy(openHelpTopic = topic) }

    fun closeHelp() = state.update { it.copy(openHelpTopic = null) }

    fun nextHelpTopic() = shiftHelpTopic(+1)

    fun previousHelpTopic() = shiftHelpTopic(-1)

    private fun shiftHelpTopic(delta: Int) {
        state.update { current ->
            val topic = current.openHelpTopic ?: return@update current
            val all = HelpTopic.entries
            current.copy(openHelpTopic = all[(topic.ordinal + delta + all.size) % all.size])
        }
    }

    // ------------------------------------------------------------------ Tour de bienvenida

    fun nextTourStep() {
        val step = state.value.tourStep ?: return
        val next = TourStep.entries.getOrNull(step.ordinal + 1)
        if (next == null) finishTour() else state.update { it.copy(tourStep = next) }
    }

    /** Terminar u omitir el tour. */
    fun finishTour() {
        state.update { it.copy(tourStep = null) }
        settingsStore.update { it.copy(tourCompleted = true) }
    }

    /** Ajustes › Repetir el tour. */
    fun restartTour() {
        settingsStore.update { it.copy(tourCompleted = false) }
        state.update { it.copy(backStack = listOf(AppScreen.HOME), tourStep = TourStep.entries.first()) }
    }

    // ------------------------------------------------------------------ Ajustes

    fun setThemeMode(mode: ThemeMode) = settingsStore.update { it.copy(themeMode = mode) }

    fun setAnimationsEnabled(enabled: Boolean) = settingsStore.update { it.copy(animationsEnabled = enabled) }

    fun setEditorFontSize(sp: Int) = settingsStore.update { it.copy(editorFontSizeSp = sp) }

    fun increaseEditorFontSize() = settingsStore.update { it.copy(editorFontSizeSp = it.editorFontSizeSp + 1) }

    fun decreaseEditorFontSize() = settingsStore.update { it.copy(editorFontSizeSp = it.editorFontSizeSp - 1) }

    // ------------------------------------------------------------------ Ejemplo del Inicio

    fun retryDemo() {
        if (state.value.demo.isLoading) return
        viewModelScope.launch { compileDemo() }
    }

    private suspend fun compileDemo() {
        state.update { it.copy(demo = it.demo.copy(isLoading = true, failure = null)) }
        try {
            val report = compilerFactory().compileAsync(DEMO_SOURCE)
            state.update { it.copy(demo = it.demo.copy(report = report, isLoading = false)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update {
                it.copy(demo = it.demo.copy(isLoading = false, failure = e.message ?: e::class.simpleName))
            }
        }
    }

    companion object {
        /** Instrucción que destacan las animaciones del Inicio. */
        const val DEMO_STATEMENT: String = "int x = 5 + 3;"

        /** Programa completo (GLC v7) que contiene [DEMO_STATEMENT]; se compila al abrir la app. */
        val DEMO_SOURCE: String = """
            public class Demo{
                public static void main(String []args){
                    int x = 5 + 3;
                    System.out.println(x);
                }
            }
        """.trimIndent()
    }
}