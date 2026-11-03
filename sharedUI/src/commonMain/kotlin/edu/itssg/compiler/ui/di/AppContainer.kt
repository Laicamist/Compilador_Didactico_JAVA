package edu.itssg.compiler.ui.di

import edu.itssg.compiler.core.JavaDidacticCompiler
import edu.itssg.compiler.core.security.CompilerSecurityConfig
import edu.itssg.compiler.data.db.CompilerSessionRepository
import edu.itssg.compiler.data.db.DatabaseDriverFactory
import edu.itssg.compiler.ui.data.InMemorySettingsStore
import edu.itssg.compiler.ui.data.RepositorySessionStore
import edu.itssg.compiler.ui.data.SessionStore
import edu.itssg.compiler.ui.data.SettingsStore
import edu.itssg.compiler.ui.viewmodel.AppViewModel
import edu.itssg.compiler.ui.viewmodel.CompilerViewModel
import edu.itssg.compiler.ui.viewmodel.HistoryViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Dependencias de la app (inyección manual). Se crea UNA sola vez en el punto de entrada de cada plataforma
 * y se pasa a `App(container)`.
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
    /** Un compilador nuevo por compilación*/
    val compilerFactory: () -> JavaDidacticCompiler = { JavaDidacticCompiler(securityConfig) }

    /** La base se abre la primera vez que se usa, ya dentro de [databaseDispatcher]. */
    private val repository = lazy { CompilerSessionRepository(databaseDriverFactory, databaseDispatcher) }

    val sessionStore: SessionStore by lazy { RepositorySessionStore(repository.value) }

    val settingsStore: SettingsStore by lazy { InMemorySettingsStore() }

    fun createAppViewModel(): AppViewModel =
        AppViewModel(compilerFactory, settingsStore, securityConfig)

    fun createCompilerViewModel(): CompilerViewModel =
        CompilerViewModel(compilerFactory, sessionStore, securityConfig, nowMillis)

    fun createHistoryViewModel(): HistoryViewModel =
        HistoryViewModel(sessionStore)

    /** Cierra la base de datos si llegó a abrirse (Desktop, al cerrar la ventana). */
    fun close() {
        if (repository.isInitialized()) repository.value.close()
    }
}

@OptIn(ExperimentalTime::class)
private fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
