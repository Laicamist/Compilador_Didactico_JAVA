package edu.itssg.compiler.ui.tables

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Android: ClipboardManager del sistema (no requiere permisos para escribir). */
@Composable
actual fun rememberTextClipboard(): TextClipboard {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        TextClipboard { text ->
            try {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@TextClipboard false
                manager.setPrimaryClip(ClipData.newPlainText("J-Autómata Studio", text))
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
