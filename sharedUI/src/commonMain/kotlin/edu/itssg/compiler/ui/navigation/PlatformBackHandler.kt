package edu.itssg.compiler.ui.navigation

import androidx.compose.runtime.Composable

/**
 * Botón Atrás del sistema. En Android intercepta el gesto/botón Atrás mientras [enabled] es `true` (sin él, Atrás
 * cerraba la app desde Historial o Ajustes); en Desktop no hay botón Atrás y no hace nada.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
