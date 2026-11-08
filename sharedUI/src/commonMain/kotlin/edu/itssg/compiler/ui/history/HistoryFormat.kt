package edu.itssg.compiler.ui.history

import edu.itssg.compiler.ui.data.SavedSessionSummary
import edu.itssg.compiler.ui.state.CompilerPhase
import edu.itssg.compiler.ui.state.PhaseStatus

/** Fecha y hora local separadas en sus partes (calendario gregoriano). */
internal data class LocalDateTimeParts(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    /** Días desde el 1 de enero de 1970 en hora local (sirve para saber si dos fechas son el mismo día). */
    val epochDay: Long
)

private const val MILLIS_PER_DAY = 86_400_000L

private val MONTHS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

/** Convierte [epochMillis] a fecha y hora locales usando la diferencia con UTC que da [offsetMillis]. */
internal fun localParts(epochMillis: Long, offsetMillis: Long): LocalDateTimeParts {
    val local = epochMillis + offsetMillis
    val epochDay = local.floorDiv(MILLIS_PER_DAY)
    val millisOfDay = local.mod(MILLIS_PER_DAY)
    // Algoritmo "civil from days" (H. Hinnant): días desde 1970-01-01 → año/mes/día.
    val z = epochDay + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
    return LocalDateTimeParts(
        year = year,
        month = month,
        day = day,
        hour = (millisOfDay / 3_600_000).toInt(),
        minute = ((millisOfDay / 60_000) % 60).toInt(),
        epochDay = epochDay
    )
}

/**
 * Fecha de una sesión para la tarjeta del Historial: "Hoy, 18:26", "Ayer, 09:05" o "7 nov 2026, 18:26".
 *
 * @param offsetOf Diferencia con UTC en cada instante (por defecto, la del dispositivo); se reemplaza en pruebas.
 */
internal fun formatSessionDate(
    epochMillis: Long,
    nowMillis: Long,
    offsetOf: (Long) -> Long = ::localUtcOffsetMillis
): String {
    val date = localParts(epochMillis, offsetOf(epochMillis))
    val today = localParts(nowMillis, offsetOf(nowMillis))
    val time = "${date.hour.toString().padStart(2, '0')}:${date.minute.toString().padStart(2, '0')}"
    return when (today.epochDay - date.epochDay) {
        0L -> "Hoy, $time"
        1L -> "Ayer, $time"
        else -> "${date.day} ${MONTHS[date.month - 1]} ${date.year}, $time"
    }
}

/**
 * Estado de cada fase para los puntos de la tarjeta del Historial, con lo que se guardó de la sesión:
 * léxico, sintáctico y semántico ✗ si tuvieron errores; el código intermedio y la optimización quedan bloqueados
 * si hubo cualquier error (misma regla que el espacio de trabajo).
 */
internal fun historyPhaseMarks(session: SavedSessionSummary): List<Pair<CompilerPhase, PhaseStatus>> {
    // Abortada por los límites de seguridad: hay errores pero ninguno contado por fase.
    val aborted = session.hasErrors && session.totalErrorCount == 0
    return CompilerPhase.entries.map { phase ->
        val status = when (phase) {
            CompilerPhase.LEXICAL ->
                if (session.lexicalErrorCount > 0 || aborted) PhaseStatus.FAILED else PhaseStatus.SUCCESS
            CompilerPhase.SYNTACTIC -> when {
                aborted -> PhaseStatus.BLOCKED
                session.syntacticErrorCount > 0 -> PhaseStatus.FAILED
                else -> PhaseStatus.SUCCESS
            }
            CompilerPhase.SEMANTIC -> when {
                aborted -> PhaseStatus.BLOCKED
                session.semanticErrorCount > 0 -> PhaseStatus.FAILED
                else -> PhaseStatus.SUCCESS
            }
            CompilerPhase.INTERMEDIATE_CODE, CompilerPhase.OPTIMIZATION ->
                if (session.hasErrors) PhaseStatus.BLOCKED else PhaseStatus.SUCCESS
        }
        phase to status
    }
}

/** "6 → 3 cuádruplos", "4 cuádruplos" o "Sin código intermedio" (si tuvo errores). */
internal fun instructionSummary(session: SavedSessionSummary): String {
    if (session.hasErrors) return "Sin código intermedio"
    val original = session.originalInstructionCount
    val optimized = session.optimizedInstructionCount
    val unit = if (original == 1) "cuádruplo" else "cuádruplos"
    return if (optimized < original) "$original → $optimized $unit" else "$original $unit"
}

/** "Sin errores" o "2 léxicos · 1 semántico". */
internal fun errorSummary(session: SavedSessionSummary): String {
    if (!session.hasErrors) return "Sin errores"
    if (session.totalErrorCount == 0) return "Detenida por límites de seguridad"
    return buildList {
        if (session.lexicalErrorCount > 0) add(countLabel(session.lexicalErrorCount, "léxico", "léxicos"))
        if (session.syntacticErrorCount > 0) add(countLabel(session.syntacticErrorCount, "sintáctico", "sintácticos"))
        if (session.semanticErrorCount > 0) add(countLabel(session.semanticErrorCount, "semántico", "semánticos"))
    }.joinToString(" · ")
}

private fun countLabel(count: Int, singular: String, plural: String): String =
    "$count ${if (count == 1) singular else plural}"
