package edu.itssg.compiler.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import edu.itssg.compiler.core.security.CompilerSecurityConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop: diálogo nativo del sistema ("Abrir"). Empieza en `Documentos/J-Autómata Studio` si existe (ahí se
 * exportará), si no en `Documentos`, y después recuerda la última carpeta usada.
 *
 * Importante: el diálogo es modal y, mientras está abierto, AWT procesa eventos en un ciclo anidado. Si se abre desde
 * una corrutina de Compose (`rememberCoroutineScope`), ese ciclo anidado corre *dentro* del despachador de Compose
 * (`FlushCoroutineDispatcher`) y Compose vuelve a entrar a él, lo que rompe la maquinaria de corrutinas
 * ("Fatal exception in coroutines machinery … ClassCastException: CompletedContinuation") y cierra la app.
 * Por eso el diálogo se abre como un evento AWT independiente ([EventQueue.invokeLater]), fuera de Compose; la lectura
 * del archivo va a [Dispatchers.IO] y solo el resultado vuelve a Compose.
 */
@Composable
actual fun rememberSourceFilePicker(
    securityConfig: CompilerSecurityConfig,
    onResult: (SourceLoadResult) -> Unit
): SourceFilePicker {
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    val currentConfig by rememberUpdatedState(securityConfig)
    // Evita abrir dos diálogos si se presiona el botón (o Ctrl+O) varias veces seguidas.
    val isOpen = remember { AtomicBoolean(false) }
    return remember(scope) {
        SourceFilePicker {
            if (!isOpen.compareAndSet(false, true)) return@SourceFilePicker
            EventQueue.invokeLater {
                val file = try {
                    chooseFile()
                } catch (e: Exception) {
                    isOpen.set(false)
                    scope.launch {
                        currentOnResult(
                            SourceLoadResult.Failure("No se pudo abrir el selector de archivos: ${e.message ?: e::class.simpleName}")
                        )
                    }
                    return@invokeLater
                }
                isOpen.set(false)
                if (file == null) return@invokeLater // El usuario canceló.
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            readSourceFile(file, currentConfig)
                        } catch (e: Exception) {
                            SourceLoadResult.Failure("No se pudo leer el archivo: ${e.message ?: e::class.simpleName}")
                        }
                    }
                    currentOnResult(result)
                }
            }
        }
    }
}

@Volatile
private var lastDirectory: String? = null

/** Se ejecuta en el hilo de AWT, fuera de Compose. Devuelve `null` si el usuario cancela. */
private fun chooseFile(): File? {
    val dialog = FileDialog(null as Frame?, "Abrir código fuente (.java / .txt)", FileDialog.LOAD)
    try {
        dialog.isMultipleMode = false
        dialog.directory = lastDirectory ?: defaultDirectory()
        // macOS y Linux respetan el filtro; en Windows se usa el patrón del nombre.
        dialog.setFilenameFilter { _, name -> SourceFileLoader.validateName(name) == null }
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            dialog.file = "*.java;*.txt"
        }
        dialog.isVisible = true

        val name = dialog.file ?: return null
        val directory = dialog.directory ?: return null
        lastDirectory = directory
        return File(directory, name)
    } finally {
        dialog.dispose()
    }
}

private fun defaultDirectory(): String {
    val home = System.getProperty("user.home").orEmpty()
    val documents = listOf("Documents", "Documentos")
        .map { File(home, it) }
        .firstOrNull { it.isDirectory }
        ?: return home
    val appFolder = File(documents, "J-Autómata Studio")
    return if (appFolder.isDirectory) appFolder.absolutePath else documents.absolutePath
}

private fun readSourceFile(file: File, config: CompilerSecurityConfig): SourceLoadResult {
    SourceFileLoader.validateName(file.name)?.let { return SourceLoadResult.Failure(it) }
    if (!file.isFile) return SourceLoadResult.Failure("No se encontró el archivo “${file.name}”.")
    val size = file.length()
    SourceFileLoader.validateSize(size, config)?.let { return SourceLoadResult.Failure(it) }

    val maxBytes = SourceFileLoader.maxFileBytes(config)
    val bytes = try {
        // Nunca se leen más de maxBytes + 1 bytes, aunque el archivo crezca mientras se abre.
        file.inputStream().use { it.readNBytes(maxBytes + 1) }
    } catch (e: IOException) {
        return SourceLoadResult.Failure("No se pudo leer el archivo: ${e.message ?: e::class.simpleName}")
    } catch (e: SecurityException) {
        return SourceLoadResult.Failure("No hay permiso para leer “${file.name}”.")
    }
    return SourceFileLoader.load(PickedSourceFile(file.name, bytes, size), config)
}
