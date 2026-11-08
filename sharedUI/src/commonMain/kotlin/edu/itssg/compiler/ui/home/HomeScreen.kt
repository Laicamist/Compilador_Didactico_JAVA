package edu.itssg.compiler.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.ui.data.ThemeMode
import edu.itssg.compiler.ui.state.CompilerPhase
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme
import edu.itssg.compiler.ui.viewmodel.HelpTopic
import edu.itssg.compiler.ui.viewmodel.HomeDemoState
import edu.itssg.compiler.ui.viewmodel.TourStep

/**
 * Pantalla de Inicio (especificación del 31 oct 2026):
 * - Arriba a la izquierda, los logos de TecNM e ITSUR y "Created by: Laicamist"; arriba a la derecha Historial, tema y
 *   Ajustes.
 * - Al centro el espacio del logo, el nombre, la línea de fases animada (un token viaja Léx → … → Opt) y los botones.
 * - Fondo decorativo de baja opacidad con referencias a la materia (se oculta en pantallas angostas).
 * - Abajo, el menú "¿Cómo funciona?": cada tema abre su hoja ([HelpSheet]).
 * - Tour de primer uso ([TourOverlay]) sobre la línea de fases, Nuevo trabajo, el menú y el Historial.
 */
@Composable
fun HomeScreen(
    themeMode: ThemeMode,
    animationsEnabled: Boolean,
    demo: HomeDemoState,
    openHelpTopic: HelpTopic?,
    tourStep: TourStep?,
    historySummary: String,
    hasWorkInProgress: Boolean,
    workInProgressTitle: String,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNewWork: () -> Unit,
    onOpenFile: () -> Unit,
    onContinueWork: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: (HelpTopic) -> Unit,
    onCloseHelp: () -> Unit,
    onPreviousHelp: () -> Unit,
    onNextHelp: () -> Unit,
    onSeeExample: (HelpContent) -> Unit,
    onTourNext: () -> Unit,
    onTourSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    // Posición de cada elemento que señala el tour (coordenadas de la ventana).
    val anchors = remember { mutableStateMapOf<TourStep, Rect>() }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    fun Modifier.tourAnchor(step: TourStep): Modifier = onGloballyPositioned {
        val bounds = it.boundsInRoot()
        if (anchors[step] != bounds) anchors[step] = bounds
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .onGloballyPositioned { rootOrigin = it.positionInRoot() }
    ) {
        val wide = maxWidth >= 600.dp
        if (wide) DecorativeBackground()

        Column(Modifier.fillMaxSize()) {
            // ------------------------------------------------------------ Barra superior
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InstitutionMark("TecNM")
                Spacer(Modifier.size(6.dp))
                InstitutionMark("ITSUR")
                Spacer(Modifier.size(10.dp))
                if (wide) Text("Created by: Laicamist", fontSize = 12.sp, color = colors.textMuted)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenHistory, modifier = Modifier.tourAnchor(TourStep.HISTORY)) {
                    Text("Historial", fontSize = 13.sp, color = colors.textPrimary)
                }
                TextButton(onClick = { onThemeModeChange(nextThemeMode(themeMode)) }) {
                    Text("Tema: ${themeMode.displayName}", fontSize = 13.sp, color = colors.textSecondary)
                }
                TextButton(onClick = onOpenSettings) { Text("Ajustes", fontSize = 13.sp, color = colors.textPrimary) }
            }

            // ------------------------------------------------------------ Centro
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LogoSlot()
                    Text(
                        "J-Autómata Studio",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = JAutomataFonts.title,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center
                    )
                    Text("Compilador didáctico · GLC v7", fontSize = 15.sp, color = colors.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    PhaseLine(animationsEnabled, Modifier.tourAnchor(TourStep.PHASE_LINE))
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onNewWork,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                        modifier = Modifier.fillMaxWidth().tourAnchor(TourStep.NEW_WORK)
                    ) { Text("Nuevo trabajo", fontSize = 15.sp) }
                    OutlinedButton(onClick = onOpenFile, modifier = Modifier.fillMaxWidth()) {
                        Text("Abrir archivo .java / .txt", color = colors.textPrimary)
                    }
                    if (hasWorkInProgress) {
                        TextButton(onClick = onContinueWork, modifier = Modifier.fillMaxWidth()) {
                            Text("Continuar con “$workInProgressTitle”", color = colors.textPrimary)
                        }
                    }
                    Text(historySummary, fontSize = 12.sp, color = colors.textMuted, textAlign = TextAlign.Center)
                    if (!wide) Text("Created by: Laicamist", fontSize = 12.sp, color = colors.textMuted)
                }
            }

            // ------------------------------------------------------------ Menú "¿Cómo funciona?"
            HorizontalDivider(color = colors.border)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface1)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).tourAnchor(TourStep.HELP_MENU),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("¿Cómo funciona?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Spacer(Modifier.size(4.dp))
                    HelpTopic.entries.forEach { topic ->
                        HelpChip(topic, selected = topic == openHelpTopic, onClick = { onOpenHelp(topic) })
                    }
                }
            }
        }

        HelpSheet(
            topic = openHelpTopic,
            demo = demo,
            animationsEnabled = animationsEnabled,
            onClose = onCloseHelp,
            onPrevious = onPreviousHelp,
            onNext = onNextHelp,
            onSeeExample = onSeeExample
        )

        if (tourStep != null && openHelpTopic == null) {
            val target = anchors[tourStep]?.let { it.translate(-rootOrigin.x, -rootOrigin.y) }
            TourOverlay(step = tourStep, target = target, onNext = onTourNext, onSkip = onTourSkip)
        }
    }
}

