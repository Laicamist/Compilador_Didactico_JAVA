package edu.itssg.compiler.ui.export

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.ui.theme.JAutomataTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Qué se va a exportar: el reporte de una compilación con el título de su sesión.
 *
 * @property notice Aviso que se muestra en el diálogo (por ejemplo, que el código cambió desde que se compiló).
 */
class ExportTarget(val title: String, val report: CompilationReport, val notice: String? = null)

/**
 * Diálogo Exportar: el usuario elige los formatos y la plataforma guarda los archivos ([rememberExportSaver]).
 * Los archivos se preparan fuera del hilo principal; al terminar se avisa dónde quedaron con [onMessage].
 */
@Composable
fun ExportDialog(
    target: ExportTarget,
    nowMillis: () -> Long,
    onDismiss: () -> Unit,
    onMessage: (text: String, isError: Boolean) -> Unit
) {
    val colors = JAutomataTheme.colors
    val saver = rememberExportSaver()
    val scope = rememberCoroutineScope()
    // Medidor propio para el PNG: se usa en un hilo de fondo, separado del que usa la interfaz.
    val exportMeasurer = rememberTextMeasurer(cacheSize = 0)
    var selected by remember { mutableStateOf(DEFAULT_KINDS) }
    var working by remember { mutableStateOf(false) }

    fun export() {
        if (working || selected.isEmpty()) return
        working = true
        scope.launch {
            try {
                val kinds = selected
                val bundle = withContext(Dispatchers.Default) {
                    buildExportBundle(
                        report = target.report,
                        title = target.title,
                        kinds = kinds,
                        nowMillis = nowMillis(),
                        pngEncoder = { picture -> treePictureToPng(picture, exportMeasurer) }
                    )
                }
                saver.save(bundle) { result ->
                    working = false
                    when (result) {
                        is ExportSaveResult.Saved -> {
                            val files = if (result.fileCount == 1) "1 archivo" else "${result.fileCount} archivos"
                            val extra = if (bundle.warnings.isEmpty()) "" else " " + bundle.warnings.joinToString(" ")
                            onMessage("Se exportaron $files en ${result.location}.$extra", false)
                            onDismiss()
                        }
                        ExportSaveResult.Cancelled -> Unit
                        is ExportSaveResult.Failed -> onMessage(result.message, true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                working = false
                onMessage("No se pudo preparar la exportación: ${e.message ?: e::class.simpleName}", true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Exportar “${target.title}”") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                target.notice?.let { Text(it, fontSize = 13.sp, color = colors.intermediate) }
                if (target.report.hasErrors) {
                    Text(
                        "La compilación tiene errores: el código intermedio no se incluye (igual que en la app).",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                }
                ExportKind.entries.forEach { kind ->
                    val checked = kind in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !working) { selected = if (checked) selected - kind else selected + kind }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            enabled = !working,
                            colors = CheckboxDefaults.colors(checkedColor = colors.accent, checkmarkColor = colors.onAccent)
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(kind.displayName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text(kind.detail, fontSize = 12.sp, color = colors.textMuted)
                        }
                    }
                }
                Text(
                    exportLocationHint(),
                    fontSize = 12.sp,
                    color = colors.textMuted,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = ::export,
                enabled = !working && selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)
            ) {
                if (working) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.onAccent)
                } else {
                    Text("Exportar")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancelar") } }
    )
}

private val DEFAULT_KINDS = setOf(ExportKind.PDF_REPORT, ExportKind.CSV_TABLES, ExportKind.TREE_PNG, ExportKind.SOURCE_JAVA)

/** Dónde quedarán los archivos, para el pie del diálogo (cada plataforma lo describe). */
internal expect fun exportLocationHint(): String
