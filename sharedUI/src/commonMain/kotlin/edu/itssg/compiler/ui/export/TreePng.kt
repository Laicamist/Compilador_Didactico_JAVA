package edu.itssg.compiler.ui.export

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

/** Lado máximo y área máxima del PNG (protege la memoria del teléfono: 16 M píxeles ≈ 64 MB). */
private const val MAX_SIDE = 16_000
private const val MAX_PIXELS = 16_000_000L

/** Escalas que se intentan, de la más nítida a la más chica. */
private val SCALES = floatArrayOf(2f, 1.5f, 1f, 0.75f)

/**
 * Pinta el [picture] en una imagen y la codifica como PNG, o `null` si ni a escala 0.75 cabe en los límites.
 * Usa el mismo dibujo que el SVG ([buildTreePicture]), con una escala de 2× para que el texto se vea nítido.
 *
 * @param measurer Medidor de texto exclusivo de la exportación (se usa fuera del hilo principal).
 */
internal fun treePictureToPng(picture: TreePicture, measurer: TextMeasurer): ByteArray? {
    val scale = SCALES.firstOrNull { s ->
        val w = ceil(picture.width * s).toLong()
        val h = ceil(picture.height * s).toLong()
        w in 1..MAX_SIDE && h in 1..MAX_SIDE && w * h <= MAX_PIXELS
    } ?: return null
    val width = ceil(picture.width * scale).toInt()
    val height = ceil(picture.height * scale).toInt()
    val bitmap = ImageBitmap(width, height)
    // Densidad 1: 1 sp = 1 px dentro del dibujo; la escala se aplica con la transformación.
    val unit = Density(1f, 1f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))

    CanvasDrawScope().draw(unit, LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
        drawRect(Color(PrintPalette.BACKGROUND))
        scale(scale, scale, pivot = Offset.Zero) {
            for (shape in picture.shapes) {
                when (shape) {
                    is PictureShape.Rect -> {
                        drawRoundRect(
                            color = Color(shape.fill),
                            topLeft = Offset(shape.x, shape.y),
                            size = Size(shape.w, shape.h),
                            cornerRadius = CornerRadius(shape.radius, shape.radius)
                        )
                        drawRoundRect(
                            color = Color(shape.stroke),
                            topLeft = Offset(shape.x, shape.y),
                            size = Size(shape.w, shape.h),
                            cornerRadius = CornerRadius(shape.radius, shape.radius),
                            style = Stroke(width = shape.strokeWidth, pathEffect = if (shape.dashed) dash else null)
                        )
                    }
                    is PictureShape.Line -> drawLine(
                        color = Color(shape.color),
                        start = Offset(shape.x1, shape.y1),
                        end = Offset(shape.x2, shape.y2),
                        strokeWidth = shape.width,
                        pathEffect = if (shape.dashed) dash else null
                    )
                    is PictureShape.Circle -> drawCircle(Color(shape.fill), radius = shape.r, center = Offset(shape.cx, shape.cy))
                    is PictureShape.Text -> {
                        val style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (shape.bold) FontWeight.Bold else FontWeight.Normal,
                            fontSize = shape.size.sp,
                            color = Color(shape.color)
                        )
                        val layout = measurer.measure(shape.text, style, density = unit)
                        val x = if (shape.centered) shape.cx - layout.size.width / 2f else shape.cx
                        drawText(layout, topLeft = Offset(x, shape.top))
                    }
                }
            }
        }
    }
    return encodePng(bitmap)
}
