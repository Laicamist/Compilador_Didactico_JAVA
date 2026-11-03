package edu.itssg.compiler.ui.data

import edu.itssg.compiler.data.db.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    /** Copia con los valores dentro de sus rangos válidos. */
    fun normalized(): AppSettings =
        copy(editorFontSizeSp = editorFontSizeSp.coerceIn(MIN_EDITOR_FONT_SP, MAX_EDITOR_FONT_SP))

    companion object {
        const val DEFAULT_EDITOR_FONT_SP = 15
        const val MIN_EDITOR_FONT_SP = 11
        const val MAX_EDITOR_FONT_SP = 28
    }
}

/** Almacén de preferencias; los ViewModels no saben si se guardan en memoria o en la base de datos. */
interface SettingsStore {
    val settings: StateFlow<AppSettings>

    /** `true` cuando ya se leyeron los ajustes guardados (antes de eso [settings] tiene los valores por defecto). */
    val isLoaded: StateFlow<Boolean>

    fun update(transform: (AppSettings) -> AppSettings)
}

/** Implementación en memoria: las preferencias duran mientras la app está abierta (útil en pruebas). */
class InMemorySettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    private val state = MutableStateFlow(initial.normalized())
    override val settings: StateFlow<AppSettings> = state.asStateFlow()
    override val isLoaded: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

    override fun update(transform: (AppSettings) -> AppSettings) {
        state.update { transform(it).normalized() }
    }
}

/**
 * Ajustes guardados en la tabla `AppSetting` de SQLDelight.
 *
 * Un único coroutine en [scope] procesa todo en orden: primero lee lo guardado y después aplica cada [update]
 * y lo escribe en la base. Así un cambio hecho mientras todavía se lee la base no se pierde, y las escrituras
 * nunca se cruzan. Si la base falla, la app sigue funcionando con los ajustes en memoria.
 */
class PersistentSettingsStore(
    private val repository: AppSettingsRepository,
    scope: CoroutineScope
) : SettingsStore {

    private val state = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    private val loaded = MutableStateFlow(false)
    override val isLoaded: StateFlow<Boolean> = loaded.asStateFlow()

    private val updates = Channel<(AppSettings) -> AppSettings>(Channel.UNLIMITED)

    init {
        scope.launch {
            state.value = try {
                AppSettingsCodec.decode(repository.loadAll())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppSettings()
            }
            loaded.value = true

            for (transform in updates) {
                val next = transform(state.value).normalized()
                if (next == state.value) continue
                state.value = next
                try {
                    repository.saveAll(AppSettingsCodec.encode(next))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Se conserva el cambio en memoria; se volverá a intentar guardar con el siguiente cambio.
                }
            }
        }
    }

    override fun update(transform: (AppSettings) -> AppSettings) {
        updates.trySend(transform)
    }
}

/**
 * Conversión entre [AppSettings] y los pares clave/valor de la tabla `AppSetting`.
 * Un valor ausente o inválido (por ejemplo, de una versión futura) usa el valor por defecto de ese ajuste.
 */
internal object AppSettingsCodec {
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_EDITOR_FONT_SIZE = "editor_font_size_sp"
    const val KEY_ANIMATIONS_ENABLED = "animations_enabled"
    const val KEY_TOUR_COMPLETED = "tour_completed"

    fun encode(settings: AppSettings): Map<String, String> = mapOf(
        KEY_THEME_MODE to settings.themeMode.name,
        KEY_EDITOR_FONT_SIZE to settings.editorFontSizeSp.toString(),
        KEY_ANIMATIONS_ENABLED to settings.animationsEnabled.toString(),
        KEY_TOUR_COMPLETED to settings.tourCompleted.toString()
    )

    fun decode(values: Map<String, String>): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = values[KEY_THEME_MODE]
                ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                ?: defaults.themeMode,
            editorFontSizeSp = values[KEY_EDITOR_FONT_SIZE]?.toIntOrNull() ?: defaults.editorFontSizeSp,
            animationsEnabled = values[KEY_ANIMATIONS_ENABLED]?.toBooleanStrictOrNull() ?: defaults.animationsEnabled,
            tourCompleted = values[KEY_TOUR_COMPLETED]?.toBooleanStrictOrNull() ?: defaults.tourCompleted
        ).normalized()
    }
}
