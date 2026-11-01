package edu.itssg.compiler.core.error

import edu.itssg.compiler.core.security.SourceSpan

/**
 * Clasificación oficial de fases para la columna "Tipo" de la Tabla de Errores.
 */
enum class ErrorPhaseType(val displayName: String) {
    LEXICAL("Léxico"),
    SYNTACTIC("Sintáctico"),
    SEMANTIC("Semántico")
}

/**
 * Fila de la Tabla de Errores (columnas de las láminas: Línea | Error | Tipo | Justificación).
 *
 * @property index Número secuencial del error (1-indexado).
 * @property type Columna "Tipo" (`Léxico`, `Sintáctico`, `Semántico`).
 * @property justification Columna "Justificación".
 * @property offendingLexeme Columna "Error": lexema o símbolo donde se originó la anomalía.
 * @property ruleName Regla de la GLC v7 involucrada (errores sintácticos) o regla semántica.
 * @property span Línea y columna en el código fuente.
 */
data class CompilerError(
    val index: Int,
    val type: ErrorPhaseType,
    val justification: String,
    val offendingLexeme: String,
    val ruleName: String? = null,
    val span: SourceSpan
) {
    val line: Int get() = span.start.line
    val column: Int get() = span.start.column
}

/**
 * Plantillas de justificación, redactadas como en las tablas de errores de la Unidad 1:
 * - Léxico: "En Java no existe ningún TOKEN para el @, por lo tanto el símbolo no es válido para el lenguaje"
 * - Sintáctico: "No cumple la regla CLASE, se esperaba un LLA y se encuentra un ID" + la producción de la GLC v7
 * - Semántico: "El ID length no se encuentra como atributo en la clase String de Java"
 */
object AcademicErrorTemplates {

    // ------------------------------------------------------------------ Léxicos

    fun unrecognizedSymbol(symbol: String): String =
        "En Java no existe ningún TOKEN para el $symbol, por lo tanto el símbolo no es válido para el lenguaje"

    fun invalidIdentifier(lexeme: String): String =
        "Con base en JAVA la cadena $lexeme no cumple con las características para ser un ID" +
            if (lexeme.firstOrNull()?.isDigit() == true) " (un ID no puede iniciar con un dígito)" else ""

    // -------------------------------------------------------------- Sintácticos

    /**
     * Error sintáctico estándar: la regla [rule] esperaba el token [expected] y se encontró [found].
     * Se agrega la producción de la GLC v7 que se rompe, como en las láminas.
     */
    fun ruleViolation(rule: String, expected: String, found: String, foundLexeme: String, production: String?): String {
        val foundText = if (foundLexeme.isEmpty()) found else "$found ($foundLexeme)"
        val head = "No cumple la regla $rule, se esperaba un $expected y se encuentra un $foundText"
        return if (production != null) "$head\nRompe la regla gramatical $production" else head
    }

    /** Violación de las "Consideraciones de la GLC v7" (herencia, listas, arreglos, estáticos...). */
    fun grammarConsiderationViolation(rule: String, detail: String, production: String?): String {
        val head = "Con base en las consideraciones de la GLC v7, $detail"
        return if (production != null) "$head\nRompe la regla gramatical $production" else head
    }

    // --------------------------------------------------------------- Semánticos

    fun undeclaredIdentifier(lexeme: String): String =
        "El ID $lexeme no ha sido declarado previamente, por lo tanto no tiene un TD ni un valor que se pueda usar"

    fun redeclaredIdentifier(lexeme: String, scope: String): String =
        "El ID $lexeme ya fue declarado previamente en el ámbito $scope"

    fun incompatibleAssignment(targetId: String, targetType: String, sourceType: String): String =
        "El TD del valor ($sourceType) es incompatible con el ID $targetId ($targetType) al que se quiere asignar"

    fun classUsedAsValue(lexeme: String): String =
        "El ID $lexeme es una clase, no tiene un valor ni un TD que pueda operarse"

    fun incompatibleOperands(operator: String, leftType: String, rightType: String): String =
        "Los TD $leftType y $rightType no son compatibles con el operador $operator"

    fun incompatibleUnary(operator: String, type: String): String =
        "El TD $type no es compatible con el operador unario $operator"

    fun stringAttributeNotFound(member: String): String =
        "El ID $member no se encuentra como atributo en la clase String de Java"

    fun stringMethodNotFound(member: String): String =
        "El método $member no se encuentra en la clase String de Java"

    fun memberNotFound(member: String, className: String): String =
        "El ID $member no se encuentra como atributo ni como método en la clase $className"

    fun notAMethod(member: String): String =
        "El ID $member no es un método, por lo tanto no puede recibir argumentos"

    fun methodUsedAsValue(member: String): String =
        "El ID $member es un método y se usa sin paréntesis (sin PA ARGS PC)"

    fun argumentCountMismatch(method: String, expected: Int, received: Int): String =
        "El método $method recibe $expected parámetro(s) y se le envían $received"

    fun argumentTypeMismatch(method: String, paramName: String, paramType: String, argType: String): String =
        "El TD del argumento ($argType) es incompatible con el parámetro $paramName ($paramType) del método $method"

    /** "El constructor por defecto de la clase Buscar NO es compatible con un constructor que recibe VTE". */
    fun incompatibleDefaultConstructor(className: String, receivedArgTokens: String): String =
        "El constructor por defecto de la clase $className NO es compatible con un constructor que recibe $receivedArgTokens"

    fun unknownClass(className: String): String =
        "La clase $className no existe en el programa ni en las clases de Java permitidas"

    fun objectTypeMismatch(declared: String, instantiated: String): String =
        "El TD del objeto ($declared) es incompatible con la instancia que se crea (new $instantiated)"

    fun missingReturn(method: String, type: String): String =
        "El método $method es de tipo $type y no regresa ningún valor (falta REE con RTN EXP FI)"

    fun voidReturnsValue(method: String): String =
        "El método $method es de tipo void (TM) y no puede regresar un valor"

    fun incompatibleReturn(method: String, methodType: String, valueType: String): String =
        "El TD del valor que se regresa ($valueType) es incompatible con el TD del método $method ($methodType)"

    fun nonBooleanCondition(rule: String, type: String): String =
        "La condición de $rule debe ser una expresión booleana y su TD es $type"

    fun uninitializedVariable(lexeme: String): String =
        "El ID $lexeme se usa sin haber recibido un valor (no fue inicializado)"

    fun notAssignable(lexeme: String, role: String): String =
        "El ID $lexeme es un(a) $role y no puede recibir una asignación"

    fun incrementOnNonVariable(operator: String): String =
        "El operador $operator solo puede aplicarse a una variable numérica"

    fun invalidCast(fromType: String, toType: String): String =
        "No es posible convertir el TD $fromType al TD $toType"

    fun treeLimitExceeded(limit: Int): String =
        "El árbol abstracto-semántico superó el límite seguro de $limit nodos; se truncó la expansión"
}
