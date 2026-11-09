package edu.itssg.compiler.ui.home

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.ui.data.ThemeMode
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme
import edu.itssg.compiler.ui.viewmodel.HelpTopic
import edu.itssg.compiler.ui.viewmodel.HomeDemoState
import edu.itssg.compiler.ui.viewmodel.TourStep
import org.jetbrains.compose.resources.painterResource
import proyecto_automatas.sharedui.generated.resources.Logo_Java
import proyecto_automatas.sharedui.generated.resources.Res
import proyecto_automatas.sharedui.generated.resources.itsur
import proyecto_automatas.sharedui.generated.resources.tecnm

/**
 * Pantalla de Inicio (especificación y boceto del 31 oct 2026):
 * - Arriba a la izquierda, los logos de TecNM e ITSUR y "Created by: Laicamist"; arriba a la derecha los botones con
 *   borde redondeado: Historial (ícono + texto), tema (ícono del modo actual) y Ajustes (engrane).
 * - Al centro el logo de Java, el nombre, la entrada del ejemplo, el proceso animado ([PhasePipeline]) y los botones.
 * - Fondo decorativo de baja opacidad con dibujos de la materia ([DecorativeBackground]; se oculta en pantallas
 *   angostas).
 * - Abajo, el menú "¿Cómo funciona?": cada tema abre su hoja ([HelpSheet]).
 * - Tour de primer uso ([TourOverlay]) sobre el proceso, Nuevo trabajo, el menú y el Historial.
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
    val stages = remember(demo.report) { pipelineStages(demo.report) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .onGloballyPositioned { rootOrigin = it.positionInRoot() }
    ) {
        val wide = maxWidth >= 600.dp
        // Alto suficiente para el logo grande (200 dp) sin que el centro tenga que desplazarse.
        val roomy = maxHeight >= 800.dp
        DecorativeBackground()

        Column(Modifier.fillMaxSize()) {
            // ------------------------------------------------------------ Barra superior
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (wide) 28.dp else 12.dp, vertical = if (wide) 16.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InstitutionLogos(height = if (wide) 48.dp else 32.dp)
                if (wide) {
                    Spacer(Modifier.width(14.dp))
                    Box(Modifier.width(1.dp).height(36.dp).background(colors.borderStrong))
                    Spacer(Modifier.width(14.dp))
                    CreatedBy()
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HeaderLabeledButton(
                        icon = HomeIcons.History,
                        label = "Historial",
                        showLabel = wide,
                        onClick = onOpenHistory,
                        modifier = Modifier.tourAnchor(TourStep.HISTORY)
                    )
                    ThemeModeButton(mode = themeMode, animationsEnabled = animationsEnabled, onChange = onThemeModeChange)
                    HeaderIconButton(icon = HomeIcons.Settings, description = "Ajustes", onClick = onOpenSettings)
                }
            }

            // ------------------------------------------------------------ Centro
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    JavaLogo(
                        size = when {
                            wide && roomy -> 200.dp
                            wide -> 156.dp
                            else -> 124.dp
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "J-Autómata Studio",
                        fontSize = if (wide) 40.sp else 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = JAutomataFonts.title,
                        letterSpacing = (-0.5).sp,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Compilador didáctico · GLC v7", fontSize = 16.sp, color = colors.textSecondary)
                    Spacer(Modifier.height(if (roomy) 28.dp else 18.dp))

                    Column(
                        modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth().tourAnchor(TourStep.PHASE_LINE),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        DemoInputChip()
                        Spacer(Modifier.height(20.dp))
                        PhasePipeline(stages = stages, animationsEnabled = animationsEnabled)
                    }

                    Spacer(Modifier.height(if (roomy) 32.dp else 20.dp))
                    Column(
                        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onNewWork,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                            modifier = Modifier.fillMaxWidth().height(54.dp).tourAnchor(TourStep.NEW_WORK)
                        ) { Text("Nuevo trabajo", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                        OutlinedButton(
                            onClick = onOpenFile,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
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

/**
 * Logos de TecNM e ITSUR (PNG con fondo transparente, `composeResources/drawable`). En el tema oscuro van sobre una
 * placa clara: el azul marino del TecNM casi no se ve sobre el fondo negro.
 */
@Composable
private fun InstitutionLogos(height: Dp) {
    val colors = JAutomataTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (colors.isDark) LOGO_PLATE else Color.Transparent)
            .padding(horizontal = if (colors.isDark) 10.dp else 0.dp, vertical = if (colors.isDark) 5.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Image(
            painter = painterResource(Res.drawable.tecnm),
            contentDescription = "TecNM",
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(height).aspectRatio(TECNM_RATIO)
        )
        Image(
            painter = painterResource(Res.drawable.itsur),
            contentDescription = "ITSUR",
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(height).aspectRatio(ITSUR_RATIO)
        )
    }
}

/** Placa clara detrás de los logos en el tema oscuro (el mismo hueso del fondo del tema claro). */
private val LOGO_PLATE = Color(0xFFF7F5F0)

/** Proporción ancho / alto de los PNG (tecnm.png 337 × 483, itsur.png 480 × 414). */
private const val TECNM_RATIO = 337f / 483f
private const val ITSUR_RATIO = 480f / 414f

@Composable
private fun CreatedBy() {
    val colors = JAutomataTheme.colors
    Column {
        Text("Created by:", fontSize = 12.sp, lineHeight = 16.sp, color = colors.textSecondary)
        Text("Laicamist", fontSize = 15.sp, lineHeight = 19.sp, color = colors.textPrimary)
    }
}

/** Logo de Java (`Logo_Java.png`) arriba del nombre, con un halo tenue del color de acento. */
@Composable
private fun JavaLogo(size: Dp) {
    val colors = JAutomataTheme.colors
    val glow = colors.accent.copy(alpha = if (colors.isDark) 0.14f else 0.10f)
    Box(
        modifier = Modifier
            .size(size)
            .drawBehind {
                val radius = this.size.minDimension / 2f
                drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), center = center, radius = radius), radius = radius)
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(Res.drawable.Logo_Java),
            contentDescription = "Logo de Java",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
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
