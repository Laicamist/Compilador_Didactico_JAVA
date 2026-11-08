package edu.itssg.compiler.ui.settings

import edu.itssg.compiler.core.security.CompilerSecurityConfig

/** Renglón de la sección "Límites de seguridad" de Ajustes (solo lectura). */
internal data class LimitRow(val label: String, val value: String, val detail: String)

/** Límites del motor ([CompilerSecurityConfig]) con nombre y explicación en español. */
internal fun securityLimitRows(config: CompilerSecurityConfig): List<LimitRow> = listOf(
    LimitRow("Tamaño del código", "${groupDigits(config.maxSourceLength)} caracteres", "Más largo no se compila ni se abre."),
    LimitRow("Lexema más largo", "${groupDigits(config.maxLexemeLength)} caracteres", "Identificadores y cadenas."),
    LimitRow("Tokens por compilación", groupDigits(config.maxTokenCount), "Al llegar al tope el análisis se detiene con aviso."),
    LimitRow("Errores en la tabla", groupDigits(config.maxErrorCount), "Los siguientes ya no se registran."),
    LimitRow("Anidamiento de reglas", "${groupDigits(config.maxAstDepth)} niveles", "Protege la pila en Android e iOS."),
    LimitRow("Paréntesis anidados", "${groupDigits(config.maxAstDepth / 8)} niveles", "Una octava parte del anidamiento de reglas."),
    LimitRow(
        "Pasos por carácter",
        "× ${config.maxLoopIterationsFactor}",
        "Tope de trabajo del analizador: evita que una entrada lo trabe."
    )
)

/** `1000000` → `1,000,000` (separador de miles como en las láminas). */
internal fun groupDigits(value: Int): String {
    val digits = kotlin.math.abs(value.toLong()).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (value < 0) "-$grouped" else grouped
}
