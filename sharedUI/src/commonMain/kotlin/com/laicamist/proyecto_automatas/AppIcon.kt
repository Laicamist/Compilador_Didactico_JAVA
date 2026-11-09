package com.laicamist.proyecto_automatas

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.painterResource
import proyecto_automatas.sharedui.generated.resources.JAutomataStudioIcon
import proyecto_automatas.sharedui.generated.resources.Res

/**
 * Ícono de J-Autómata Studio (`composeResources/drawable/JAutomataStudioIcon.png`).
 *
 * `Res` de sharedUI es interno al módulo, así que desktopApp lo pide por aquí para la ventana y la barra de tareas.
 */
@Composable
fun appIconPainter(): Painter = painterResource(Res.drawable.JAutomataStudioIcon)
