package edu.itssg.compiler.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.ui.data.ThemeMode
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme

/**
 * Inicio provisional del Paso 3.2: da acceso al espacio de trabajo (nuevo, abrir archivo o continuar), al
 * Historial, a los Ajustes y al cambio de tema. La pantalla de Inicio completa de la especificación (fondo decorativo, línea de fases
 * animada, menú "¿Cómo funciona?" y tour) llega en un paso posterior.
 */
@Composable
fun InterimHomeScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    demoSummary: String,
    historySummary: String,
    hasWorkInProgress: Boolean,
    workInProgressTitle: String,
    onNewWork: () -> Unit,
    onOpenFile: () -> Unit,
    onContinueWork: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    Box(modifier.fillMaxSize().background(colors.background)) {
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onOpenHistory) { Text("Historial", fontSize = 13.sp, color = colors.textPrimary) }
            TextButton(onClick = onOpenSettings) { Text("Ajustes", fontSize = 13.sp, color = colors.textPrimary) }
            Text("·", fontSize = 12.sp, color = colors.textMuted)
            Text("Tema:", fontSize = 12.sp, color = colors.textMuted)
            ThemeMode.entries.forEach { mode ->
                TextButton(onClick = { onThemeModeChange(mode) }) {
                    Text(
                        mode.displayName,
                        fontSize = 12.sp,
                        fontWeight = if (mode == themeMode) FontWeight.Bold else FontWeight.Normal,
                        color = if (mode == themeMode) colors.accent else colors.textSecondary
                    )
                }
            }
        }
        Text(
            "Created by: Laicamist",
            fontSize = 12.sp,
            color = colors.textMuted,
            modifier = Modifier.align(Alignment.TopStart).padding(18.dp)
        )

        Column(
            modifier = Modifier.align(Alignment.Center).widthIn(max = 520.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "J-Autómata Studio",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = JAutomataFonts.title,
                color = colors.textPrimary
            )
            Text("Compilador didáctico · GLC v7", fontSize = 15.sp, color = colors.textSecondary)
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onNewWork,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Nuevo trabajo", fontSize = 15.sp) }
            OutlinedButton(onClick = onOpenFile, modifier = Modifier.fillMaxWidth()) { Text("Abrir archivo .java / .txt") }
            if (hasWorkInProgress) {
                TextButton(onClick = onContinueWork, modifier = Modifier.fillMaxWidth()) {
                    Text("Continuar con “$workInProgressTitle”", color = colors.textPrimary)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(demoSummary, fontSize = 12.sp, color = colors.textMuted, textAlign = TextAlign.Center)
            Text(historySummary, fontSize = 12.sp, color = colors.textMuted, textAlign = TextAlign.Center)
        }
    }
}
