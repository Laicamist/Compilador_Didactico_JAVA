package edu.itssg.compiler.ui.export

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** Resultado de guardar una exportación. */
sealed interface ExportSaveResult {
    /** @property location Carpeta donde quedaron los archivos, en palabras del usuario. */
    data class Saved(val location: String, val fileCount: Int) : ExportSaveResult
    data object Cancelled : ExportSaveResult
    data class Failed(val message: String) : ExportSaveResult
}

/** Guarda los archivos de una exportación donde cada plataforma los hace fáciles de encontrar. */
fun interface ExportSaver {
    fun save(bundle: ExportBundle, onResult: (ExportSaveResult) -> Unit)
}

/**
 * Guardado por plataforma (decisión del 31 oct 2026):
 * - **Desktop:** el usuario elige la carpeta (empieza en `Documentos/J-Autómata Studio`); se crea una subcarpeta por
 *   exportación y al terminar se abre en el explorador.
 * - **Android:** `Descargas/J-Autómata Studio/<carpeta>` (MediaStore, sin permisos); en Android 9 o anterior, la
 *   carpeta de descargas de la app.
 */
@Composable
expect fun rememberExportSaver(): ExportSaver

/** Codifica la imagen como PNG (cada plataforma usa su codificador nativo). */
internal expect fun encodePng(image: ImageBitmap): ByteArray?
