package edu.itssg.compiler.ui.export

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * Desktop: diálogo para elegir la carpeta (empieza en `Documentos/J-Autómata Studio`, que se crea si no existe),
 * subcarpeta por exportación y, al terminar, se abre en el explorador de archivos.
 *
 * Igual que el selector de "Abrir", el diálogo modal se abre como evento AWT independiente ([EventQueue.invokeLater])
 * y no desde una corrutina de Compose (ver `SourceFilePicker.jvm.kt`); la escritura va a [Dispatchers.IO].
 */
@Composable
actual fun rememberExportSaver(): ExportSaver {
    val scope = rememberCoroutineScope()
    val isOpen = remember { AtomicBoolean(false) }
    return remember(scope) {
        ExportSaver { bundle, onResult ->
            if (!isOpen.compareAndSet(false, true)) return@ExportSaver
            EventQueue.invokeLater {
                val parent = try {
                    chooseExportDirectory()
                } catch (e: Exception) {
                    isOpen.set(false)
                    scope.launch { onResult(ExportSaveResult.Failed("No se pudo abrir el selector: ${e.message ?: e::class.simpleName}")) }
                    return@invokeLater
                }
                isOpen.set(false)
                if (parent == null) {
                    scope.launch { onResult(ExportSaveResult.Cancelled) }
                    return@invokeLater
                }
                scope.launch {
                    val result = withContext(Dispatchers.IO) { writeBundle(parent, bundle) }
                    onResult(result)
                }
            }
        }
    }
}

internal actual fun encodePng(image: ImageBitmap): ByteArray? =
    Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes

@Volatile
private var lastExportDirectory: String? = null

private fun chooseExportDirectory(): File? {
    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (e: Exception) {
        // Aspecto por defecto de Swing.
    }
    val start = File(lastExportDirectory ?: defaultExportDirectory().absolutePath)
    val chooser = JFileChooser(if (start.isDirectory) start else null).apply {
        dialogTitle = "Elegir carpeta para exportar"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isMultiSelectionEnabled = false
        approveButtonText = "Exportar aquí"
        isAcceptAllFileFilterUsed = false
    }
    val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    if (chooser.showDialog(owner, "Exportar aquí") != JFileChooser.APPROVE_OPTION) return null
    // Si el usuario entra a una carpeta sin seleccionar nada, selectedFile es null: se usa la carpeta actual.
    val dir = chooser.selectedFile ?: chooser.currentDirectory ?: return null
    lastExportDirectory = dir.absolutePath
    return dir
}

/** `Documentos/J-Autómata Studio` (la crea si hace falta); si no hay Documentos, la carpeta del usuario. */
private fun defaultExportDirectory(): File {
    val home = File(System.getProperty("user.home").orEmpty())
    val documents = listOf("Documents", "Documentos").map { File(home, it) }.firstOrNull { it.isDirectory } ?: return home
    val appFolder = File(documents, "J-Autómata Studio")
    if (!appFolder.isDirectory) appFolder.mkdirs()
    return if (appFolder.isDirectory) appFolder else documents
}

private fun writeBundle(parent: File, bundle: ExportBundle): ExportSaveResult = try {
    val folder = uniqueFolder(parent, bundle.folderName)
    if (!folder.mkdirs() && !folder.isDirectory) {
        ExportSaveResult.Failed("No se pudo crear la carpeta ${folder.absolutePath}")
    } else {
        for (file in bundle.files) File(folder, file.name).writeBytes(file.bytes)
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(folder)
            }
        } catch (e: Exception) {
            // Abrir la carpeta es una ayuda; si falla, los archivos ya están guardados.
        }
        ExportSaveResult.Saved(folder.absolutePath, bundle.files.size)
    }
} catch (e: SecurityException) {
    ExportSaveResult.Failed("No hay permiso para escribir en ${parent.absolutePath}")
} catch (e: Exception) {
    ExportSaveResult.Failed("No se pudo guardar: ${e.message ?: e::class.simpleName}")
}

/** `Uno_2026-11-07_1826`, o `…(2)` si ya existe (dos exportaciones en el mismo minuto). */
private fun uniqueFolder(parent: File, name: String): File {
    var candidate = File(parent, name)
    var n = 2
    while (candidate.exists()) {
        candidate = File(parent, "$name ($n)")
        n++
    }
    return candidate
}

internal actual fun exportLocationHint(): String =
    "Elegirás la carpeta (empieza en Documentos/J-Autómata Studio); se crea una subcarpeta con el nombre y la fecha y al terminar se abre."
