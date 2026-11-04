package edu.itssg.compiler.core.lexer

/**
 * @property mnemonic Código exacto del token (ej. "PCK", "MA", "ID", "VTE").
 * @property description Descripción académica de la categoría léxica.
 */
enum class TokenCategory(
    val mnemonic: String,
    val description: String
) {
    // 1. Identificadores y Tipos de Datos
    ID(mnemonic = "ID", description = "Cualquier cadena que cumpla con las características de ser un identificador"),
    TD(mnemonic = "TD", description = "Tipo de dato primitivo (int, boolean, double, float, long, short, byte, char)"),
    STR(mnemonic = "STR", description = "Clase o tipo de cadena (String)"),
    TM(mnemonic = "TM", description = "Tipo de retorno vacío (void)"),
    TDD(mnemonic = "TDD", description = "Modificador constante (final)"),

    // 2. Operadores y Signos de Puntuación / Delimitadores
    ODA(mnemonic = "ODA", description = "Operador de asignación (=)"),
    FI(mnemonic = "FI", description = "Fin de instrucción (;)"),
    LLA(mnemonic = "LLA", description = "Llave que abre ({)"),
    LLC(mnemonic = "LLC", description = "Llave que cierra (})"),
    PA(mnemonic = "PA", description = "Paréntesis que abre (()"),
    PC(mnemonic = "PC", description = "Paréntesis que cierra ())"),
    COA(mnemonic = "COA", description = "Corchete que abre ([)"),
    COC(mnemonic = "COC", description = "Corchete que cierra (])"),
    OPA(mnemonic = "OPA", description = "Operador aritmético (/, *, +, -, %)"),
    DOP(mnemonic = "DOP", description = "Dos puntos (:)"),
    OPT(mnemonic = "OPT", description = "Operador ternario (?)"),
    OPL(mnemonic = "OPL", description = "Operador lógico (|, &)"),
    OPR(mnemonic = "OPR", description = "Operador relacional (<, >, ==)"),
    CA(mnemonic = "CA", description = "Conector de acceso (.)"),
    CO(mnemonic = "CO", description = "Coma separadora (,)"),
    DEC(mnemonic = "DEC", description = "Operador de decremento (--)"),
    INC(mnemonic = "INC", description = "Operador de incremento (++)"),
    OPB(mnemonic = "OPB", description = "Operador unario de bits (~)"),
    XOR(mnemonic = "XOR", description = "Operador OR exclusivo (^)"),
    OPN(mnemonic = "OPN", description = "Operador unario de negación (!)"),

    // 3. Palabras Reservadas de Estructura y Modificadores
    MA(mnemonic = "MA", description = "Modificador de acceso (public, protected, private, abstract)"),
    CL(mnemonic = "CL", description = "Declaración de clase (class)"),
    ISE(mnemonic = "ISE", description = "Inicio de bloque seguro (try)"),
    IMP(mnemonic = "IMP", description = "Importación de paquete (import)"),
    CE(mnemonic = "CE", description = "Captura de excepción (catch)"),
    BR(mnemonic = "BR", description = "Rompimiento de flujo (break)"),
    ASR(mnemonic = "ASR", description = "Aserción (assert)"),
    CS(mnemonic = "CS", description = "Caso de evaluación (case)"),
    PRIF(mnemonic = "PRIF", description = "Condicional si (if)"),
    PRE(mnemonic = "PRE", description = "Condicional sino (else)"),
    PRSW(mnemonic = "PRSW", description = "Estructura selectiva (switch)"),
    PRW(mnemonic = "PRW", description = "Ciclo mientras (while)"),
    PRDO(mnemonic = "PRDO", description = "Ciclo hacer (do)"),
    PRF(mnemonic = "PRF", description = "Ciclo para (for)"),
    DF(mnemonic = "DF", description = "Caso por defecto (deafult / default)"),
    TH(mnemonic = "TH", description = "Lanzamiento de excepción (throws)"),
    SY(mnemonic = "SY", description = "Sincronización de hilos (synchronized)"),
    PCK(mnemonic = "PCK", description = "Declaración de paquete (package)"),
    RTN(mnemonic = "RTN", description = "Retorno de método (return)"),
    SPR(mnemonic = "SPR", description = "Referencia a superclase (super)"),
    INOF(mnemonic = "INOF", description = "Verificación de instancia (instanceof)"),
    EXTS(mnemonic = "EXTS", description = "Herencia de clase (extends)"),
    FIN(mnemonic = "FIN", description = "Bloque final (finally)"),
    RF(mnemonic = "RF", description = "Autorreferencia de objeto (this)"),
    IMPL(mnemonic = "IMPL", description = "Implementación de interfaz (implements)"),
    ST(mnemonic = "ST", description = "Miembro estático (static)"),
    NW(mnemonic = "NW", description = "Instanciación de objeto (new)"),
    PMAIN(mnemonic = "PMAIN", description = "Identificador de método principal (main)"),

    // 4. Literales / Valores Constantes
    VTB(mnemonic = "VTB", description = "Valor tipo booleano (false, true)"),
    VTS(mnemonic = "VTS", description = "Cualquier string (incluye las comillas dobles y el contenido)"),
    VTE(mnemonic = "VTE", description = "Números enteros"),
    VTD(mnemonic = "VTD", description = "Cualquier número con decimal"),
    VTF(mnemonic = "VTF", description = "Número decimal con f"),
    VTC(mnemonic = "VTC", description = "Cualquier carácter encerrado entre comillas simples"),
    _NU(mnemonic = "_NU", description = "Valor nulo (null)"),

    // 5. Tokens de Control Interno
    EOF(mnemonic = "EOF", description = "Fin de archivo fuente");

    fun displayMnemonic(useSlideAliasForString: Boolean = false): String {
        return if (this == VTS && useSlideAliasForString) "VST" else mnemonic
    }

    companion object {
        const val MAIN_LEXEME: String = "main"

        /** Mapa de resolución O(1) para palabras reservadas exactas de Java según la tabla oficial. */
        val KEYWORDS: Map<String, TokenCategory> = mapOf(
            "int" to TD,
            "boolean" to TD,
            "double" to TD,
            "float" to TD,
            "long" to TD,
            "short" to TD,
            "byte" to TD,
            "char" to TD,

            "public" to MA,
            "protected" to MA,
            "private" to MA,
            "abstract" to MA,

            "class" to CL,
            "void" to TM,
            "try" to ISE,
            "import" to IMP,
            "catch" to CE,
            "break" to BR,
            "assert" to ASR,
            "case" to CS,
            "if" to PRIF,
            "else" to PRE,
            "switch" to PRSW,
            "while" to PRW,
            "false" to VTB,
            "true" to VTB,
            "throws" to TH,
            "synchronized" to SY,
            "package" to PCK,
            "return" to RTN,
            "super" to SPR,
            "instanceof" to INOF,
            "extends" to EXTS,
            "finally" to FIN,
            "this" to RF,
            "for" to PRF,
            "implements" to IMPL,
            "final" to TDD,
            "default" to DF,
            "null" to _NU,
            "static" to ST,
            "String" to STR,
            "do" to PRDO,
            "new" to NW
        )
    }
}