package edu.itssg.compiler.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme
import kotlin.math.min

/*
 * Fondo decorativo del Inicio (boceto del 31 oct 2026): dibujos y fórmulas de la materia, muy tenues, en los
 * márgenes izquierdo y derecho para no estorbar al contenido del centro.
 *
 * Las posiciones vienen del boceto (1440 × 900): x en dp desde el borde de su lado, y como fracción de la altura.
 * Los tamaños se escalan con la ventana (0.8×–1.2×).
 *
 * - Desde 960 dp de ancho: AFD de identificadores, expresión regular y δ, gramática E → E + T, árbol de derivación,
 *   L = { aⁿbⁿ } / Σ / S ⇒* w y triplos.
 * - Desde 1200 dp: además ε y λ, pares ⟨token, lexema⟩, autómata de pila y tabla de cuádruplos.
 * - Menos de 960 dp de ancho o de 560 dp de alto: no se dibuja.
 */

/** Unidades del boceto → píxeles: [k] px por unidad, a partir de [origin]. */
private class Sketch(
    val scope: DrawScope,
    val measurer: TextMeasurer,
    val ink: Color,
    val mono: FontFamily,
    val serif: FontFamily,
    val k: Float,
    val origin: Offset
) {
    fun at(x: Float, y: Float): Offset = Offset(origin.x + x * k, origin.y + y * k)

    fun stroke(width: Float = 1.5f) = Stroke(width = width * k, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun line(alpha: Float, x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 1.5f) {
        scope.drawLine(ink.copy(alpha = alpha), at(x1, y1), at(x2, y2), strokeWidth = width * k, cap = StrokeCap.Round)
    }

    /** Línea con punta de flecha al final. */
    fun arrow(alpha: Float, x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 1.5f) {
        line(alpha, x1, y1, x2, y2, width)
        val dx = x2 - x1
        val dy = y2 - y1
        val len = kotlin.math.sqrt(dx * dx + dy * dy).takeIf { it > 0f } ?: return
        val ux = dx / len
        val uy = dy / len
        val head = 6f
        line(alpha, x2, y2, x2 - ux * head - uy * head * 0.8f, y2 - uy * head + ux * head * 0.8f, width)
        line(alpha, x2, y2, x2 - ux * head + uy * head * 0.8f, y2 - uy * head - ux * head * 0.8f, width)
    }

    fun circle(alpha: Float, cx: Float, cy: Float, r: Float, width: Float = 1.5f) {
        scope.drawCircle(ink.copy(alpha = alpha), radius = r * k, center = at(cx, cy), style = stroke(width))
    }

    fun rect(alpha: Float, x: Float, y: Float, w: Float, h: Float, width: Float = 1.3f) {
        scope.drawRect(ink.copy(alpha = alpha), topLeft = at(x, y), size = Size(w * k, h * k), style = stroke(width))
    }

    /**
     * Texto en unidades del boceto. [center] = el punto es el centro del texto; si no, la esquina superior izquierda.
     * El tamaño se pasa a sp dividiendo entre la densidad y la escala de fuente, para que mida exactamente lo pedido.
     */
    fun text(
        alpha: Float,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        center: Boolean = false,
        family: FontFamily = mono,
        lineHeight: Float = 1.8f,
        alignRight: Boolean = false
    ) {
        val unit = k / (scope.density * scope.fontScale)
        val style = TextStyle(
            color = ink.copy(alpha = alpha),
            fontFamily = family,
            fontSize = (size * unit).sp,
            lineHeight = (size * lineHeight * unit).sp
        )
        val layout = measurer.measure(value, style)
        val p = at(x, y)
        val topLeft = when {
            center -> Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f)
            alignRight -> Offset(p.x - layout.size.width, p.y)
            else -> p
        }
        scope.drawText(layout, topLeft = topLeft)
    }
}

