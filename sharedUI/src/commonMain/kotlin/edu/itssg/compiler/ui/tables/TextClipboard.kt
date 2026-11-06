package edu.itssg.compiler.ui.tables

import androidx.compose.runtime.Composable

/** Copia texto plano al portapapeles del sistema. */
fun interface TextClipboard {
    /** @return `false` si el sistema no permitió copiar. */
    fun copy(text: String): Boolean
}

/**
 * Portapapeles de cada plataforma (Desktop: AWT; Android: ClipboardManager). Se implementa por plataforma en lugar
 * de usar la API de portapapeles de Compose.
 */
@Composable
expect fun rememberTextClipboard(): TextClipboard