/** Espacio para los logos institucionales (con fondo transparente); el autor coloca las imágenes oficiales. */
@Composable
private fun InstitutionMark(name: String) {
    val colors = JAutomataTheme.colors
    Text(
        name,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = JAutomataFonts.title,
        color = colors.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, colors.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/** Espacio reservado arriba del nombre para el logo de Java (marca de Oracle: lo coloca el autor). */
@Composable
private fun LogoSlot() {
    val colors = JAutomataTheme.colors
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface2)
            .border(1.dp, colors.border, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text("J", fontSize = 38.sp, fontWeight = FontWeight.Bold, fontFamily = JAutomataFonts.title, color = colors.accent)
    }
}

/** Etapas de la línea de fases del Inicio (el árbol va entre el sintáctico y el semántico). */
private enum class HomeStage(val label: String, val phase: CompilerPhase) {
    LEX("Léx", CompilerPhase.LEXICAL),
    SIN("Sin", CompilerPhase.SYNTACTIC),
    TREE("Árbol", CompilerPhase.SYNTACTIC),
    SEM("Sem", CompilerPhase.SEMANTIC),
    CI("CI", CompilerPhase.INTERMEDIATE_CODE),
    OPT("Opt", CompilerPhase.OPTIMIZATION)
}

/** Línea Léx → Sin → Árbol → Sem → CI → Opt con un token que la recorre (sin animación: todas encendidas). */
@Composable
private fun PhaseLine(animationsEnabled: Boolean, modifier: Modifier = Modifier) {
    val colors = JAutomataTheme.colors
    val stages = HomeStage.entries
    val progress = if (animationsEnabled) {
        val transition = rememberInfiniteTransition()
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 5_400, easing = LinearEasing), RepeatMode.Restart)
        )
        value
    } else {
        null
    }
    val active = progress?.let { (it * stages.size).toInt().coerceIn(0, stages.lastIndex) }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            stages.forEachIndexed { index, stage ->
                if (index > 0) Text("→", fontSize = 12.sp, color = colors.textMuted)
                val color = if (stage == HomeStage.TREE) colors.syntactic else colors.phase(stage.phase)
                val on = active == null || index <= active
                Text(
                    stage.label,
                    fontSize = 13.sp,
                    fontWeight = if (index == active) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (on) color else colors.textMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, if (on) color else colors.border, RoundedCornerShape(50))
                        .background(if (index == active) color.copy(alpha = 0.16f) else colors.surface2)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
        if (progress != null) {
            // El token que viaja por la línea.
            Canvas(Modifier.fillMaxWidth(0.9f).height(12.dp)) {
                val y = size.height / 2f
                drawLine(colors.border, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                val x = size.width * progress
                drawCircle(colors.phase(stages[active ?: 0].phase), radius = 4.5.dp.toPx(), center = Offset(x, y))
            }
        }
    }
}

@Composable
private fun HelpChip(topic: HelpTopic, selected: Boolean, onClick: () -> Unit) {
    val colors = JAutomataTheme.colors
    val accent = topic.phase?.let { colors.phase(it) } ?: colors.accent
    Text(
        topic.displayName,
        fontSize = 12.sp,
        color = if (selected) colors.onAccent else colors.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, if (selected) accent else colors.border, RoundedCornerShape(50))
            .background(if (selected) accent else colors.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** Referencias de la materia, muy tenues, repartidas por el fondo. */
@Composable
private fun DecorativeBackground() {
    val colors = JAutomataTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        for (item in DECORATIONS) {
            Text(
                item.text,
                fontSize = item.size.sp,
                fontFamily = JAutomataFonts.code,
                color = colors.textPrimary.copy(alpha = 0.07f),
                modifier = Modifier.offset(x = w * item.x, y = h * item.y)
            )
        }
    }
}

private class Decoration(val text: String, val x: Float, val y: Float, val size: Int)

private val DECORATIONS = listOf(
    Decoration("q0 —letra→ q1 —dígito→ q2", 0.04f, 0.16f, 15),
    Decoration("δ(q0, a) = q1", 0.70f, 0.13f, 16),
    Decoration("[a-zA-Z_][a-zA-Z0-9_]*", 0.06f, 0.30f, 14),
    Decoration("E → E + T | T", 0.74f, 0.30f, 17),
    Decoration("T → T * F | F", 0.75f, 0.36f, 15),
    Decoration("L = { aⁿbⁿ | n ≥ 1 }", 0.05f, 0.48f, 16),
    Decoration("Σ = { a, b }", 0.80f, 0.50f, 18),
    Decoration("S ⇒* w", 0.10f, 0.64f, 20),
    Decoration("⟨ID, x⟩  ⟨ODA, =⟩  ⟨VTE, 5⟩", 0.64f, 0.64f, 14),
    Decoration("(5, +, 3, T1)", 0.08f, 0.78f, 16),
    Decoration("T1 = 5 + 3", 0.76f, 0.78f, 16),
    Decoration("ε", 0.30f, 0.86f, 28),
    Decoration("λ", 0.62f, 0.88f, 26),
    Decoration("DEC → TY ID ODA EXP FI", 0.32f, 0.08f, 14)
)
