package edu.itssg.compiler.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.examples.ClassExample
import edu.itssg.compiler.ui.theme.JAutomataTheme

/**
 * Botón "Ejemplos ▾" de la barra superior: abre en el editor los programas de las láminas de clase
 * (los mismos que validan las pruebas) y los que sirven para llenar la Tabla de Errores.
 */
@Composable
internal fun ExamplesMenuButton(onPick: (ClassExample) -> Unit, enabled: Boolean = true) {
    val colors = JAutomataTheme.colors
    var open by remember { mutableStateOf(false) }
    val sections = remember { ExampleMenuModel.sections() }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) { Text("Ejemplos ▾", color = colors.textPrimary) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sections.forEachIndexed { index, section ->
                if (index > 0) HorizontalDivider(color = colors.border)
                Text(
                    text = section.title.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
                section.examples.forEach { example ->
                    DropdownMenuItem(
                        text = {
                            Column(Modifier.widthIn(max = 340.dp)) {
                                Text(ExampleMenuModel.label(example), fontSize = 14.sp, color = colors.textPrimary)
                                Text(example.description, fontSize = 12.sp, color = colors.textSecondary)
                            }
                        },
                        onClick = {
                            open = false
                            onPick(example)
                        }
                    )
                }
            }
        }
    }
}
