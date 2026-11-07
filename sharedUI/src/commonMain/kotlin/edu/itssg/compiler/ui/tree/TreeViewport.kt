package edu.itssg.compiler.ui.tree

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Transformación del Canvas: `pantalla = mundo × scale + offset`.
 * Lógica pura (sin tipos de Compose) para probar el zoom y el desplazamiento en commonTest.
 */
data class Viewport(val scale: Float, val offsetX: Float, val offsetY: Float) {

    fun toScreenX(worldX: Float): Float = worldX * scale + offsetX
    fun toScreenY(worldY: Float): Float = worldY * scale + offsetY
    fun toWorldX(screenX: Float): Float = (screenX - offsetX) / scale
    fun toWorldY(screenY: Float): Float = (screenY - offsetY) / scale

    /** Rectángulo del mundo que se ve en una vista de [width] × [height] píxeles. */
    fun visibleWorld(width: Float, height: Float): WorldRect =
        WorldRect(toWorldX(0f), toWorldY(0f), toWorldX(width), toWorldY(height))

    fun panBy(dx: Float, dy: Float): Viewport = copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

    /** Zoom alrededor de un punto de la pantalla: el punto del mundo que está bajo [focusX], [focusY] no se mueve. */
    fun zoomAt(factor: Float, focusX: Float, focusY: Float): Viewport {
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val worldX = toWorldX(focusX)
        val worldY = toWorldY(focusY)
        return Viewport(newScale, focusX - worldX * newScale, focusY - worldY * newScale)
    }

    /** Misma escala, con el punto del mundo ([worldX], [worldY]) en el centro de la vista. */
    fun centeredOn(worldX: Float, worldY: Float, width: Float, height: Float): Viewport =
        copy(offsetX = width / 2f - worldX * scale, offsetY = height / 2f - worldY * scale)

    /**
     * Evita perder el árbol de vista: siempre quedan al menos [margin] píxeles del contenido dentro de la vista en cada
     * eje.
     */
    fun clampedTo(content: WorldRect, width: Float, height: Float, margin: Float): Viewport {
        val left = toScreenX(content.left)
        val right = toScreenX(content.right)
        val topEdge = toScreenY(content.top)
        val bottomEdge = toScreenY(content.bottom)
        var dx = 0f
        var dy = 0f
        val keepX = min(margin, (right - left) / 2f)
        val keepY = min(margin, (bottomEdge - topEdge) / 2f)
        if (right < keepX) dx = keepX - right
        if (left > width - keepX) dx = (width - keepX) - left
        if (bottomEdge < keepY) dy = keepY - bottomEdge
        if (topEdge > height - keepY) dy = (height - keepY) - topEdge
        return if (dx == 0f && dy == 0f) this else panBy(dx, dy)
    }

    companion object {
        const val MIN_SCALE: Float = 0.06f
        const val MAX_SCALE: Float = 3f

        /** Encuadra [content] dentro de la vista con [padding] píxeles de margen, sin pasar de [maxScale]. */
        fun fit(content: WorldRect, width: Float, height: Float, padding: Float, maxScale: Float = 1.15f): Viewport {
            if (width <= 0f || height <= 0f) return Viewport(1f, 0f, 0f)
            val availableW = max(width - 2 * padding, 1f)
            val availableH = max(height - 2 * padding, 1f)
            val scale = min(
                min(availableW / max(content.width, 1f), availableH / max(content.height, 1f)),
                maxScale
            ).coerceIn(MIN_SCALE, MAX_SCALE)
            val offsetX = width / 2f - content.centerX * scale
            // Si sobra alto, el árbol queda arriba (la raíz cerca del borde superior), como en las láminas.
            val offsetY = padding - content.top * scale
            return Viewport(scale, offsetX, offsetY)
        }

        /**
         * Paso intermedio de una animación entre dos vistas: la escala cambia de forma geométrica (el zoom se percibe
         * parejo) y el centro de la vista se mueve en línea recta.
         */
        fun interpolate(from: Viewport, to: Viewport, t: Float, width: Float, height: Float): Viewport {
            val f = t.coerceIn(0f, 1f)
            val scale = exp(ln(from.scale) + (ln(to.scale) - ln(from.scale)) * f)
            val fromX = from.toWorldX(width / 2f)
            val fromY = from.toWorldY(height / 2f)
            val toX = to.toWorldX(width / 2f)
            val toY = to.toWorldY(height / 2f)
            val cx = fromX + (toX - fromX) * f
            val cy = fromY + (toY - fromY) * f
            return Viewport(scale, width / 2f - cx * scale, height / 2f - cy * scale)
        }
    }
}

/** El rectángulo [rect] del mundo se ve completo (con [margin] píxeles de holgura) en la vista. */
fun Viewport.shows(rect: WorldRect, width: Float, height: Float, margin: Float): Boolean =
    toScreenX(rect.left) >= margin && toScreenX(rect.right) <= width - margin &&
            toScreenY(rect.top) >= margin && toScreenY(rect.bottom) <= height - margin
