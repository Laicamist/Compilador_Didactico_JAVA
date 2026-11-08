package edu.itssg.compiler.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme
import edu.itssg.compiler.ui.viewmodel.HelpTopic
import edu.itssg.compiler.ui.viewmodel.HomeDemoState
import kotlinx.coroutines.delay

/**
 * Hoja deslizante (~70 % de la altura) de "¿Cómo funciona?": explicación, ejemplo de entrada → salida con la
 * compilación real de `int x = 5 + 3;` (los renglones de la salida aparecen uno por uno si las animaciones están
 * activas), navegación ◀ ▶ entre temas y "Ver ejemplo", que abre el ejemplo en el espacio de trabajo.
 */
@Composable
internal fun HelpSheet(
    topic: HelpTopic?,
    demo: HomeDemoState,
    animationsEnabled: Boolean,
    onClose: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeeExample: (HelpContent) -> Unit
) {
    // Se conserva el último tema para que la hoja se vea mientras se cierra (no es estado: no provoca recomposición).
    val lastTopic = remember { arrayOfNulls<HelpTopic>(1) }
    if (topic != null) lastTopic[0] = topic
    val colors = JAutomataTheme.colors

    AnimatedVisibility(visible = topic != null, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
        )
    }
    AnimatedVisibility(
        visible = topic != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        val current = topic ?: lastTopic[0] ?: return@AnimatedVisibility
        val content = remember(current, demo.report) { helpContent(current, demo.report) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 860.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(colors.surface1)
                    // La hoja no deja pasar los toques al fondo (que la cerraría).
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            ) {
                SheetHeader(content, onClose, onPrevious, onNext)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    content.paragraphs.forEach { Text(it, fontSize = 14.sp, color = colors.textPrimary) }
                    ExampleBlock(content, demo, animationsEnabled)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${current.ordinal + 1} de ${HelpTopic.entries.size}",
                        fontSize = 12.sp,
                        color = colors.textMuted,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onSeeExample(content) },
                        enabled = demo.report != null,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.phase(content.phase), contentColor = Color.White)
                    ) { Text("Ver ejemplo") }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(content: HelpContent, onClose: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    val colors = JAutomataTheme.colors
    Column {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(colors.border))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(colors.phase(content.phase)))
            Spacer(Modifier.size(10.dp))
            Text(
                content.title,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = JAutomataFonts.title,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onPrevious) { Text("◀", color = colors.textSecondary) }
            TextButton(onClick = onNext) { Text("▶", color = colors.textSecondary) }
            TextButton(onClick = onClose) { Text("✕", color = colors.textSecondary) }
        }
    }
}

/** Entrada → salida del ejemplo; en pantallas anchas lado a lado. */
@Composable
private fun ExampleBlock(content: HelpContent, demo: HomeDemoState, animationsEnabled: Boolean) {
    val colors = JAutomataTheme.colors
    val accent = colors.phase(content.phase)
    val lines = content.outputLines
    // Cuántos renglones de la salida se ven (la animación los va mostrando y vuelve a empezar).
    var shown by remember(content.topic, lines) { mutableIntStateOf(if (animationsEnabled) 0 else lines.size) }
    LaunchedEffect(content.topic, lines, animationsEnabled) {
        if (!animationsEnabled || lines.isEmpty()) {
            shown = lines.size
            return@LaunchedEffect
        }
        while (true) {
            for (i in 0..lines.size) {
                shown = i
                delay(STEP_MILLIS)
            }
            delay(PAUSE_MILLIS)
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sideBySide = maxWidth >= 620.dp
        val input: @Composable (Modifier) -> Unit = { m ->
            ExampleCard(content.inputLabel, m, accent) {
                Text(content.input, fontSize = 14.sp, fontFamily = JAutomataFonts.code, color = colors.textPrimary)
            }
        }
        val output: @Composable (Modifier) -> Unit = { m ->
            ExampleCard(content.outputLabel, m, accent) {
                when {
                    demo.isLoading -> Text("Compilando el ejemplo…", fontSize = 13.sp, color = colors.textMuted)
                    demo.failure != null -> Text("El ejemplo no compiló: ${demo.failure}", fontSize = 13.sp, color = colors.errorLexical)
                    lines.isEmpty() -> Text("—", fontSize = 13.sp, color = colors.textMuted)
                    else -> lines.forEachIndexed { index, line ->
                        Text(
                            line,
                            fontSize = 13.sp,
                            fontFamily = JAutomataFonts.code,
                            color = if (index < shown) colors.textPrimary else colors.textPrimary.copy(alpha = 0.15f),
                            fontWeight = if (index == shown - 1 && animationsEnabled) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
        if (sideBySide) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                input(Modifier.weight(0.4f))
                Text("→", fontSize = 22.sp, color = accent, modifier = Modifier.padding(top = 26.dp))
                output(Modifier.weight(0.6f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                input(Modifier.fillMaxWidth())
                Text("↓", fontSize = 20.sp, color = accent, modifier = Modifier.padding(start = 12.dp))
                output(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ExampleCard(label: String, modifier: Modifier, accent: Color, body: @Composable () -> Unit) {
    val colors = JAutomataTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface2)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent)
        body()
    }
}

private const val STEP_MILLIS = 600L
private const val PAUSE_MILLIS = 1_800L