@Composable
internal fun DecorativeBackground(modifier: Modifier = Modifier) {
    val colors = JAutomataTheme.colors
    val ink = colors.textPrimary
    val mono = JAutomataFonts.code
    val serif = JAutomataFonts.text
    val measurer = rememberTextMeasurer()
    // Un poco más marcado en el tema claro (tinta oscura sobre fondo hueso se pierde más).
    val strength = if (colors.isDark) 1f else 1.15f

    Canvas(modifier.fillMaxSize()) {
        if (size.width < 960.dp.toPx() || size.height < 560.dp.toPx()) return@Canvas
        val scale = min(size.width / 1440.dp.toPx(), size.height / 900.dp.toPx()).coerceIn(0.8f, 1.2f)
        val k = scale * density
        val extras = size.width >= 1200.dp.toPx()
        fun yOf(designY: Float) = size.height * designY / 900f

        /** Lienzo con origen a [x] dp del borde izquierdo (o derecho si [fromRight], midiendo [width] unidades). */
        fun sketch(x: Float, y: Float, fromRight: Boolean = false, width: Float = 0f): Sketch {
            val left = if (fromRight) size.width - (x + width) * k else x * k
            return Sketch(this, measurer, ink, mono, serif, k, Offset(left, yOf(y)))
        }

        // ------------------------------------------------------------ Lado izquierdo
        drawIdentifierDfa(sketch(44f, 96f), 0.16f * strength)
        sketch(56f, 300f).text(
            0.15f * strength,
            "[a-zA-Z_][a-zA-Z0-9_]*\nδ(q0, l) = q1\nδ(q1, d) = q1",
            0f, 0f, 14f
        )
        sketch(64f, 612f).text(
            0.15f * strength,
            "L = { aⁿbⁿ | n ≥ 0 }\nΣ = { a, b, c }\nS ⇒* w",
            0f, 0f, 14f
        )

        // ------------------------------------------------------------ Lado derecho
        sketch(56f, 104f, fromRight = true, width = 150f).text(
            0.16f * strength,
            "E → E + T | T\nT → T * F | F\nF → ( E ) | id",
            0f, 0f, 15f
        )
        drawDerivationTree(sketch(68f, 250f, fromRight = true, width = 200f), 0.15f * strength)
        sketch(64f, 600f, fromRight = true, width = 140f).text(
            0.15f * strength,
            "(0)  *   b   c\n(1)  +   a  (0)\n(2)  =   x  (1)\nt1 = b * c",
            0f, 0f, 14f
        )

        // ------------------------------------------------------------ Extras en ventanas anchas
        if (extras) {
            // ε y λ quedan a la altura del proceso: solo caben fuera de él en ventanas anchas.
            sketch(26f, 400f).text(0.05f * strength, "ε", 0f, 0f, 150f, family = serif, lineHeight = 1f)
            sketch(40f, 380f, fromRight = true, width = 80f).text(0.05f * strength, "λ", 0f, 0f, 150f, family = serif, lineHeight = 1f)
            sketch(330f, 150f).text(0.10f * strength, "⟨id, x⟩  ⟨op, =⟩  ⟨num, 5⟩", 0f, 0f, 13f)
            drawPushdownStack(sketch(250f, 600f), 0.15f * strength)
            drawQuadrupleTable(sketch(70f, 712f, fromRight = true, width = 180f), 0.15f * strength)
        }
    }
}

/** AFD de identificadores: q0 —l→ q1 (ciclo l, d) —otro→ q2 (final). Caja de 300 × 150 unidades. */
private fun drawIdentifierDfa(s: Sketch, alpha: Float) {
    s.arrow(alpha, 4f, 70f, 38f, 70f)
    s.circle(alpha, 62f, 70f, 22f)
    s.arrow(alpha, 84f, 70f, 148f, 70f)
    s.circle(alpha, 172f, 70f, 22f)
    // Ciclo sobre q1.
    val loop = Path().apply {
        val a = s.at(160f, 51f)
        val c1 = s.at(148f, 12f)
        val c2 = s.at(196f, 12f)
        val b = s.at(184f, 51f)
        moveTo(a.x, a.y)
        cubicTo(c1.x, c1.y, c2.x, c2.y, b.x, b.y)
    }
    s.scope.drawPath(loop, s.ink.copy(alpha = alpha), style = s.stroke())
    s.line(alpha, 184f, 51f, 179f, 46f)
    s.line(alpha, 184f, 51f, 186f, 44f)
    s.arrow(alpha, 194f, 70f, 248f, 70f)
    s.circle(alpha, 272f, 70f, 22f)
    s.circle(alpha, 272f, 70f, 17f)

    s.text(alpha, "q0", 62f, 70f, 13f, center = true, lineHeight = 1f)
    s.text(alpha, "q1", 172f, 70f, 13f, center = true, lineHeight = 1f)
    s.text(alpha, "q2", 272f, 70f, 13f, center = true, lineHeight = 1f)
    s.text(alpha, "l", 116f, 58f, 13f, center = true, lineHeight = 1f)
    s.text(alpha, "l, d", 172f, 9f, 13f, center = true, lineHeight = 1f)
    s.text(alpha, "otro", 221f, 58f, 13f, center = true, lineHeight = 1f)
    s.text(alpha, "AFD · identificador", 8f, 128f, 12f, lineHeight = 1f)
}

