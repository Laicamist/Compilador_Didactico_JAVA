package edu.itssg.compiler.ui.navigation

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop no tiene botón Atrás del sistema: la navegación usa los botones "← Inicio" / "← Volver".
}
