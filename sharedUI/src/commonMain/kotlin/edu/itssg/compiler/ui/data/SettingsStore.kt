package edu.itssg.compiler.ui.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Tema de la interfaz: claro, oscuro (casi negro) o el que indique el sistema operativo. */
enum class ThemeMode(val displayName: String) {
    SYSTEM("Sistema"),
    LIGHT("Claro"),
    DARK("Oscuro")
}

/**
 * Preferencias del usuario (pantalla de Ajustes).
 *
 * @property editorFontSizeSp Tamaño de letra del editor; se limita a [MIN_EDITOR_FONT_SP]..[MAX_EDITOR_FONT_SP].
 * @property animationsEnabled `false` = movimiento reducido (sin el token viajero ni las animaciones de fase).
 * @property tourCompleted `true` cuando el usuario terminó u omitió el tour de bienvenida.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val editorFontSizeSp: Int = DEFAULT_EDITOR_FONT_SP,
    val animationsEnabled: Boolean = true,
    val tourCompleted: Boolean = false
) {
    companion object {
        const val DEFAULT_EDITOR_FONT_SP = 15
        const val MIN_EDITOR_FONT_SP = 11
        const val MAX_EDITOR_FONT_SP = 28
    }
}

/**
 * Almacén de preferencias. La interfaz permite cambiar después a un almacenamiento persistente
 * (por ejemplo, una tabla en SQLDelight) sin tocar los ViewModels.
 */
interface SettingsStore {
    val settings: StateFlow<AppSettings>
    fun update(transform: (AppSettings) -> AppSettings)
}

/** Implementación en memoria: las preferencias duran mientras la app está abierta. */
class InMemorySettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override fun update(transform: (AppSettings) -> AppSettings) {
        state.update { current ->
            val next = transform(current)
            next.copy(
                editorFontSizeSp = next.editorFontSizeSp.coerceIn(
                    AppSettings.MIN_EDITOR_FONT_SP,
                    AppSettings.MAX_EDITOR_FONT_SP
                )
            )
        }
    }
}