package edu.itssg.compiler.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.core.security.CompilerSecurityConfig
import edu.itssg.compiler.ui.data.AppSettings
import edu.itssg.compiler.ui.data.ThemeMode
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme
import kotlin.math.roundToInt

/**
 * Pantalla de Ajustes: apariencia (tema, letra del editor, animaciones), límites de seguridad del motor (solo
 * lectura), datos (borrar historial, repetir el tour) y "Acerca de". Cada cambio se guarda al momento en la tabla `AppSetting`.
 *
 * @param historyCount Sesiones guardadas (para el texto y para habilitar "Borrar historial"); `null` mientras se lee.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    securityConfig: CompilerSecurityConfig,
    historyCount: Int?,
    onBack: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onEditorFontSizeChange: (Int) -> Unit,
    onAnimationsEnabledChange: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onRestartTour: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(colors.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surface1).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Volver", color = colors.textSecondary) }
            Text(
                "Ajustes",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = JAutomataFonts.title,
                color = colors.textPrimary
            )
        }
        HorizontalDivider(color = colors.border)

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Section("Apariencia") {
                    SettingRow(title = "Tema", detail = "Sistema sigue el modo claro u oscuro del dispositivo.") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                Choice(mode.displayName, selected = mode == settings.themeMode) { onThemeModeChange(mode) }
                            }
                        }
                    }
                    RowDivider()
                    FontSizeRow(settings.editorFontSizeSp, onEditorFontSizeChange)
                    RowDivider()
                    SettingRow(
                        title = "Animaciones",
                        detail = "Apagadas: el recorrido de los árboles y la cámara saltan sin transición (movimiento reducido)."
                    ) {
                        Switch(
                            checked = settings.animationsEnabled,
                            onCheckedChange = onAnimationsEnabledChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.onAccent,
                                checkedTrackColor = colors.accent,
                                uncheckedThumbColor = colors.textSecondary,
                                uncheckedTrackColor = colors.surface3,
                                uncheckedBorderColor = colors.border
                            )
                        )
                    }
                }

                Section("Límites de seguridad", subtitle = "Protegen al compilador de entradas enormes o mal formadas. Solo lectura.") {
                    securityLimitRows(securityConfig).forEachIndexed { index, row ->
                        if (index > 0) RowDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(row.label, fontSize = 14.sp, color = colors.textPrimary)
                                Text(row.detail, fontSize = 12.sp, color = colors.textMuted)
                            }
                            Text(
                                row.value,
                                fontSize = 13.sp,
                                fontFamily = JAutomataFonts.code,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textSecondary
                            )
                        }
                    }
                }

                Section("Datos") {
                    SettingRow(
                        title = "Borrar historial",
                        detail = when (historyCount) {
                            null -> "Leyendo historial…"
                            0 -> "No hay sesiones guardadas."
                            1 -> "Se eliminará 1 sesión guardada. Los ajustes no se tocan."
                            else -> "Se eliminarán $historyCount sesiones guardadas. Los ajustes no se tocan."
                        }
                    ) {
                        OutlinedButton(
                            onClick = { confirmClear = true },
                            enabled = (historyCount ?: 0) > 0
                        ) { Text("Borrar", color = if ((historyCount ?: 0) > 0) colors.errorLexical else colors.textMuted) }
                    }
                    RowDivider()
                    SettingRow(
                        title = "Repetir el tour",
                        detail = "Vuelve al Inicio y muestra otra vez los globos de bienvenida."
                    ) {
                        OutlinedButton(onClick = onRestartTour) { Text("Repetir", color = colors.textPrimary) }
                    }
                }

                Section("Acerca de") {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("J-Autómata Studio $APP_VERSION", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                        Text("Compilador didáctico · GLC v7 · Lenguajes y Autómatas II", fontSize = 13.sp, color = colors.textSecondary)
                        Text("Created by: Laicamist", fontSize = 13.sp, color = colors.textSecondary)
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "Tipografías Alice y Sora bajo la SIL Open Font License 1.1 (el texto de las licencias va " +
                                "incluido en la app, en files/licencias).",
                            fontSize = 12.sp,
                            color = colors.textMuted
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Borrar historial") },
            text = { Text("Se eliminarán todas las sesiones guardadas con sus tablas y su código intermedio. No se puede deshacer.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        onClearHistory()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.errorLexical, contentColor = Color.White)
                ) { Text("Borrar todo") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun FontSizeRow(sizeSp: Int, onChange: (Int) -> Unit) {
    val colors = JAutomataTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tamaño de letra del editor", fontSize = 14.sp, color = colors.textPrimary)
                Text("También con Ctrl + / Ctrl − / Ctrl 0 en el editor.", fontSize = 12.sp, color = colors.textMuted)
            }
            TextButton(
                onClick = { onChange(sizeSp - 1) },
                enabled = sizeSp > AppSettings.MIN_EDITOR_FONT_SP
            ) { Text("A−", color = colors.textSecondary) }
            Text("$sizeSp", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, modifier = Modifier.width(28.dp))
            TextButton(
                onClick = { onChange(sizeSp + 1) },
                enabled = sizeSp < AppSettings.MAX_EDITOR_FONT_SP
            ) { Text("A+", color = colors.textSecondary) }
        }
        Slider(
            value = sizeSp.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = AppSettings.MIN_EDITOR_FONT_SP.toFloat()..AppSettings.MAX_EDITOR_FONT_SP.toFloat(),
            steps = AppSettings.MAX_EDITOR_FONT_SP - AppSettings.MIN_EDITOR_FONT_SP - 1,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.surface3
            )
        )
        Text(
            "public static void main(String []args)",
            fontSize = sizeSp.sp,
            fontFamily = JAutomataFonts.code,
            color = colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(colors.editorBackground).padding(8.dp)
        )
    }
}

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    val colors = JAutomataTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = JAutomataFonts.title, color = colors.textPrimary)
        subtitle?.let { Text(it, fontSize = 12.sp, color = colors.textMuted) }
        Column(
            Modifier.fillMaxWidth().clip(shape).border(1.dp, colors.border, shape).background(colors.surface2)
        ) { content() }
    }
}

@Composable
private fun SettingRow(title: String, detail: String, control: @Composable () -> Unit) {
    val colors = JAutomataTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = colors.textPrimary)
            Text(detail, fontSize = 12.sp, color = colors.textMuted)
        }
        control()
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = JAutomataTheme.colors.border)
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = JAutomataTheme.colors
    val shape = RoundedCornerShape(50)
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) colors.onAccent else colors.textSecondary,
        modifier = Modifier
            .clip(shape)
            .border(1.dp, if (selected) colors.accent else colors.border, shape)
            .background(if (selected) colors.accent else colors.surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** Debe coincidir con `packageVersion` de `desktopApp/build.gradle.kts`. */
private const val APP_VERSION = "1.0.0"
