package edu.itssg.compiler.ui.di

import edu.itssg.compiler.core.JavaDidacticCompiler
import edu.itssg.compiler.core.security.CompilerSecurityConfig
import edu.itssg.compiler.data.db.AppSettingsRepository
import edu.itssg.compiler.data.db.CompilerDatabaseProvider
import edu.itssg.compiler.data.db.CompilerSessionRepository
import edu.itssg.compiler.data.db.DatabaseDriverFactory
import edu.itssg.compiler.ui.data.PersistentSettingsStore
import edu.itssg.compiler.ui.data.RepositorySessionStore
import edu.itssg.compiler.ui.data.SessionStore
import edu.itssg.compiler.ui.data.SettingsStore
import edu.itssg.compiler.ui.viewmodel.AppViewModel
import edu.itssg.compiler.ui.viewmodel.CompilerViewModel
import edu.itssg.compiler.ui.viewmodel.HistoryViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Dependencias de la app (inyección manual). Se crea UNA sola vez en el punto de entrada de cada plataforma
 * y se pasa a `App(container)`.
 *
 * El historial y los ajustes comparten una sola conexión a la base ([CompilerDatabaseProvider]).
 *
 * @param databaseDispatcher Despachador para la base de datos; desde Desktop y Android pasar `Dispatchers.IO`.
 * @param nowMillis Hora actual en milisegundos; se puede reemplazar en pruebas.
 */
class AppContainer(
    databaseDriverFactory: DatabaseDriverFactory,
    val securityConfig: CompilerSecurityConfig = CompilerSecurityConfig(),
    databaseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val nowMillis: () -> Long = ::currentEpochMillis
) {
    /** Un compilador nuevo por compilación (Parser y SemanticAnalyzer no son seguros para uso concurrente). */
    val compilerFactory: () -> JavaDidacticCompiler = { JavaDidacticCompiler(securityConfig) }

    /** Trabajo de fondo que vive lo mismo que la app (lectura y guardado de ajustes). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** La base se abre la primera vez que se usa, ya dentro de [databaseDispatcher]. */
    private val databaseProvider = CompilerDatabaseProvider(databaseDriverFactory)

    val sessionStore: SessionStore by lazy {
        RepositorySessionStore(CompilerSessionRepository(databaseProvider, databaseDispatcher))
    }

    val settingsStore: SettingsStore by lazy {
        PersistentSettingsStore(AppSettingsRepository(databaseProvider, databaseDispatcher), appScope)
    }

    fun createAppViewModel(): AppViewModel =
        AppViewModel(compilerFactory, settingsStore, securityConfig)

    fun createCompilerViewModel(): CompilerViewModel =
        CompilerViewModel(compilerFactory, sessionStore, securityConfig, nowMillis)

    fun createHistoryViewModel(): HistoryViewModel =
        HistoryViewModel(sessionStore)

    /**
     * Cierre ordenado (Desktop, al cerrar la ventana): primero espera, a lo más [timeoutMillis], a que se guarde el
     * último cambio de ajustes (antes se perdía si se cerraba justo después de cambiarlo) y luego [close].
     */
    suspend fun shutdown(timeoutMillis: Long = 1_500) {
        withTimeoutOrNull(timeoutMillis) { settingsStore.flush() }
        close()
    }

    /** Detiene el trabajo de fondo y cierra la base de datos si llegó a abrirse. */
    fun close() {
        appScope.cancel()
        databaseProvider.close()
    }
}

@OptIn(ExperimentalTime::class)
private fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
