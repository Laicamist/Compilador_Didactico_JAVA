package edu.itssg.compiler.ui.tables

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Desktop: portapapeles del sistema mediante AWT. */
@Composable
actual fun rememberTextClipboard(): TextClipboard = remember {
    TextClipboard { text ->
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            true
        } catch (e: Exception) {
            // Portapapeles ocupado por otra aplicación o entorno sin interfaz gráfica.
            false
        }
    }
}
