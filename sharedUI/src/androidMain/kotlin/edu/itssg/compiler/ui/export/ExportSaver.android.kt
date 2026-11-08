package edu.itssg.compiler.ui.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Android: `Descargas/J-Autómata Studio/<carpeta>` con MediaStore (Android 10+, sin permisos de almacenamiento).
 * En Android 7–9 se usa la carpeta de descargas propia de la app (tampoco pide permisos) y se indica la ruta.
 */
@Composable
actual fun rememberExportSaver(): ExportSaver {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        ExportSaver { bundle, onResult ->
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveWithMediaStore(context, bundle)
                        else saveInAppDownloads(context, bundle)
                    } catch (e: Exception) {
                        ExportSaveResult.Failed("No se pudo guardar: ${e.message ?: e::class.simpleName}")
                    }
                }
                onResult(result)
            }
        }
    }
}

internal actual fun encodePng(image: ImageBitmap): ByteArray? {
    val out = ByteArrayOutputStream()
    return if (image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
}

private const val APP_FOLDER = "J-Autómata Studio"

@RequiresApi(Build.VERSION_CODES.Q)
private fun saveWithMediaStore(context: Context, bundle: ExportBundle): ExportSaveResult {
    val resolver = context.contentResolver
    val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$APP_FOLDER/${bundle.folderName}"
    for (file in bundle.files) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, file.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return ExportSaveResult.Failed("No se pudo crear ${file.name} en Descargas.")
        try {
            resolver.openOutputStream(uri)?.use { it.write(file.bytes) }
                ?: return ExportSaveResult.Failed("No se pudo escribir ${file.name}.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
    return ExportSaveResult.Saved("Descargas/$APP_FOLDER/${bundle.folderName}", bundle.files.size)
}

private fun saveInAppDownloads(context: Context, bundle: ExportBundle): ExportSaveResult {
    val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    val folder = File(File(base, APP_FOLDER), bundle.folderName)
    if (!folder.mkdirs() && !folder.isDirectory) return ExportSaveResult.Failed("No se pudo crear ${folder.absolutePath}")
    for (file in bundle.files) File(folder, file.name).writeBytes(file.bytes)
    return ExportSaveResult.Saved(folder.absolutePath, bundle.files.size)
}

internal actual fun exportLocationHint(): String =
    "Se guardan en Descargas/J-Autómata Studio, en una carpeta con el nombre y la fecha (ábrela con la app Archivos)."
