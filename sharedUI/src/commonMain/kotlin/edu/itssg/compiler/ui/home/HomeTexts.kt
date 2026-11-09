package edu.itssg.compiler.ui.home

import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.ui.data.ThemeMode
import edu.itssg.compiler.ui.state.CompilerPhase
import edu.itssg.compiler.ui.viewmodel.TourStep

/** Botón de tema del Inicio: Sistema → Claro → Oscuro → Sistema. */
internal fun nextThemeMode(mode: ThemeMode): ThemeMode = when (mode) {
    ThemeMode.SYSTEM -> ThemeMode.LIGHT
    ThemeMode.LIGHT -> ThemeMode.DARK
    ThemeMode.DARK -> ThemeMode.SYSTEM
}

/** Texto de cada globo del tour de bienvenida. */
internal fun tourText(step: TourStep): String = when (step) {
    TourStep.PHASE_LINE ->
        "Un compilador trabaja por fases: léxico, sintáctico, árbol, semántico, código intermedio y optimización. " +
                "La app muestra el resultado de cada una."
    TourStep.NEW_WORK ->
        "Empieza aquí: escribe o pega tu programa en Java (GLC v7) y compílalo todo o paso a paso para ver cada fase."
    TourStep.HELP_MENU ->
        "¿Dudas sobre una fase? Cada tema abre una hoja con la explicación y el ejemplo int x = 5 + 3; compilado de verdad."
    TourStep.HISTORY ->
        "Cada trabajo que guardes queda en el Historial, con sus errores y su código intermedio. Desde ahí lo reabres o lo exportas."
}

/**
 * Etapas del proceso animado del Inicio (Léxico → Sintáctico → Árbol → Semántico → CI → Optimización) con lo que
 * produce cada una para `int x = 5 + 3;`. Las muestras salen de la compilación real del ejemplo; mientras [report]
 * es `null` (o si algo no está) se usa el texto equivalente fijo, para que la línea nunca quede vacía.
 */
internal fun pipelineStages(report: CompilationReport?): List<PipelineStage> {
    val lexemes = report?.let { r ->
        val line = demoLine(r)
        r.lexicalTableRows.filter { it.line == line }.map { it.lexemeColumn }
    }.orEmpty()
    val lexical = if (lexemes.isEmpty()) "int · x · = · 5\n+ · 3 · ;"
    else lexemes.chunked(4).take(2).joinToString("\n") { it.joinToString(" · ") }

    val syntactic = report?.let { derivationLines(it) }
        ?.firstOrNull { it.startsWith("DEC") }
        ?.let(::splitProduction)
        ?: "DEC → TY ID\nODA EXP FI"

    val type = report?.symbolEntries?.firstOrNull { it.lexeme == "x" }?.dataType ?: "int"
    val semantic = "x : $type\n$type ← $type ✓"

    val quads = report?.intermediateCode?.quadruples.orEmpty()
    val intermediate = quads.firstOrNull()
        ?.let { "(${it.val1}, ${it.operator}, ${it.val2}, ${it.result})\n${quads.size} cuádruplos" }
        ?: "(5, +, 3, 8)\n3 cuádruplos"

    val optimizedQuads = report?.optimizationResult?.optimizedBundle?.quadruples.orEmpty()
    val optimization = optimizedQuads.lastOrNull()
        ?.let { "(${it.val1}, ${it.operator}, ${it.val2}, ${it.result})\n${quads.size} → ${optimizedQuads.size}" }
        ?: "(PMAIN, SOUT, 8, 8)\n3 → 1"

    return listOf(
        PipelineStage("Lex", "Léxico", CompilerPhase.LEXICAL, lexical),
        PipelineStage("Sin", "Sintáctico", CompilerPhase.SYNTACTIC, syntactic),
        PipelineStage("Árb", "Árbol", CompilerPhase.SYNTACTIC, "=( x, +(5, 3) )"),
        PipelineStage("Sem", "Semántico", CompilerPhase.SEMANTIC, semantic),
        PipelineStage("CI", "Cód. intermedio", CompilerPhase.INTERMEDIATE_CODE, intermediate),
        PipelineStage("Opt", "Optimización", CompilerPhase.OPTIMIZATION, optimization)
    )
}

/** "DEC → TY ID ODA EXP FI" → "DEC → TY ID\nODA EXP FI" (dos renglones para que quepa bajo el círculo). */
internal fun splitProduction(production: String): String {
    val parts = production.split(" → ", limit = 2)
    if (parts.size < 2) return production
    val symbols = parts[1].split(' ').filter { it.isNotBlank() }
    if (symbols.size < 3) return production
    val cut = symbols.size / 2
    return "${parts[0]} → ${symbols.take(cut).joinToString(" ")}\n${symbols.drop(cut).joinToString(" ")}"
}
