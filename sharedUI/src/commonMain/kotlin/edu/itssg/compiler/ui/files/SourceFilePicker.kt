package edu.itssg.compiler.ui.files

import androidx.compose.runtime.Composable
import edu.itssg.compiler.core.security.CompilerSecurityConfig

/** Abre el selector de archivos del sistema. */
fun interface SourceFilePicker {
    fun launch()
}

/**
 * Selector de archivos de código de cada plataforma (Desktop: diálogo nativo; Android: Storage Access Framework).
 *
 * La plataforma solo elige y lee los bytes, con el tope de [SourceFileLoader.maxFileBytes]; la validación y
 * decodificación son comunes ([SourceFileLoader.load]) y el resultado llega en [onResult].
 * Si el usuario cancela, no se llama a [onResult].
 */
@Composable
expect fun rememberSourceFilePicker(
    securityConfig: CompilerSecurityConfig,
    onResult: (SourceLoadResult) -> Unit
): SourceFilePicker
