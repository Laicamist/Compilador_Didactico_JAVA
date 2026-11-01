package edu.itssg.compiler.core.parser

import edu.itssg.compiler.core.lexer.Token
import edu.itssg.compiler.core.security.SourceSpan

/**
 * Representación tipada del programa que el [Parser] construye a la par del Árbol Sintáctico.
 *
 * El Árbol Sintáctico ([SyntaxTreeNode]) es el producto didáctico (derivación de la GLC v7); este modelo es el que
 * consume el Analizador Semántico, para no tener que "re-leer" la derivación token por token.
 */
sealed interface Expr {
    val span: SourceSpan
}

/** `VAL` literal: VTE, VTD, VTF, VTC, VTS, VTB o `_NU`. */
data class LiteralExpr(val token: Token) : Expr {
    override val span: SourceSpan get() = token.span
}

/** `VAL ::= ID`: referencia a variable, parámetro, atributo o clase. */
data class NameExpr(val token: Token) : Expr {
    override val span: SourceSpan get() = token.span
}

/** `EXPC ::= EXP OP EXP | PA EXP OP EXP PC`. */
data class BinaryExpr(
    val left: Expr,
    val operator: Token,
    val right: Expr,
    val parenthesized: Boolean,
    override val span: SourceSpan
) : Expr

/** `EXPU ::= OPU VAL` (prefijo `++a`, `!a`, `-5`) o `VAL OPU` (postfijo `a++`). */
data class UnaryExpr(
    val operator: Token,
    val operand: Expr,
    val isPrefix: Boolean,
    override val span: SourceSpan
) : Expr

/** Extensión `PA TD PC EXP`: `(char)(b + 1)`. */
data class CastExpr(val typeToken: Token, val operand: Expr, override val span: SourceSpan) : Expr

/** Expresión entre paréntesis sin operador binario visible: `(x)`. */
data class GroupExpr(val inner: Expr, override val span: SourceSpan) : Expr

/** Extensión `NW ID PA ARGS PC`: `new Uno()`. */
data class NewExpr(val classToken: Token, val args: List<Expr>, override val span: SourceSpan) : Expr

/**
 * `OT ::= CA ID OT | CA ID PA ARGS PC OT`: acceso a atributo (`args == null`) o llamada a método.
 * Ej. `new Nueva().total`, `new Uno().cuatro('@')`, `"LOL".length()`, `Math.random()`.
 */
data class AccessExpr(
    val target: Expr,
    val member: Token,
    val args: List<Expr>?,
    override val span: SourceSpan
) : Expr

/** Llamada a un método de la propia clase sin objeto: `cuatro(5)`. */
data class CallExpr(val name: Token, val args: List<Expr>, override val span: SourceSpan) : Expr

/** Expresión que no se pudo derivar (el error ya está en la Tabla de Errores). */
data class ErrorExpr(override val span: SourceSpan) : Expr

// ------------------------------------------------------------------- Instrucciones

sealed interface Stmt {
    val span: SourceSpan
}

/** `DEC ::= TY ID FI | TY ID ODA EXP FI`. */
data class VarDeclStmt(val typeToken: Token, val name: Token, val init: Expr?, override val span: SourceSpan) : Stmt

/** `ASIG ::= ID ODA EXP FI`. */
data class AssignStmt(val target: Token, val value: Expr, override val span: SourceSpan) : Stmt

/** `OBJ ::= ID ID ODA NW ID PA ARGS PC FI`. */
data class ObjDeclStmt(
    val classToken: Token,
    val name: Token,
    val constructorToken: Token,
    val args: List<Expr>,
    override val span: SourceSpan
) : Stmt

/** `SOUT`: `System.out.println(EXP);`. */
data class SoutStmt(val printToken: Token, val argument: Expr?, override val span: SourceSpan) : Stmt

/** `ACC FI` / `EXPU FI`: llamada o incremento usado como instrucción. */
data class ExprStmt(val expr: Expr, override val span: SourceSpan) : Stmt

/**
 * Expresión de una instrucción que tuvo un error sintáctico ajeno a la expresión (ej. `int 4a = deTrabajo.length;`,
 * donde el ID inválido se descartó en el léxico). La instrucción no se ejecuta, pero su expresión sí se valida
 * semánticamente, porque las fases detectan errores de forma independiente (tabla de errores del ejemplo `Facilito`).
 */
data class CheckOnlyStmt(val expr: Expr, override val span: SourceSpan) : Stmt

/** `REE ::= RTN FI | RTN EXP FI`. */
data class ReturnStmt(val keyword: Token, val value: Expr?, override val span: SourceSpan) : Stmt

data class IfStmt(
    val condition: Expr,
    val thenBody: List<Stmt>,
    val elseBody: List<Stmt>?,
    override val span: SourceSpan
) : Stmt

data class WhileStmt(val condition: Expr, val body: List<Stmt>, override val span: SourceSpan) : Stmt

data class DoWhileStmt(val body: List<Stmt>, val condition: Expr, override val span: SourceSpan) : Stmt

data class ForStmt(
    val init: Stmt?,
    val condition: Expr?,
    val update: Expr?,
    val body: List<Stmt>,
    override val span: SourceSpan
) : Stmt

data class SwitchCase(val value: Expr?, val body: List<Stmt>, val hasBreak: Boolean)

data class SwitchStmt(val selector: Expr, val cases: List<SwitchCase>, override val span: SourceSpan) : Stmt

// ------------------------------------------------------------------- Declaraciones

/** `D_GLOB ::= MAO TY ID FI | MAO TY ID ODA EXP FI | OBJ`. */
data class FieldDecl(
    val modifier: Token?,
    val typeToken: Token,
    val name: Token,
    val init: Expr?,
    val objectDecl: ObjDeclStmt? = null,
    val span: SourceSpan
)

/** `PARAM ::= TY ID | CO TY ID`. */
data class ParamDecl(val typeToken: Token, val name: Token)

/** `METODO ::= MA TYPE ID PA PARAMS PC LLA CUERPO REE LLC`. */
data class MethodDecl(
    val modifier: Token?,
    val returnTypeToken: Token,
    val name: Token,
    val params: List<ParamDecl>,
    val body: List<Stmt>,
    val returnStmt: ReturnStmt?,
    val span: SourceSpan,
    val hadSyntaxErrors: Boolean = false
)

/** `MAIN ::= MA ST TM PMAIN PA STR COA COC ID PC LLA CUERPO LLC`. */
data class MainDecl(val mainToken: Token, val argsName: Token?, val body: List<Stmt>, val span: SourceSpan)

/** `CLASE ::= MA CL ID LLA CONTENT LLC` (los miembros conservan su orden de aparición). */
data class ClassDecl(
    val name: Token?,
    val fields: List<FieldDecl>,
    val methods: List<MethodDecl>,
    val main: MainDecl?,
    val span: SourceSpan
)

data class ImportDecl(val packageToken: Token?, val memberToken: Token?)

/** `JAVA ::= DPACKAGE IMPORTS CLASE`. */
data class ProgramAst(
    val packageName: Token?,
    val imports: List<ImportDecl>,
    val classDecl: ClassDecl?
)