/** Árbol de derivación de E → E + T (200 × 125 unidades). */
private fun drawDerivationTree(s: Sketch, alpha: Float) {
    s.line(alpha, 100f, 20f, 40f, 50f, 1.4f)
    s.line(alpha, 100f, 20f, 100f, 50f, 1.4f)
    s.line(alpha, 100f, 20f, 160f, 50f, 1.4f)
    s.line(alpha, 40f, 72f, 40f, 98f, 1.4f)
    s.line(alpha, 160f, 72f, 160f, 98f, 1.4f)
    s.text(alpha, "E", 100f, 10f, 15f, center = true, lineHeight = 1f)
    s.text(alpha, "E", 40f, 61f, 15f, center = true, lineHeight = 1f)
    s.text(alpha, "+", 100f, 61f, 15f, center = true, lineHeight = 1f)
    s.text(alpha, "T", 160f, 61f, 15f, center = true, lineHeight = 1f)
    s.text(alpha, "T", 40f, 111f, 15f, center = true, lineHeight = 1f)
    s.text(alpha, "F", 160f, 111f, 15f, center = true, lineHeight = 1f)
}

/** Autómata de pila: la pila abierta arriba, con push (entra) y pop (sale). Caja de 160 × 135 unidades. */
private fun drawPushdownStack(s: Sketch, alpha: Float) {
    val left = 40f
    val right = 110f
    val top = 22f
    val cell = 22f
    // Paredes y fondo (abierta arriba).
    s.line(alpha, left, top - 4f, left, top + cell * 4, 1.4f)
    s.line(alpha, right, top - 4f, right, top + cell * 4, 1.4f)
    s.line(alpha, left, top + cell * 4, right, top + cell * 4, 1.4f)
    val symbols = listOf("T", "+", "E", "$")
    symbols.forEachIndexed { i, symbol ->
        val y = top + cell * i
        if (i > 0) s.line(alpha * 0.8f, left, y, right, y, 1f)
        s.text(alpha, symbol, (left + right) / 2f, y + cell / 2f, 13f, center = true, lineHeight = 1f)
    }
    // push ↓ y pop ↑
    s.arrow(alpha, 62f, 0f, 62f, 16f, 1.3f)
    s.arrow(alpha, 88f, 16f, 88f, 0f, 1.3f)
    s.text(alpha, "push", 56f, 0f, 11f, lineHeight = 1f, alignRight = true)
    s.text(alpha, "pop", 94f, 0f, 11f, lineHeight = 1f)
    s.text(alpha, "autómata de pila", 75f, 125f, 12f, center = true, lineHeight = 1f)
}

/** Tabla de cuádruplos de t = 5 + 3; x = t (180 × 85 unidades). */
private fun drawQuadrupleTable(s: Sketch, alpha: Float) {
    val columns = listOf(0f, 45f, 90f, 135f, 180f)
    val rowHeight = 20f
    val rows = listOf(
        listOf("v1", "op", "v2", "res"),
        listOf("5", "+", "3", "T1"),
        listOf("T1", "=", "", "x")
    )
    s.rect(alpha, 0f, 0f, 180f, rowHeight * rows.size)
    s.line(alpha, 0f, rowHeight, 180f, rowHeight, 1.6f)
    s.line(alpha * 0.8f, 0f, rowHeight * 2, 180f, rowHeight * 2, 1f)
    for (x in columns.subList(1, columns.size - 1)) s.line(alpha * 0.8f, x, 0f, x, rowHeight * rows.size, 1f)
    rows.forEachIndexed { r, row ->
        row.forEachIndexed { c, value ->
            if (value.isNotEmpty()) {
                s.text(alpha, value, (columns[c] + columns[c + 1]) / 2f, rowHeight * r + rowHeight / 2f, 12f, center = true, lineHeight = 1f)
            }
        }
    }
    s.text(alpha, "cuádruplos", 0f, rowHeight * rows.size + 8f, 12f, lineHeight = 1f)
}
