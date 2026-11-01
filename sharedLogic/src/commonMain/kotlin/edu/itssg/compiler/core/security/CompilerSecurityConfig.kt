package edu.itssg.compiler.core.security

/**
 * Configuración inmutable de cuotas de seguridad (Security Quotas) para el motor del compilador.
 * Protege contra Denegación de Servicio (DoS), agotamiento de memoria (OOM) en dispositivos móviles
 * y bucles infinitos durante las fases de análisis.
 *
 * Todo el motor usa un único paquete: `edu.itssg.compiler.core.security` (el paquete anterior
 * `...core.segurity` tenía un typo y ya no existe; cualquier import viejo debe actualizarse).
 *
 * @property maxSourceLength Longitud máxima del código fuente en caracteres (por defecto 1,000,000).
 * @property maxLexemeLength Longitud máxima de un lexema o literal de cadena (por defecto 4,096; si se reduce
 *   [maxSourceLength] por debajo de ese valor, el límite de lexema se ajusta solo para no invalidar la config).
 * @property maxTokenCount Cantidad máxima de tokens por sesión (por defecto 100,000).
 * @property maxErrorCount Máximo de errores que almacena la Tabla de Errores.
 * @property maxAstDepth Profundidad máxima de anidamiento permitida en los árboles.
 * @property maxLoopIterationsFactor Multiplicador de pasos permitidos por unidad de entrada (guardia de bucles).
 */
data class CompilerSecurityConfig(
    val maxSourceLength: Int = DEFAULT_MAX_SOURCE_LENGTH,
    val maxLexemeLength: Int = minOf(DEFAULT_MAX_LEXEME_LENGTH, maxSourceLength),
    val maxTokenCount: Int = DEFAULT_MAX_TOKEN_COUNT,
    val maxErrorCount: Int = DEFAULT_MAX_ERROR_COUNT,
    val maxAstDepth: Int = DEFAULT_MAX_AST_DEPTH,
    val maxLoopIterationsFactor: Int = DEFAULT_LOOP_ITERATIONS_FACTOR
) {
    init {
        require(maxSourceLength in 1..ABSOLUTE_MAX_SOURCE_LENGTH) {
            "maxSourceLength debe estar entre 1 y $ABSOLUTE_MAX_SOURCE_LENGTH caracteres."
        }
        require(maxLexemeLength in 1..maxSourceLength) {
            "maxLexemeLength debe ser positivo y no exceder maxSourceLength."
        }
        require(maxTokenCount in 1..ABSOLUTE_MAX_TOKEN_COUNT) {
            "maxTokenCount debe estar entre 1 y $ABSOLUTE_MAX_TOKEN_COUNT."
        }
        require(maxErrorCount in 1..ABSOLUTE_MAX_ERROR_COUNT) {
            "maxErrorCount debe estar entre 1 y $ABSOLUTE_MAX_ERROR_COUNT."
        }
        require(maxAstDepth in 8..ABSOLUTE_MAX_AST_DEPTH) {
            "maxAstDepth debe estar entre 8 y $ABSOLUTE_MAX_AST_DEPTH niveles para proteger el Call Stack en iOS/Android."
        }
        require(maxLoopIterationsFactor in 1..MAX_LOOP_FACTOR) {
            "maxLoopIterationsFactor debe estar entre 1 y $MAX_LOOP_FACTOR."
        }
    }

    /** Límite estricto de operaciones de lectura permitidas para una entrada de tamaño [inputLength]. */
    fun calculateMaxAllowedSteps(inputLength: Int): Long {
        val safeLength = inputLength.coerceAtLeast(1).toLong()
        return (safeLength * maxLoopIterationsFactor.toLong()) + 1024L
    }

    companion object {
        const val DEFAULT_MAX_SOURCE_LENGTH: Int = 1_000_000
        const val DEFAULT_MAX_LEXEME_LENGTH: Int = 4_096
        const val DEFAULT_MAX_TOKEN_COUNT: Int = 100_000
        const val DEFAULT_MAX_ERROR_COUNT: Int = 1_000

        /**
         * Profundidad máxima de reglas anidadas en el Parser. Cada nivel cuesta varias llamadas en la pila; 256
         * mantiene el análisis seguro en hilos con pila de 512 KB (hilos secundarios de iOS) y aun así permite
         * ~50 bloques `if` anidados. Los paréntesis anidados se limitan a `maxAstDepth / 8` (32).
         */
        const val DEFAULT_MAX_AST_DEPTH: Int = 256

        /**
         * Cada lectura/avance cuenta como un paso; un carácter puede costar hasta ~4 pasos
         * (peek + peekAhead + advance). Con factor 3 un archivo con comentarios largos abortaba por error.
         */
        const val DEFAULT_LOOP_ITERATIONS_FACTOR: Int = 8
        const val MAX_LOOP_FACTOR: Int = 64

        const val ABSOLUTE_MAX_SOURCE_LENGTH: Int = 5_000_000
        const val ABSOLUTE_MAX_TOKEN_COUNT: Int = 500_000
        const val ABSOLUTE_MAX_ERROR_COUNT: Int = 10_000
        const val ABSOLUTE_MAX_AST_DEPTH: Int = 2_048
    }
}

/** Resultado sellado de validación de seguridad sobre el flujo de entrada o estado del compilador. */
sealed interface SecurityCheckResult {
    data object Allowed : SecurityCheckResult

    data class Violation(
        val code: SecurityViolationCode,
        val message: String,
        val position: SourcePosition
    ) : SecurityCheckResult
}

/** Códigos tipados de violación de seguridad en tiempo de compilación. */
enum class SecurityViolationCode {
    SOURCE_QUOTA_EXCEEDED,
    LEXEME_LENGTH_EXCEEDED,
    TOKEN_QUOTA_EXCEEDED,
    ERROR_QUOTA_EXCEEDED,
    AST_DEPTH_EXCEEDED,
    INFINITE_LOOP_DETECTED,
    NULL_BYTE_DETECTED
}