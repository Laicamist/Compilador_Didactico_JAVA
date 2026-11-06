package edu.itssg.compiler.ui.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import edu.itssg.compiler.core.security.CompilerSecurityConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Android: selector de documentos del sistema (Storage Access Framework). No requiere permisos de
 * almacenamiento: el sistema da acceso solo al archivo elegido.
 */
@Composable
actual fun rememberSourceFilePicker(
    securityConfig: CompilerSecurityConfig,
    onResult: (SourceLoadResult) -> Unit
): SourceFilePicker {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    val currentConfig by rememberUpdatedState(securityConfig)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { readSourceUri(context, uri, currentConfig) }
                currentOnResult(result)
            }
        }
    }

    return remember(launcher) {
        SourceFilePicker {
            try {
                launcher.launch(SourceFileLoader.PICKER_MIME_TYPES)
            } catch (e: ActivityNotFoundException) {
                currentOnResult(SourceLoadResult.Failure("Este dispositivo no tiene un selector de archivos disponible."))
            }
        }
    }
}

private fun readSourceUri(context: Context, uri: Uri, config: CompilerSecurityConfig): SourceLoadResult {
    return try {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }

        val fileName = name ?: uri.lastPathSegment.orEmpty()
        SourceFileLoader.validateName(fileName)?.let { return SourceLoadResult.Failure(it) }
        size?.let { declared -> SourceFileLoader.validateSize(declared, config)?.let { return SourceLoadResult.Failure(it) } }

        val maxBytes = SourceFileLoader.maxFileBytes(config)
        val bytes = resolver.openInputStream(uri)?.use { readAtMost(it, maxBytes + 1) }
            ?: return SourceLoadResult.Failure("No se pudo abrir “$fileName”.")
        SourceFileLoader.load(PickedSourceFile(fileName, bytes, size), config)
    } catch (e: SecurityException) {
        SourceLoadResult.Failure("No hay permiso para leer el archivo elegido.")
    } catch (e: Exception) {
        SourceLoadResult.Failure("No se pudo leer el archivo: ${e.message ?: e::class.simpleName}")
    }
}

/** Lee como máximo [limit] bytes (`InputStream.readNBytes` solo existe desde Android 13). */
private fun readAtMost(input: InputStream, limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var total = 0
    while (total < limit) {
        val read = input.read(buffer, total, limit - total)
        if (read < 0) break
        total += read
    }
    return buffer.copyOf(total)
}
