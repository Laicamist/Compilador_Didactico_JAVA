package edu.itssg.compiler.core.parser

/**
 * Producciones de la GLC v7
 *
 * ## Erratas del documento que se interpretan
 * - `L_CASES::=PRCA L_CASES|;` se lee `L_CASES::=CASE L_CASES|ε` (CASE sí está definida).
 * - `STATMNT_B` / `STATMNT_ B` se leen como `STAT_B`.
 * - `EXPBOO` ("expresión booleana") se analiza como `EXP` y el semántico verifica que su TD sea boolean.
 *
 * ## Extensiones necesarias para los ejemplos de clase
 * - `CLA::= ... | NW ID PA ARGS PC` → `new Uno().cuatro('@')`, `new Nueva().total`.
 * - `CLA::= ... | PA EXP PC` → `("1"+c).length()` (ejemplo `Lunes`).
 * - `EXP::= ... | PA TD PC EXP` (conversión) → `(char)(b + ...)`, `(float)(dos+tres*dos)`, `(int)(...)`.
 * - `ARG::= EXP | CO EXP` → argumentos que son expresiones.
 * - `INST::= ... | ACC FI | EXPU FI` → llamadas e incrementos como instrucción terminan en `;`.
 * - `SOUT::=ID CA ID CA ID PA EXP PC FI` → `System.out.println(...)` (STATMNT la usa pero no la define).
 * - `EXPU::= OPU VAL | VAL OPU | OPA VAL` → `a++` y el signo negativo `-5`.
 * - Cuerpos de `IFEL`, `WHILE`, `DO` y `FOR` como `LLA CUERPO LLC` (reglas moradas no verificadas).
 */
object GlcV7 {

    val PRODUCTIONS: Map<String, String> = mapOf(
        "JAVA" to "JAVA::=DPACKAGE IMPORTS CLASE",
        "DPACKAGE" to "DPACKAGE::=PCK ID FI",
        "IMPORTS" to "IMPORTS::=IMPORTS IMPORT|ε",
        "IMPORT" to "IMPORT::=IMP ID CA ID FI",
        "CLASE" to "CLASE::=MA CL ID LLA CONTENT LLC",
        "DECS_GLOB" to "DECS_GLOB::=D_GLOB DECS_GLOB|ε",
        "D_GLOB" to "D_GLOB::=MAO TY ID FI|MAO TY ID ODA EXP FI|OBJ",
        "MAO" to "MAO::=MA|ε",
        "CONTENT" to "CONTENT::=VARIOS CONTENT|ε",
        "VARIOS" to "VARIOS::=MAIN|METODO|DECS_GLOB",
        "MAIN" to "MAIN::=MA ST TM PMAIN PA STR COA COC ID PC LLA CUERPO LLC",
        "CUERPO" to "CUERPO::=INST CUERPO|ε",
        "INST" to "INST::=DEC|STAT_B|ASIG|ACC|OBJ",
        "OBJ" to "OBJ::=ID ID ODA NW ID PA ARGS PC FI",
        "DEC" to "DEC::=TY ID FI|TY ID ODA EXP FI",
        "ASIG" to "ASIG::=ID ODA EXP FI",
        "STAT_B" to "STAT_B::=STAT_B STATMNT|ε",
        "VAL" to "VAL::=VTE|VTD|VTS|VTB|ID|VTF|VTC",
        "EXPC" to "EXPC::=EXP OP EXP|PA EXP OP EXP PC|EXPU",
        "EXPU" to "EXPU::=OPU VAL",
        "OPU" to "OPU::=DEC|INC|OPB|OPN",
        "OP" to "OP::=OPA|OPR|OPL",
        "METODO" to "METODO::=MA TYPE ID PA PARAMS PC LLA CUERPO REE LLC",
        "REE" to "REE::=RTN FI|RTN EXP FI|ε",
        "EXP" to "EXP::=EXPC|VAL|ACC",
        "ACC" to "ACC::=CLA OT",
        "CLA" to "CLA::=ID|VTS",
        "OT" to "OT::=CA ID OT|CA ID PA ARGS PC OT|ε",
        "ARGS" to "ARGS::=ARG ARGS|ε",
        "ARG" to "ARG::=VAL|CO VAL",
        "TYPE" to "TYPE::=TY|TM",
        "PARAMS" to "PARAMS::=PARAM PARAMS|ε",
        "PARAM" to "PARAM::=TY ID|CO TY ID",
        "TY" to "TY::=ID|TD|STR",
        "STATMNT" to "STATMNT::=WHILE|IFEL|DO|SWITCH|FOR|SOUT",
        "SWITCH" to "SWITCH::=PRSW PA VAL PC LLA L_CASES C_DEF LLC",
        "L_CASES" to "L_CASES::=CASE L_CASES|ε",
        "CASE" to "CASE::=CS VAL DOP CUERPO BREAK",
        "BREAK" to "BREAK::=BR FI|ε",
        "C_DEF" to "C_DEF::=DF DOP CUERPO BREAK|ε",
        "DO" to "DO::=PRDO STAT_B PRW PA EXP PC FI",
        "WHILE" to "WHILE::=PRW PA EXP PC STAT_B",
        "FOR" to "FOR::=PRF PA DEC|EXP FI|FI EXP FI PC LLA STAT_B LLC",
        "IFEL" to "IFEL::=PRIF PA EXPBOO PC LLA INST LLC CLOS",
        "CLOS" to "CLOS::=PRE LLA INST LLC|ε",
        "SOUT" to "SOUT::=ID CA ID CA ID PA EXP PC FI (+ extensión: System.out.println)"
    )

    /** Extensiones aceptadas por el analizador (ver KDoc de [GlcV7]). */
    val EXTENSIONS: List<String> = listOf(
        "+ CLA::=NW ID PA ARGS PC",
        "+ CLA::=PA EXP PC",
        "+ EXP::=PA TD PC EXP",
        "+ ARG::=EXP|CO EXP",
        "+ INST::=ACC FI|EXPU FI",
        "+ SOUT::=ID CA ID CA ID PA EXP PC FI",
        "+ EXPU::=VAL OPU|OPA VAL",
        "+ IFEL/WHILE/DO/FOR con cuerpo LLA CUERPO LLC"
    )

    fun production(rule: String): String? = PRODUCTIONS[rule]
}
