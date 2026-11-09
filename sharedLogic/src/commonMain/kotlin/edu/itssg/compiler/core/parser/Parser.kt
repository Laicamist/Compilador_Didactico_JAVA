package edu.itssg.compiler.core.parser

import edu.itssg.compiler.core.error.AcademicErrorTemplates
import edu.itssg.compiler.core.error.ErrorReporter
import edu.itssg.compiler.core.io.SecurityQuotaException
import edu.itssg.compiler.core.lexer.Token
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.core.lexer.TokenCategory.*
import edu.itssg.compiler.core.security.CompilerSecurityConfig
import edu.itssg.compiler.core.security.SecurityCheckResult
import edu.itssg.compiler.core.security.SecurityViolationCode
import edu.itssg.compiler.core.security.SourcePosition
import edu.itssg.compiler.core.security.SourceSpan

/**
 * Resultado de la fase de Análisis Sintáctico.
 *
 * @property syntaxTree Raíz del Árbol Sintáctico (`JAVA`), derivación de la GLC v7.
 * @property program Modelo tipado del programa para el Analizador Semántico.
 */
data class ParserResult(
    val syntaxTree: SyntaxTreeNode.NonTerminal,
    val hasSyntacticErrors: Boolean,
    val program: ProgramAst
)

/**
 * Analizador Sintáctico descendente recursivo
 * - Cada nodo No-Terminal del árbol lleva el nombre exacto de la regla (`JAVA`, `CLASE`, `D_GLOB`, `EXP`, `EXPC`...).
 * - Las reglas recursivas (`CONTENT`, `CUERPO`, `IMPORTS`, `ARGS`, `PARAMS`) se muestran "aplanadas": un nodo con
 *   todos sus elementos en orden, para que el árbol sea legible.
 * - Expresiones: `EXP → EXPC → EXP OP EXP` respetando la jerarquía de operadores de la U1/U2
 *   (nivel 1 `+ -`, nivel 2 `* /`, nivel 3 `.`, los paréntesis suben un nivel)
 *
 * ## Consideraciones de la GLC v7
 * - No acepta herencia ni implementación (`extends`, `implements`).
 * - Importación simple (`import a.B;`) y, como extensión, con varios niveles (`import java.util.Scanner;`,
 *   `import java.util.*;`).
 * - No hay regla para crear arreglos (`int[] a`, `new int[3]`, `args[0]`).
 * - No permite declaración de variables en modo lista (`int a, b;`).
 * - No permite métodos ni variables estáticos (excepto `main`).
 *
 * ## Modo Pánico
 * Cada declaración, instrucción o encabezado es una "unidad de recuperación": se reporta el PRIMER error de la
 * unidad (los siguientes son consecuencia del primero) y se sincroniza descartando tokens hasta `FI` (`;`, que se
 * consume) o `LLC` (`}`, que no se consume). Así la Tabla de Errores tiene un renglón por instrucción mal formada,
 * como en los ejercicios de la Unidad 1.
 */
class Parser(
    private val config: CompilerSecurityConfig = CompilerSecurityConfig()
) {
    private var nextNodeId: Int = 1
    private lateinit var cursor: SafeTokenCursor
    private lateinit var reporter: ErrorReporter

    /** `true` cuando la unidad de recuperación actual ya reportó un error (Modo Pánico activo). */
    private var unitFailed: Boolean = false

    /** `true` si hubo un error silenciado (posterior al primero de la unidad) desde la última vez que se reinició. */
    private var silentFailure: Boolean = false

    /** Paréntesis anidados abiertos en la expresión actual (cada nivel consume varias llamadas en la pila). */
    private var parenDepth: Int = 0

    private val maxParenDepth: Int get() = (config.maxAstDepth / 8).coerceAtLeast(8)

    fun parse(tokens: List<Token>, errorReporter: ErrorReporter): ParserResult {
        nextNodeId = 1
        unitFailed = false
        parenDepth = 0
        cursor = SafeTokenCursor(tokens = tokens, config = config)
        reporter = errorReporter
        val errorsBefore = errorReporter.errorCount

        var program = ProgramAst(packageName = null, imports = emptyList(), classDecl = null)
        val root = try {
            val (node, ast) = parseJava()
            program = ast
            node
        } catch (quotaEx: SecurityQuotaException) {
            errorReporter.reportSyntacticError(
                ruleName = "JAVA",
                offendingLexeme = cursor.peek().lexeme,
                justification = quotaEx.violation.message,
                span = cursor.peek().span
            )
            SyntaxTreeNode.NonTerminal(
                id = allocateId(),
                ruleName = "JAVA",
                children = emptyList(),
                span = cursor.peek().span,
                isRecoveredWithError = true
            )
        }
        return ParserResult(
            syntaxTree = root,
            hasSyntacticErrors = errorReporter.errorCount > errorsBefore,
            program = program
        )
    }

    /**
     * Resultado cuando el léxico rechazó la fuente completa (carácter nulo `\u0000` o tamaño mayor al permitido):
     * no hay tokens que derivar, así que no se reportan errores sintácticos "se esperaba MA… fin de archivo" que
     * solo serían consecuencia del rechazo. El error que explica el rechazo ya está en la Tabla de Errores.
     */
    fun rejectedSourceResult(): ParserResult {
        nextNodeId = 1
        val start = SourceSpan(SourcePosition.START, SourcePosition.START)
        return ParserResult(
            syntaxTree = SyntaxTreeNode.NonTerminal(
                id = allocateId(),
                ruleName = "JAVA",
                children = emptyList(),
                span = start,
                isRecoveredWithError = true
            ),
            hasSyntacticErrors = false,
            program = ProgramAst(packageName = null, imports = emptyList(), classDecl = null)
        )
    }

    // JAVA ::= DPACKAGE IMPORTS CLASE

    private fun parseJava(): Pair<SyntaxTreeNode.NonTerminal, ProgramAst> {
        var packageToken: Token? = null
        var imports: List<ImportDecl> = emptyList()
        var classDecl: ClassDecl? = null
        val node = rule("JAVA") {
            val children = ArrayList<SyntaxTreeNode>()
            val (pkgNode, pkg) = parseDPackage()
            packageToken = pkg
            children += pkgNode
            val (impNode, imps) = parseImports()
            imports = imps
            children += impNode
            val (claseNode, clase) = parseClase()
            classDecl = clase
            children += claseNode

            if (!cursor.isAtEnd) {
                unit {
                    syntaxError("JAVA", "fin de archivo (EOF)", cursor.peek())
                }
                while (!cursor.isAtEnd) cursor.advance()
            }
            children
        }
        return node to ProgramAst(packageToken, imports, classDecl)
    }

    /** `DPACKAGE ::= PCK ID FI`*/
    private fun parseDPackage(): Pair<SyntaxTreeNode.NonTerminal, Token?> {
        var packageId: Token? = null
        val node = rule("DPACKAGE") {
            val children = ArrayList<SyntaxTreeNode>()
            when {
                check(PCK) -> unit {
                    children += term(cursor.advance())
                    val id = expect(ID, "DPACKAGE")
                    packageId = (id as? SyntaxTreeNode.Terminal)?.token
                    children += id
                    if (check(CA)) {
                        consideration("DPACKAGE", "el nombre del paquete debe ser un solo ID", cursor.peek())
                        while (check(CA) || check(ID)) cursor.advance()
                    }
                    children += expectFi("DPACKAGE")
                }
                check(ID) && peekCat(1) == ID && peekCat(2) == FI -> unit {
                    syntaxError("DPACKAGE", "PCK", cursor.peek())
                    children += error("PCK", "DPACKAGE", cursor.peek().span)
                    repeat(3) { children += term(cursor.advance()) }
                }
                else -> children += epsilon("DPACKAGE")
            }
            children
        }
        return node to packageId
    }

    /**
     * `IMPORTS ::= IMPORTS IMPORT | ε` ; `IMPORT ::= IMP ID CA ID FI`, con la extensión `IMPORT ::= IMP ID CA ID
     * L_IMP FI`, `L_IMP ::= CA ID L_IMP | CA OPA(*) | ε` para `import java.util.Scanner;` e `import java.util.*;`.
     */
    private fun parseImports(): Pair<SyntaxTreeNode.NonTerminal, List<ImportDecl>> {
        val imports = ArrayList<ImportDecl>()
        val node = rule("IMPORTS") {
            val children = ArrayList<SyntaxTreeNode>()
            if (!check(IMP)) children += epsilon("IMPORTS")
            while (check(IMP)) {
                val start = cursor.currentIndex
                children += rule("IMPORT") {
                    val c = ArrayList<SyntaxTreeNode>()
                    unit {
                        c += term(cursor.advance())
                        val pkg = expect(ID, "IMPORT")
                        c += pkg
                        c += expect(CA, "IMPORT")
                        var member = if (check(OPA) && cursor.peek().lexeme == "*") term(cursor.advance())
                        else expect(ID, "IMPORT")
                        c += member
                        // Extensión: más niveles (java.util.Scanner) o comodín (java.util.*).
                        var wildcard = (member as? SyntaxTreeNode.Terminal)?.token?.lexeme == "*"
                        while (!wildcard && check(CA)) {
                            c += term(cursor.advance())
                            member = if (check(OPA) && cursor.peek().lexeme == "*") {
                                wildcard = true
                                term(cursor.advance())
                            } else {
                                expect(ID, "IMPORT")
                            }
                            c += member
                        }
                        c += expectFi("IMPORT")
                        imports += ImportDecl(
                            (pkg as? SyntaxTreeNode.Terminal)?.token,
                            (member as? SyntaxTreeNode.Terminal)?.token
                        )
                    }
                    c
                }
                cursor.enforceForwardProgress(start)
            }
            children
        }
        return node to imports
    }

    // CLASE ::= MA CL ID LLA CONTENT LLC

    private fun parseClase(): Pair<SyntaxTreeNode.NonTerminal, ClassDecl?> {
        var classDecl: ClassDecl? = null
        val node = rule("CLASE") {
            val children = ArrayList<SyntaxTreeNode>()
            var classToken: Token? = null
            header {
                children += expectOrSkip(MA, "CLASE")
                // Ej. U1 "public clase Pruebas {": "clase" es un ID donde se esperaba CL
                if (!check(CL) && check(ID) && peekCat(1) == ID) {
                    syntaxError("CLASE", "CL", cursor.peek())
                    children += error("CL", "CLASE", cursor.peek().span)
                    cursor.advance()
                } else {
                    children += expectOrSkip(CL, "CLASE")
                }
                val id = expect(ID, "CLASE")
                classToken = (id as? SyntaxTreeNode.Terminal)?.token
                children += id
                if (check(EXTS) || check(IMPL)) {
                    consideration("CLASE", "no se acepta herencia ni implementación (${cursor.peek().lexeme})", cursor.peek())
                    while (!cursor.isAtEnd && !check(LLA) && !check(FI) && !check(LLC)) cursor.advance()
                }
                children += expectOrSkip(LLA, "CLASE", maxSkip = 3)
            }

            val fields = ArrayList<FieldDecl>()
            val methods = ArrayList<MethodDecl>()
            var main: MainDecl? = null
            children += parseContent(fields, methods, onMain = { m ->
                if (main == null) main = m
            })

            children += if (check(LLC)) {
                term(cursor.advance())
            } else {
                unit { syntaxError("CLASE", "LLC", cursor.peek(), cursor.previous().span) }
                error("LLC", "CLASE", cursor.previous().span)
            }
            val spanEnd = cursor.previous().span.end
            val spanStart = classToken?.span?.start ?: cursor.previous().span.start
            classDecl = ClassDecl(classToken, fields, methods, main, SourceSpan(spanStart, spanEnd))
            children
        }
        return node to classDecl
    }

    /** `CONTENT ::= VARIOS CONTENT | ε` ; `VARIOS ::= MAIN | METODO | DECS_GLOB`. */
    private fun parseContent(
        fields: MutableList<FieldDecl>,
        methods: MutableList<MethodDecl>,
        onMain: (MainDecl) -> Unit
    ): SyntaxTreeNode.NonTerminal = rule("CONTENT") {
        val children = ArrayList<SyntaxTreeNode>()
        while (!cursor.isAtEnd && !check(LLC)) {
            val start = cursor.currentIndex
            val varios = rule("VARIOS") {
                val c = ArrayList<SyntaxTreeNode>()
                when {
                    isMainStart() -> {
                        val (n, m) = parseMain()
                        c += n
                        onMain(m)
                    }
                    isMethodStart() -> {
                        val (n, m) = parseMetodo()
                        c += n
                        if (m != null) methods += m
                    }
                    isObjStart() || isGlobalDeclStart() -> {
                        c += rule("DECS_GLOB") {
                            val (n, f) = parseDGlob()
                            if (f != null) fields += f
                            listOf(n)
                        }
                    }
                    else -> unit {
                        syntaxError("VARIOS", "MAIN, METODO o D_GLOB", cursor.peek())
                        c += error("VARIOS", "VARIOS", cursor.peek().span)
                        // Un renglón de `;` sobrantes (ej. "float toma=10/3.0;;;;;;;") es un solo error.
                        if (check(FI)) {
                            while (check(FI)) cursor.advance()
                            unitFailed = false
                        }
                    }
                }
                c
            }
            children += varios
            cursor.enforceForwardProgress(start)
        }
        if (children.isEmpty()) children += epsilon("CONTENT")
        children
    }

    private fun isMainStart(): Boolean =
        (check(MA) && peekCat(1) == ST && peekCat(2) == TM && peekCat(3) == PMAIN) ||
            (check(ST) && peekCat(1) == TM && peekCat(2) == PMAIN) ||
            (check(MA) && peekCat(1) == TM && peekCat(2) == PMAIN)

    /** `MA? ST? TYPE ID PA` */
    private fun isMethodStart(): Boolean {
        var i = 0
        if (peekCat(i) == MA) i++
        if (peekCat(i) == ST) i++
        val type = peekCat(i)
        if (type != TM && type != TD && type != STR && type != ID) return false
        i++
        if (peekCat(i) == COA && peekCat(i + 1) == COC) i += 2
        return (peekCat(i) == ID || peekCat(i) == PMAIN) && peekCat(i + 1) == PA
    }

    private fun isObjStart(): Boolean = check(ID) && peekCat(1) == ID && peekCat(2) == ODA && peekCat(3) == NW

    private fun isGlobalDeclStart(): Boolean {
        var i = 0
        if (peekCat(i) == MA) i++
        if (peekCat(i) == ST) i++
        if (peekCat(i) == TDD) i++
        return peekCat(i) == TD || peekCat(i) == STR || (peekCat(i) == ID && peekCat(i + 1) == ID) ||
            // Ej. U1 "int private c=9;": empieza como D_GLOB y falla al esperar el ID
            (i > 0 && peekCat(i) == ID)
    }
    // D_GLOB ::= MAO TY ID FI | MAO TY ID ODA EXP FI | OBJ

    private fun parseDGlob(): Pair<SyntaxTreeNode.NonTerminal, FieldDecl?> {
        var field: FieldDecl? = null
        val node = rule("D_GLOB") {
            val children = ArrayList<SyntaxTreeNode>()
            if (isObjStart()) {
                val (objNode, obj) = parseObj()
                children += objNode
                if (obj != null) {
                    field = FieldDecl(null, obj.classToken, obj.name, null, obj, obj.span)
                }
                return@rule children
            }
            unit {
                val startSpan = cursor.peek().span
                var modifier: Token? = null
                children += rule("MAO") {
                    if (check(MA)) {
                        modifier = cursor.peek()
                        listOf(term(cursor.advance()))
                    } else {
                        listOf(epsilon("MAO"))
                    }
                }
                if (check(ST)) {
                    consideration("D_GLOB", "no se permiten variables estáticas (static)", cursor.peek())
                    cursor.advance()
                }
                if (check(TDD)) cursor.advance() // final: constante, se acepta como modificador
                val (tyNode, tyToken) = parseTy("D_GLOB")
                children += tyNode
                skipArrayBrackets("D_GLOB")
                val idNode = expect(ID, "D_GLOB")
                children += idNode
                rejectListDeclaration("D_GLOB")
                var init: Expr? = null
                var initClean = true
                val headerOk = !unitFailed
                if (check(ODA)) {
                    children += term(cursor.advance())
                    val (expNode, expr, clean) = parseExpChecked()
                    children += expNode
                    init = expr
                    initClean = clean
                }
                children += expectFi("D_GLOB")
                val nameTok = (idNode as? SyntaxTreeNode.Terminal)?.token
                // Si solo falta el FI, la declaración se conserva para el análisis semántico.
                if (tyToken != null && nameTok != null && headerOk && initClean) {
                    field = FieldDecl(modifier, tyToken, nameTok, init, null,
                        SourceSpan(startSpan.start, cursor.previous().span.end))
                }
            }
            children
        }
        return node to field
    }

    /** `OBJ ::= ID ID ODA NW ID PA ARGS PC FI`. */
    private fun parseObj(): Pair<SyntaxTreeNode.NonTerminal, ObjDeclStmt?> {
        var obj: ObjDeclStmt? = null
        val node = rule("OBJ") {
            val children = ArrayList<SyntaxTreeNode>()
            unit {
                val startSpan = cursor.peek().span
                val classTok = cursor.advance()
                children += term(classTok)
                val nameNode = expect(ID, "OBJ")
                children += nameNode
                children += expect(ODA, "OBJ")
                children += expect(NW, "OBJ")
                val ctorNode = expect(ID, "OBJ")
                children += ctorNode
                children += expect(PA, "OBJ")
                val (argsNode, args) = parseArgs()
                children += argsNode
                children += expect(PC, "OBJ")
                children += expectFi("OBJ")
                val nameTok = (nameNode as? SyntaxTreeNode.Terminal)?.token
                val ctorTok = (ctorNode as? SyntaxTreeNode.Terminal)?.token
                if (nameTok != null && ctorTok != null) {
                    obj = ObjDeclStmt(classTok, nameTok, ctorTok, args,
                        SourceSpan(startSpan.start, cursor.previous().span.end))
                }
            }
            children
        }
        return node to obj
    }

    // MAIN y METODO

    /** `MAIN ::= MA ST TM PMAIN PA STR COA COC ID PC LLA CUERPO LLC`. */
    private fun parseMain(): Pair<SyntaxTreeNode.NonTerminal, MainDecl> {
        var mainDecl: MainDecl? = null
        val node = rule("MAIN") {
            val children = ArrayList<SyntaxTreeNode>()
            val startSpan = cursor.peek().span
            var mainTok = cursor.peek()
            var argsTok: Token? = null
            header {
                children += expectOrSkip(MA, "MAIN")
                children += expectOrSkip(ST, "MAIN")
                children += expectOrSkip(TM, "MAIN")
                val pm = expectOrSkip(PMAIN, "MAIN")
                (pm as? SyntaxTreeNode.Terminal)?.let { mainTok = it.token }
                children += pm
                children += expectOrSkip(PA, "MAIN")
                // Ej. U1 "main(int [] args)": se esperaba un STR y se encuentra un TD
                if (!check(STR) && (check(TD) || check(ID)) && peekCat(1) == COA) {
                    syntaxError("MAIN", "STR", cursor.peek())
                    children += error("STR", "MAIN", cursor.peek().span)
                    cursor.advance()
                } else {
                    children += expectOrSkip(STR, "MAIN")
                }
                children += expectOrSkip(COA, "MAIN")
                children += expectOrSkip(COC, "MAIN")
                val a = expectOrSkip(ID, "MAIN")
                argsTok = (a as? SyntaxTreeNode.Terminal)?.token
                children += a
                children += expectOrSkip(PC, "MAIN")
                children += expectOrSkip(LLA, "MAIN", maxSkip = 3)
            }
            val body = ArrayList<Stmt>()
            children += parseCuerpo(body, CuerpoContext.MAIN)
            if (check(RTN)) {
                unit {
                    syntaxError("MAIN", "LLC", cursor.peek())
                }
            }
            children += expectClosingBrace("MAIN")
            mainDecl = MainDecl(mainTok, argsTok, body, SourceSpan(startSpan.start, cursor.previous().span.end))
            children
        }
        return node to mainDecl!!
    }

    /** `METODO ::= MA TYPE ID PA PARAMS PC LLA CUERPO REE LLC`. */
    private fun parseMetodo(): Pair<SyntaxTreeNode.NonTerminal, MethodDecl?> {
        var method: MethodDecl? = null
        val node = rule("METODO") {
            val children = ArrayList<SyntaxTreeNode>()
            val startSpan = cursor.peek().span
            val errorsBefore = reporter.errorCount
            var modifier: Token? = null
            var typeTok: Token? = null
            var nameTok: Token? = null
            val params = ArrayList<ParamDecl>()
            header {
                if (check(MA)) {
                    modifier = cursor.peek()
                    children += term(cursor.advance())
                } else {
                    syntaxError("METODO", "MA", cursor.peek())
                    children += error("MA", "METODO", cursor.peek().span)
                }
                if (check(ST)) {
                    consideration("METODO", "no se permiten métodos estáticos (static)", cursor.peek())
                    cursor.advance()
                }
                children += rule("TYPE") {
                    if (check(TM)) {
                        typeTok = cursor.peek()
                        listOf(term(cursor.advance()))
                    } else {
                        val (ty, tok) = parseTy("TYPE")
                        typeTok = tok
                        listOf(ty)
                    }
                }
                skipArrayBrackets("METODO")
                val id = if (check(PMAIN)) term(cursor.advance()) else expect(ID, "METODO")
                nameTok = (id as? SyntaxTreeNode.Terminal)?.token
                children += id
                children += expect(PA, "METODO")
                children += parseParams(params)
                children += expectOrSkip(PC, "METODO", maxSkip = 2)
                children += expectOrSkip(LLA, "METODO", maxSkip = 2)
            }
            val body = ArrayList<Stmt>()
            children += parseCuerpo(body, CuerpoContext.METHOD)
            var ret: ReturnStmt? = null
            children += rule("REE") {
                if (check(RTN)) {
                    val (nodes, r) = parseReturnTail()
                    ret = r
                    nodes
                } else {
                    listOf(epsilon("REE"))
                }
            }
            if (ret != null && !check(LLC) && !cursor.isAtEnd && !check(MA)) {
                // Instrucciones después de REE: la GLC v7 ubica el return al final del método.
                unit { syntaxError("METODO", "LLC", cursor.peek()) }
                val extra = ArrayList<Stmt>()
                parseCuerpo(extra, CuerpoContext.METHOD)
            }
            children += expectClosingBrace("METODO")
            val tt = typeTok
            val nt = nameTok
            if (tt != null && nt != null) {
                method = MethodDecl(modifier, tt, nt, params, body, ret,
                    SourceSpan(startSpan.start, cursor.previous().span.end),
                    hadSyntaxErrors = reporter.errorCount > errorsBefore)
            }
            children
        }
        return node to method
    }

    /** `PARAMS ::= PARAM PARAMS | ε` ; `PARAM ::= TY ID | CO TY ID`. */
    private fun parseParams(out: MutableList<ParamDecl>): SyntaxTreeNode.NonTerminal = rule("PARAMS") {
        val children = ArrayList<SyntaxTreeNode>()
        if (check(PC) || !(check(TD) || check(STR) || check(ID))) {
            children += epsilon("PARAMS")
            return@rule children
        }
        var first = true
        while (!cursor.isAtEnd) {
            val start = cursor.currentIndex
            if (!first && !check(CO)) break
            children += rule("PARAM") {
                val c = ArrayList<SyntaxTreeNode>()
                if (!first) c += term(cursor.advance())
                val (ty, tyTok) = parseTy("PARAM")
                c += ty
                skipArrayBrackets("PARAM")
                val id = expect(ID, "PARAM")
                c += id
                val idTok = (id as? SyntaxTreeNode.Terminal)?.token
                if (tyTok != null && idTok != null) out += ParamDecl(tyTok, idTok)
                c
            }
            first = false
            if (unitFailed) break
            cursor.enforceForwardProgress(start)
        }
        children
    }

    /** `RTN FI | RTN EXP FI`. */
    private fun parseReturnTail(): Pair<List<SyntaxTreeNode>, ReturnStmt> {
        val nodes = ArrayList<SyntaxTreeNode>()
        val kw = cursor.peek()
        var value: Expr? = null
        unit {
            nodes += term(cursor.advance())
            if (!check(FI)) {
                val (e, expr) = parseExp()
                nodes += e
                value = expr
            }
            nodes += expectFi("REE")
        }
        return nodes to ReturnStmt(kw, value, SourceSpan(kw.span.start, cursor.previous().span.end))
    }

    // CUERPO ::= INST CUERPO | ε
    // INST ::= DEC | STAT_B | ASIG | ACC | OBJ

    private enum class CuerpoContext { MAIN, METHOD, BLOCK, CASE }

    private fun parseCuerpo(out: MutableList<Stmt>, context: CuerpoContext): SyntaxTreeNode.NonTerminal =
        rule("CUERPO") {
            val children = ArrayList<SyntaxTreeNode>()
            while (!cursor.isAtEnd) {
                if (check(LLC)) break
                if (check(RTN) && (context == CuerpoContext.METHOD || context == CuerpoContext.MAIN)) break
                if (context == CuerpoContext.CASE && (check(CS) || check(DF) || check(BR))) break
                // Un modificador de acceso indica que falta la LLC del método/main anterior.
                if (check(MA)) break
                val start = cursor.currentIndex
                val (instNode, stmt) = parseInst(context)
                children += instNode
                if (stmt != null) out += stmt
                cursor.enforceForwardProgress(start)
            }
            if (children.isEmpty()) children += epsilon("CUERPO")
            children
        }

    private fun parseInst(context: CuerpoContext): Pair<SyntaxTreeNode.NonTerminal, Stmt?> {
        var stmt: Stmt? = null
        val node = rule("INST") {
            val c = ArrayList<SyntaxTreeNode>()
            val cat = cursor.peek().category
            when {
                cat == TD || cat == STR || (cat == ID && peekCat(1) == ID && peekCat(2) != ODA) ||
                    (cat == ID && peekCat(1) == ID && peekCat(2) == ODA && peekCat(3) != NW) ||
                    (cat == TD && peekCat(1) == COA) -> {
                    val (n, s) = parseDec()
                    c += n
                    stmt = s
                }
                cat == ID && isCompoundAssignStart() -> {
                    val (n, s) = parseCompoundAsig()
                    c += n
                    stmt = s
                }
                cat == ID && isObjStart() -> {
                    val (n, s) = parseObj()
                    c += n
                    stmt = s
                }
                cat == ID && peekCat(1) == ODA -> {
                    val (n, s) = parseAsig()
                    c += n
                    stmt = s
                }
                cat == ID && isSoutStart() -> {
                    val (n, s) = parseStatB { parseSout() }
                    c += n
                    stmt = s
                }
                cat in setOf(PRW, PRDO, PRIF, PRSW, PRF) -> {
                    val (n, s) = parseStatB { parseControl() }
                    c += n
                    stmt = s
                }
                cat == ID || cat == VTS || cat == NW || cat == PA || cat == INC || cat == DEC -> {
                    // INST ::= ACC (extensión: termina en FI) | EXPU FI
                    unit {
                        val startSpan = cursor.peek().span
                        val (expNode, expr, clean) = parseExpChecked()
                        c += expNode.children.firstOrNull() ?: expNode
                        c += expectFi("INST")
                        if (expr !is AccessExpr && expr !is CallExpr && expr !is UnaryExpr && !unitFailed) {
                            syntaxError("INST", "DEC, ASIG, ACC u OBJ", cursor.previous())
                        }
                        // Una expresión incompleta (ej. `x + ;`) no se analiza ni genera código intermedio.
                        if (clean) stmt = ExprStmt(expr, SourceSpan(startSpan.start, cursor.previous().span.end))
                    }
                }
                cat == RTN -> unit {
                    // return dentro de un bloque (if/while): se acepta como REE del bloque
                    val (nodes, r) = parseReturnTail()
                    c += SyntaxTreeNode.NonTerminal(allocateId(), "REE", nodes, computeSpan(nodes))
                    stmt = r
                }
                else -> unit {
                    syntaxError("INST", "DEC, STAT_B, ASIG, ACC u OBJ", cursor.peek())
                    c += error("INST", "INST", cursor.peek().span)
                }
            }
            c
        }
        return node to stmt
    }

    /** `STAT_B ::= STAT_B STATMNT | ε` → envuelve un `STATMNT`. */
    private inline fun parseStatB(
        crossinline body: () -> Pair<SyntaxTreeNode.NonTerminal, Stmt?>
    ): Pair<SyntaxTreeNode.NonTerminal, Stmt?> {
        var stmt: Stmt? = null
        val node = rule("STAT_B") {
            listOf(rule("STATMNT") {
                val (n, s) = body()
                stmt = s
                listOf(n)
            })
        }
        return node to stmt
    }

    /** `DEC ::= TY ID FI | TY ID ODA EXP FI`. */
    private fun parseDec(): Pair<SyntaxTreeNode.NonTerminal, Stmt?> {
        var stmt: Stmt? = null
        val node = rule("DEC") {
            val children = ArrayList<SyntaxTreeNode>()
            unit {
                val startSpan = cursor.peek().span
                val (ty, tyTok) = parseTy("DEC")
                children += ty
                skipArrayBrackets("DEC")
                val idNode = expect(ID, "DEC")
                children += idNode
                rejectListDeclaration("DEC")
                var init: Expr? = null
                var initClean = true
                val headerOk = !unitFailed
                if (check(ODA)) {
                    children += term(cursor.advance())
                    val (e, expr, clean) = parseExpChecked()
                    children += e
                    init = expr
                    initClean = clean && !continuesExpression()
                }
                children += expectFi("DEC")
                val nameTok = (idNode as? SyntaxTreeNode.Terminal)?.token
                val span = SourceSpan(startSpan.start, cursor.previous().span.end)
                stmt = when {
                    tyTok != null && nameTok != null && headerOk && initClean -> VarDeclStmt(tyTok, nameTok, init, span)
                    // Valor incompleto (`int y = a > 2 ? 1 : 0;`, `int x = 5 + ;`): el ID queda declarado con valor
                    // desconocido, sin revisar ni representar la parte que sí se derivó (evita errores en cascada).
                    tyTok != null && nameTok != null && headerOk && init != null ->
                        VarDeclStmt(tyTok, nameTok, ErrorExpr(init.span), span)
                    init != null && initClean -> CheckOnlyStmt(init, span)
                    else -> null
                }
            }
            children
        }
        return node to stmt
    }

    /** `ASIG ::= ID ODA EXP FI`. */
    private fun parseAsig(): Pair<SyntaxTreeNode.NonTerminal, Stmt?> {
        var stmt: Stmt? = null
        val node = rule("ASIG") {
            val children = ArrayList<SyntaxTreeNode>()
            unit {
                val target = cursor.advance()
                children += term(target)
                children += expect(ODA, "ASIG")
                val headerOk = !unitFailed
                val (e, expr, parsedClean) = parseExpChecked()
                val clean = parsedClean && !continuesExpression()
                children += e
                children += expectFi("ASIG")
                val span = SourceSpan(target.span.start, cursor.previous().span.end)
                stmt = when {
                    headerOk && clean -> AssignStmt(target, expr, span)
                    // Valor incompleto: desde aquí el valor del ID es desconocido (?) en lugar del anterior.
                    headerOk -> AssignStmt(target, ErrorExpr(expr.span), span)
                    clean -> CheckOnlyStmt(expr, span)
                    else -> null
                }
            }
            children
        }
        return node to stmt
    }

    /**
     * `true` si después de la expresión sigue un token que la continúa pero que la GLC v7 no deriva (ternario `?`,
     * `:`, `=`, `.`, `[`…): la expresión quedó truncada y su valor no es el que escribió el alumno. Un token que
     * puede iniciar otra instrucción (`int`, un ID, `}`…) solo indica que falta el `;`.
     */
    private fun continuesExpression(): Boolean = cursor.peek().category in EXPRESSION_CONTINUATION

    private fun isCompoundAssignStart(): Boolean =
        check(ID) && peekCat(1) == OPA && peekCat(2) == ODA && isCompoundOperator(cursor.peekAhead(1), cursor.peekAhead(2))

    /** `+` `=` escritos juntos (`+=`): LstTokens no tiene ese token, el léxico los separa en OPA y ODA. */
    private fun isCompoundOperator(op: Token, eq: Token): Boolean =
        op.category == OPA && eq.category == ODA && eq.span.start.offset == op.span.start.offset + op.lexeme.length

    /**
     * Asignación compuesta de Java (`x += 4;`, `x -= 1;`, `x *= 2;`, `x /= 2;`, `x %= 3;`): no existe en la GLC v7.
     * Se reporta con un mensaje claro y el ID queda con valor desconocido; la expresión de la derecha se deriva
     * para el árbol pero no se analiza.
     */
    private fun parseCompoundAsig(): Pair<SyntaxTreeNode.NonTerminal, Stmt?> {
        var stmt: Stmt? = null
        val node = rule("ASIG") {
            val children = ArrayList<SyntaxTreeNode>()
            unit {
                val target = cursor.advance()
                children += term(target)
                val op = cursor.advance()
                val eq = cursor.advance()
                children += error("ODA", "ASIG", SourceSpan(op.span.start, eq.span.end))
                compoundAssignmentError(op, target.lexeme, SourceSpan(op.span.start, eq.span.end))
                val (e, _, _) = parseExpChecked()
                children += e
                children += expectFi("ASIG")
                stmt = AssignStmt(target, ErrorExpr(SourceSpan(op.span.start, cursor.previous().span.end)),
                    SourceSpan(target.span.start, cursor.previous().span.end))
            }
            children
        }
        return node to stmt
    }

    /** Reporta [op]`=`; con [panic] activa el modo pánico de la unidad (se sincroniza hasta `;`). */
    private fun compoundAssignmentError(op: Token, target: String?, span: SourceSpan, panic: Boolean = true) {
        if (unitFailed) {
            silentFailure = true
            return
        }
        if (panic) unitFailed = true
        reporter.reportSyntacticError(
            ruleName = "ASIG",
            offendingLexeme = "${op.lexeme}=",
            justification = AcademicErrorTemplates.compoundAssignmentNotInGlc(op.lexeme, target, GlcV7.production("ASIG")),
            span = span
        )
    }

    private fun isSoutStart(): Boolean =
        check(ID) && cursor.peek().lexeme == "System" && peekCat(1) == CA &&
            peekCat(2) == ID && cursor.peekAhead(2).lexeme == "out"

    /** `SOUT ::= ID CA ID CA ID PA EXP PC FI` (`System.out.println(...)`). */
    private fun parseSout(): Pair<SyntaxTreeNode.NonTerminal, Stmt?> {
        var stmt: Stmt? = null
        val node = rule("SOUT") {
            val children = ArrayList<SyntaxTreeNode>()
            unit {
                val startSpan = cursor.peek().span
                children += term(cursor.advance()) // System
                children += expect(CA, "SOUT")
                children += expect(ID, "SOUT") // out
                children += expect(CA, "SOUT")
                val printNode = expect(ID, "SOUT") // println / print
                children += printNode
                children += expect(PA, "SOUT")
                var arg: Expr? = null
                if (!check(PC)) {
                    val (e, expr) = parseExp()
                    children += e
                    arg = expr
                }
                if (check(COA)) {
                    consideration("SOUT", "no existe regla gramatical para usar arreglos (${cursor.peek().lexeme})", cursor.peek())
                }
                children += expect(PC, "SOUT")
                children += expectFi("SOUT")
                val printTok = (printNode as? SyntaxTreeNode.Terminal)?.token ?: startSpan.let { cursor.previous() }
                stmt = SoutStmt(printTok, arg, SourceSpan(startSpan.start, cursor.previous().span.end))
            }
            children
        }
        return node to stmt
    }

    //Estructuras de control

    private fun parseControl(): Pair<SyntaxTreeNode.NonTerminal, Stmt?> = when (cursor.peek().category) {
        PRIF -> parseIfEl()
        PRW -> parseWhile()
        PRDO -> parseDo()
        PRF -> parseFor()
        else -> parseSwitch()
    }

    /** Cuerpo de bloque: `LLA CUERPO LLC` o una sola instrucción. */
    private fun parseBlock(out: MutableList<Stmt>, ownerRule: String): List<SyntaxTreeNode> {
        val nodes = ArrayList<SyntaxTreeNode>()
        if (check(LLA)) {
            nodes += term(cursor.advance())
            nodes += parseCuerpo(out, CuerpoContext.BLOCK)
            if (check(RTN)) {
                val (inst, s) = parseInst(CuerpoContext.BLOCK)
                nodes += inst
                if (s != null) out += s
            }
            nodes += expectClosingBrace(ownerRule)
        } else {
            val (inst, s) = parseInst(CuerpoContext.BLOCK)
            nodes += inst
            if (s != null) out += s
        }
        return nodes
    }

    private fun parseCondition(rule: String, children: MutableList<SyntaxTreeNode>): Expr {
        var cond: Expr = ErrorExpr(cursor.peek().span)
        unit {
            children += expect(PA, rule)
            val (e, expr) = parseExp()
            children += if (rule == "IFEL") wrap("EXPBOO", listOf(e)) else e
            cond = expr
            children += expect(PC, rule)
        }
        return cond
    }

    /** `IFEL ::= PRIF PA EXPBOO PC LLA INST LLC CLOS` ; `CLOS ::= PRE LLA INST LLC | ε`. */
    private fun parseIfEl(): Pair<SyntaxTreeNode.NonTerminal, Stmt?> {
        var stmt: Stmt? = null
        val node = rule("IFEL") {
            val children = ArrayList<SyntaxTreeNode>()
            val start = cursor.peek().span
            children += term(cursor.advance())
            val cond = parseCondition("IFEL", children)
            val thenBody = ArrayList<Stmt>()
            children += parseBlock(thenBody, "IFEL")
            var elseBody: List<Stmt>? = null
            children += rule("CLOS") {
                if (check(PRE)) {
                    val c = ArrayList<SyntaxTreeNode>()
                    c += term(cursor.advance())
                    val eb = ArrayList<Stmt>()
                    if (check(PRIF)) {
                        val (n, s) = parseIfEl()
                        c += n
                        if (s != null) eb += s
                    } else {
                        c += parseBlock(eb, "CLOS")
                    }
                    elseBody = eb
                    c
                } else {
                    listOf(epsilon("CLOS"))
                }
            }
            stmt = IfStmt(cond, thenBody, elseBody, SourceSpan(start.start, cursor.previous().span.end))
            children
        }
        return node to stmt
    }

    /** `WHILE ::= PRW PA EXP PC STAT_B`. */
    private fun parseWhile(): Pair<SyntaxTreeNode.NonTerminal, Stmt?> {
        var stmt: Stmt? = null
        val node = rule("WHILE") {
            val children = ArrayList<SyntaxTreeNode>()
            val start = cursor.peek().span
            children += term(cursor.advance())
            val cond = parseCondition("WHILE", children)
            val body = ArrayList<Stmt>()
            children += parseBlock(body, "WHILE")
            stmt = WhileStmt(cond, body, SourceSpan(start.start, cursor.previous().span.end))
            children
        }
        return node to stmt
    }

    /** `DO ::= PRDO STAT_B PRW PA EXP PC FI`. */
    private fun parseDo(): Pair<SyntaxTreeNode.NonTerminal, Stmt?> {
        var stmt: Stmt? = null
        val node = rule("DO") {
            val children = ArrayList<SyntaxTreeNode>()
            val start = cursor.peek().span
            children += term(cursor.advance())
            val body = ArrayList<Stmt>()
            children += parseBlock(body, "DO")
            var cond: Expr = ErrorExpr(cursor.peek().span)
            unit {
                children += expect(PRW, "DO")
                cond = parseCondition("DO", children)
                children += expectFi("DO")
            }
            stmt = DoWhileStmt(body, cond, SourceSpan(start.start, cursor.previous().span.end))
            children
        }
        return node to stmt
    }

    /** `FOR ::= PRF PA DEC|EXP FI|FI EXP FI PC LLA STAT_B LLC` (regla morada: se interpreta como el `for` de Java). */
    private fun parseFor(): Pair<SyntaxTreeNode.NonTerminal, Stmt?> {
        var stmt: Stmt? = null
        val node = rule("FOR") {
            val children = ArrayList<SyntaxTreeNode>()
            val start = cursor.peek().span
            children += term(cursor.advance())
            var init: Stmt? = null
            var cond: Expr? = null
            var update: Expr? = null
            unit {
                children += expect(PA, "FOR")
                when {
                    check(TD) || check(STR) -> {
                        val (n, s) = parseDec()
                        children += n
                        init = s
                    }
                    check(ID) && peekCat(1) == ODA -> {
                        val (n, s) = parseAsig()
                        children += n
                        init = s
                    }
                    else -> children += expectFi("FOR")
                }
                if (!check(FI)) {
                    val (e, expr) = parseExp()
                    children += e
                    cond = expr
                }
                children += expectFi("FOR")
                if (isCompoundAssignStart()) {
                    // `i += 2` como actualización: se reporta, pero el encabezado sigue (sin modo pánico) para no
                    // descartar el cuerpo del for y desacomodar las llaves.
                    val target = cursor.advance()
                    val op = cursor.advance()
                    val eq = cursor.advance()
                    val opSpan = SourceSpan(op.span.start, eq.span.end)
                    children += term(target)
                    children += error("ODA", "FOR", opSpan)
                    compoundAssignmentError(op, target.lexeme, opSpan, panic = false)
                    val (e, _) = parseExp()
                    children += e
                } else if (!check(PC)) {
                    val (e, expr) = parseExp()
                    children += e
                    update = expr
                }
                children += expect(PC, "FOR")
            }
            val body = ArrayList<Stmt>()
            children += parseBlock(body, "FOR")
            stmt = ForStmt(init, cond, update, body, SourceSpan(start.start, cursor.previous().span.end))
            children
        }
        return node to stmt
    }

    /** `SWITCH ::= PRSW PA VAL PC LLA L_CASES C_DEF LLC`. */
    private fun parseSwitch(): Pair<SyntaxTreeNode.NonTerminal, Stmt?> {
        var stmt: Stmt? = null
        val node = rule("SWITCH") {
            val children = ArrayList<SyntaxTreeNode>()
            val start = cursor.peek().span
            children += term(cursor.advance())
            var selector: Expr = ErrorExpr(cursor.peek().span)
            unit {
                children += expect(PA, "SWITCH")
                val (e, expr) = parseExp()
                children += e
                selector = expr
                children += expect(PC, "SWITCH")
                children += expectOrSkip(LLA, "SWITCH", maxSkip = 2)
            }
            val cases = ArrayList<SwitchCase>()
            children += rule("L_CASES") {
                val c = ArrayList<SyntaxTreeNode>()
                while (check(CS)) {
                    val s = cursor.currentIndex
                    c += rule("CASE") {
                        val cc = ArrayList<SyntaxTreeNode>()
                        var value: Expr? = null
                        unit {
                            cc += term(cursor.advance())
                            val (e, expr) = parseUnary()
                            cc += e
                            value = expr
                            cc += expect(DOP, "CASE")
                        }
                        val body = ArrayList<Stmt>()
                        cc += parseCuerpo(body, CuerpoContext.CASE)
                        val (brk, hasBreak) = parseBreak()
                        cc += brk
                        cases += SwitchCase(value, body, hasBreak)
                        cc
                    }
                    cursor.enforceForwardProgress(s)
                }
                if (c.isEmpty()) c += epsilon("L_CASES")
                c
            }
            children += rule("C_DEF") {
                if (check(DF)) {
                    val cc = ArrayList<SyntaxTreeNode>()
                    cc += term(cursor.advance())
                    unit { cc += expect(DOP, "C_DEF") }
                    val body = ArrayList<Stmt>()
                    cc += parseCuerpo(body, CuerpoContext.CASE)
                    val (brk, hasBreak) = parseBreak()
                    cc += brk
                    cases += SwitchCase(null, body, hasBreak)
                    cc
                } else {
                    listOf(epsilon("C_DEF"))
                }
            }
            children += expectClosingBrace("SWITCH")
            stmt = SwitchStmt(selector, cases, SourceSpan(start.start, cursor.previous().span.end))
            children
        }
        return node to stmt
    }

    /** `BREAK ::= BR FI | ε`. */
    private fun parseBreak(): Pair<SyntaxTreeNode.NonTerminal, Boolean> {
        var has = false
        val node = rule("BREAK") {
            if (check(BR)) {
                has = true
                val c = ArrayList<SyntaxTreeNode>()
                unit {
                    c += term(cursor.advance())
                    c += expectFi("BREAK")
                }
                c
            } else {
                listOf(epsilon("BREAK"))
            }
        }
        return node to has
    }

    // EXPRESIONES: EXP ::= EXPC | VAL | ACC ; EXPC ::= EXP OP EXP | PA EXP OP EXP PC | EXPU

    /** Jerarquía (mayor número = mayor prioridad). Nivel 1 `+ -`, nivel 2 `* /` de las láminas, más lógicos/relacionales. */
    private fun precedenceOf(token: Token): Int = when (token.category) {
        OPL -> if (token.lexeme == "|" || token.lexeme == "||") 1 else 2
        XOR -> 2
        OPR -> if (token.lexeme == "==" || token.lexeme == "!=") 3 else 4
        OPA -> if (token.lexeme == "+" || token.lexeme == "-") 5 else 6
        else -> -1
    }

    private fun parseExp(): Pair<SyntaxTreeNode.NonTerminal, Expr> = parseBinary(1)

    /** Deriva una expresión e indica si quedó completa (sin errores, ni reportados ni silenciados). */
    private fun parseExpChecked(): Triple<SyntaxTreeNode.NonTerminal, Expr, Boolean> {
        val failedBefore = unitFailed
        silentFailure = false
        val (node, expr) = parseExp()
        val clean = !silentFailure && unitFailed == failedBefore && expr !is ErrorExpr
        return Triple(node, expr, clean)
    }

    private fun parseBinary(minPrecedence: Int): Pair<SyntaxTreeNode.NonTerminal, Expr> {
        cursor.enterRule()
        try {
            return parseBinaryGuarded(minPrecedence)
        } finally {
            cursor.exitRule()
        }
    }

    private fun parseBinaryGuarded(minPrecedence: Int): Pair<SyntaxTreeNode.NonTerminal, Expr> {
        var (leftNode, leftExpr) = parseUnary()
        var chainLength = 0
        while (true) {
            val op = cursor.peek()
            val prec = precedenceOf(op)
            if (prec < minPrecedence) break
            // Una cadena `a+b+c+...` produce un árbol tan profundo como términos tiene: se limita igual que el anidamiento.
            if (++chainLength > config.maxAstDepth) {
                throw SecurityQuotaException(
                    SecurityCheckResult.Violation(
                        code = SecurityViolationCode.AST_DEPTH_EXCEEDED,
                        message = "La expresión tiene más de ${config.maxAstDepth} operaciones encadenadas; se excede la profundidad segura del árbol.",
                        position = op.span.start
                    )
                )
            }
            cursor.advance()
            val opNode = wrap("OP", listOf(term(op)))
            val (rightNode, rightExpr) = parseBinary(prec + 1)
            val expc = wrap("EXPC", listOf(leftNode, opNode, rightNode))
            leftNode = wrap("EXP", listOf(expc))
            leftExpr = BinaryExpr(leftExpr, op, rightExpr, false, SourceSpan(leftExpr.span.start, rightExpr.span.end))
        }
        return leftNode to leftExpr
    }

    /** `EXPU ::= OPU VAL` (más las extensiones `VAL OPU` y signo `OPA VAL`), o un primario. */
    private fun parseUnary(): Pair<SyntaxTreeNode.NonTerminal, Expr> {
        cursor.enterRule()
        try {
            return parseUnaryGuarded()
        } finally {
            cursor.exitRule()
        }
    }

    private fun parseUnaryGuarded(): Pair<SyntaxTreeNode.NonTerminal, Expr> {
        val tok = cursor.peek()
        if (tok.category == INC || tok.category == DEC || tok.category == OPB || tok.category == OPN ||
            (tok.category == OPA && (tok.lexeme == "-" || tok.lexeme == "+"))
        ) {
            cursor.advance()
            val opNode = if (tok.category == OPA) term(tok) else wrap("OPU", listOf(term(tok)))
            val (operandNode, operand) = parseUnary()
            val operandChild = operandNode.children.singleOrNull() ?: operandNode
            val expu = wrap("EXPU", listOf(opNode, operandChild))
            return wrap("EXP", listOf(wrap("EXPC", listOf(expu)))) to
                UnaryExpr(tok, operand, true, SourceSpan(tok.span.start, operand.span.end))
        }
        val (primaryNode, primary) = parsePrimary()
        if ((check(INC) || check(DEC)) && primary is NameExpr) {
            val op = cursor.advance()
            val operandChild = primaryNode.children.singleOrNull() ?: primaryNode
            val expu = wrap("EXPU", listOf(operandChild, wrap("OPU", listOf(term(op)))))
            return wrap("EXP", listOf(wrap("EXPC", listOf(expu)))) to
                UnaryExpr(op, primary, false, SourceSpan(primary.span.start, op.span.end))
        }
        return primaryNode to primary
    }

    private fun isLiteral(category: TokenCategory): Boolean =
        category == VTE || category == VTD || category == VTF || category == VTC ||
            category == VTS || category == VTB || category == _NU

    private fun startsOperand(category: TokenCategory): Boolean =
        isLiteral(category) || category == ID || category == PA || category == NW ||
            category == INC || category == DEC || category == OPN || category == OPB || category == OPA

    private fun parsePrimary(): Pair<SyntaxTreeNode.NonTerminal, Expr> {
        val tok = cursor.peek()
        if (tok.category == PA) {
            if (parenDepth >= maxParenDepth) {
                throw SecurityQuotaException(
                    SecurityCheckResult.Violation(
                        code = SecurityViolationCode.AST_DEPTH_EXCEEDED,
                        message = "La expresión tiene más de $maxParenDepth paréntesis anidados; se excede la profundidad segura del árbol.",
                        position = tok.span.start
                    )
                )
            }
            parenDepth++
            try {
                return parsePrimaryGuarded(tok)
            } finally {
                parenDepth--
            }
        }
        return parsePrimaryGuarded(tok)
    }

    private fun parsePrimaryGuarded(tok: Token): Pair<SyntaxTreeNode.NonTerminal, Expr> {
        return when {
            // Extensión: conversión `(char)(...)`
            tok.category == PA && peekCat(1) == TD && peekCat(2) == PC && startsOperand(peekCat(3)) -> {
                val pa = cursor.advance()
                val td = cursor.advance()
                val pc = cursor.advance()
                val (operandNode, operand) = parseUnary()
                wrap("EXP", listOf(term(pa), term(td), term(pc), operandNode)) to
                    CastExpr(td, operand, SourceSpan(pa.span.start, operand.span.end))
            }
            tok.category == PA -> {
                val pa = cursor.advance()
                val (innerNode, inner) = parseExp()
                val pc = expect(PC, "EXPC")
                val endSpan = cursor.previous().span.end
                val innerExpc = innerNode.children.singleOrNull() as? SyntaxTreeNode.NonTerminal
                val (groupNode, groupExpr) = if (innerExpc?.ruleName == "EXPC" && inner is BinaryExpr) {
                    // EXPC ::= PA EXP OP EXP PC
                    wrap("EXPC", listOf(term(pa)) + innerExpc.children + pc) to
                        inner.copy(parenthesized = true, span = SourceSpan(pa.span.start, endSpan))
                } else {
                    wrap("EXP", listOf(term(pa), innerNode, pc)) to GroupExpr(inner, SourceSpan(pa.span.start, endSpan))
                }
                if (check(CA)) {
                    val cla = wrap("CLA", listOf(groupNode))
                    parseAccessTail(cla, groupExpr)
                } else {
                    (if (groupNode.ruleName == "EXP") groupNode else wrap("EXP", listOf(groupNode))) to groupExpr
                }
            }
            isLiteral(tok.category) -> {
                cursor.advance()
                if (tok.category == VTS && check(CA)) {
                    parseAccessTail(wrap("CLA", listOf(term(tok))), LiteralExpr(tok))
                } else {
                    wrap("EXP", listOf(wrap("VAL", listOf(term(tok))))) to LiteralExpr(tok)
                }
            }
            tok.category == ID -> {
                cursor.advance()
                when {
                    check(CA) -> parseAccessTail(wrap("CLA", listOf(term(tok))), NameExpr(tok))
                    check(PA) -> {
                        // Llamada a un método de la propia clase: ACC → CLA PA ARGS PC
                        val pa = cursor.advance()
                        val (argsNode, args) = parseArgs()
                        val pc = expect(PC, "OT")
                        val acc = wrap("ACC", listOf(wrap("CLA", listOf(term(tok))), term(pa), argsNode, pc))
                        val call = CallExpr(tok, args, SourceSpan(tok.span.start, cursor.previous().span.end))
                        if (check(CA)) parseAccessTail(wrap("CLA", listOf(acc)), call) else wrap("EXP", listOf(acc)) to call
                    }
                    check(COA) -> {
                        consideration("VAL", "no existe regla gramatical para usar arreglos (${tok.lexeme}[...])", cursor.peek())
                        while (!cursor.isAtEnd && !check(COC) && !check(FI)) cursor.advance()
                        if (check(COC)) cursor.advance()
                        wrap("EXP", listOf(wrap("VAL", listOf(term(tok))))) to ErrorExpr(tok.span)
                    }
                    else -> wrap("EXP", listOf(wrap("VAL", listOf(term(tok))))) to NameExpr(tok)
                }
            }
            tok.category == STR && peekCat(1) == CA -> {
                // Extensión CLA ::= STR: método estático de la clase String (`String.valueOf(5)`)
                cursor.advance()
                parseAccessTail(wrap("CLA", listOf(term(tok))), NameExpr(tok))
            }
            tok.category == NW -> {
                // Extensión CLA ::= NW ID PA ARGS PC
                val nw = cursor.advance()
                val cls = expect(ID, "CLA")
                if (check(COA)) {
                    consideration("CLA", "no existe regla gramatical para la creación de arreglos", cursor.peek())
                }
                val pa = expect(PA, "CLA")
                val (argsNode, args) = parseArgs()
                val pc = expect(PC, "CLA")
                val clsTok = (cls as? SyntaxTreeNode.Terminal)?.token ?: nw
                val newExpr = NewExpr(clsTok, args, SourceSpan(nw.span.start, cursor.previous().span.end))
                val cla = wrap("CLA", listOf(term(nw), cls, pa, argsNode, pc))
                if (check(CA)) parseAccessTail(cla, newExpr) else wrap("EXP", listOf(wrap("ACC", listOf(cla)))) to newExpr
            }
            else -> {
                val previous = cursor.previous()
                if (isCompoundOperator(previous, tok)) {
                    // `i += 2` en otra posición (ej. la actualización de un for): mismo mensaje que la instrucción.
                    compoundAssignmentError(previous, null, SourceSpan(previous.span.start, tok.span.end))
                } else {
                    syntaxError("EXP", "VAL, ACC o EXPC", tok)
                }
                wrap("EXP", listOf(error("EXP", "EXP", tok.span))) to ErrorExpr(tok.span)
            }
        }
    }

    /** `ACC ::= CLA OT` ; `OT ::= CA ID OT | CA ID PA ARGS PC OT | ε`. */
    private fun parseAccessTail(cla: SyntaxTreeNode.NonTerminal, target: Expr): Pair<SyntaxTreeNode.NonTerminal, Expr> {
        val segments = ArrayList<List<SyntaxTreeNode>>()
        var expr = target
        while (check(CA)) {
            val start = cursor.currentIndex
            val seg = ArrayList<SyntaxTreeNode>()
            seg += term(cursor.advance())
            val memberNode = expect(ID, "OT")
            seg += memberNode
            val member = (memberNode as? SyntaxTreeNode.Terminal)?.token
            var args: List<Expr>? = null
            if (check(PA)) {
                seg += term(cursor.advance())
                val (argsNode, a) = parseArgs()
                seg += argsNode
                seg += expect(PC, "OT")
                args = a
            }
            segments += seg
            if (member == null) break
            expr = AccessExpr(expr, member, args, SourceSpan(target.span.start, cursor.previous().span.end))
            cursor.enforceForwardProgress(start)
        }
        var ot: SyntaxTreeNode = epsilon("OT")
        for (seg in segments.asReversed()) {
            ot = wrap("OT", seg + ot)
        }
        return wrap("EXP", listOf(wrap("ACC", listOf(cla, ot)))) to expr
    }

    /** `ARGS ::= ARG ARGS | ε` ; `ARG ::= VAL | CO VAL` (extensión: `EXP`). */
    private fun parseArgs(): Pair<SyntaxTreeNode.NonTerminal, List<Expr>> {
        val args = ArrayList<Expr>()
        val node = rule("ARGS") {
            val children = ArrayList<SyntaxTreeNode>()
            if (check(PC) || cursor.isAtEnd) {
                children += epsilon("ARGS")
                return@rule children
            }
            var first = true
            while (!cursor.isAtEnd) {
                val start = cursor.currentIndex
                if (!first && !check(CO)) break
                children += rule("ARG") {
                    val c = ArrayList<SyntaxTreeNode>()
                    if (!first) c += term(cursor.advance())
                    val (e, expr) = parseExp()
                    val valNode = e.children.singleOrNull()
                    c += if (valNode is SyntaxTreeNode.NonTerminal && valNode.ruleName == "VAL") valNode else e
                    args += expr
                    c
                }
                first = false
                cursor.enforceForwardProgress(start)
            }
            children
        }
        return node to args
    }

    /** `TY ::= ID | TD | STR`. */
    private fun parseTy(callerRule: String): Pair<SyntaxTreeNode.NonTerminal, Token?> {
        var tok: Token? = null
        val node = rule("TY") {
            if (check(TD) || check(STR) || check(ID)) {
                tok = cursor.peek()
                listOf(term(cursor.advance()))
            } else {
                syntaxError(callerRule, "TY (ID, TD o STR)", cursor.peek())
                listOf(error("TY", callerRule, cursor.peek().span))
            }
        }
        return node to tok
    }
    // Consideraciones de la GLC v7

    private fun skipArrayBrackets(rule: String) {
        if (check(COA)) {
            consideration(rule, "no existe regla gramatical para la creación de arreglos", cursor.peek())
            cursor.advance()
            if (check(COC)) cursor.advance()
        }
    }

    private fun rejectListDeclaration(rule: String) {
        if (check(CO)) {
            consideration(rule, "no se permite la declaración de variables en modo lista", cursor.peek())
            while (!cursor.isAtEnd && !check(FI) && !check(LLC) && !check(ODA)) cursor.advance()
        }
    }

    // Utilidades de construcción y recuperación (Modo Pánico)

    private fun peekCat(offset: Int): TokenCategory = cursor.peekAhead(offset).category

    private fun check(category: TokenCategory): Boolean = cursor.peek().category == category

    /**
     * Unidad de recuperación de una instrucción/declaración: se reporta solo el primer error y, si hubo
     * error, se sincroniza hasta `FI` (se consume) o `LLC` (no se consume).
     */
    private inline fun unit(block: () -> Unit) {
        val saved = unitFailed
        unitFailed = false
        val startIndex = cursor.currentIndex
        block()
        if (unitFailed) synchronize(startIndex)
        unitFailed = saved
    }

    /** Unidad de encabezado (CLASE, MAIN, METODO): reporta el primer error sin descartar el cuerpo. */
    private inline fun header(block: () -> Unit) {
        val saved = unitFailed
        unitFailed = false
        block()
        unitFailed = saved
    }

    private fun synchronize(unitStartIndex: Int) {
        // Si la unidad ya consumió su `;`, la sincronización terminó.
        if (cursor.currentIndex > unitStartIndex && cursor.previous().category == FI) return
        while (!cursor.isAtEnd) {
            when (cursor.peek().category) {
                FI -> {
                    cursor.advance()
                    return
                }
                LLC -> return
                else -> cursor.advance()
            }
        }
    }

    private fun syntaxError(rule: String, expected: String, found: Token, spanOverride: SourceSpan? = null) {
        if (unitFailed) {
            silentFailure = true
            return
        }
        unitFailed = true
        val foundName = if (found.category == EOF) "fin de archivo" else found.category.mnemonic
        reporter.reportSyntacticError(
            ruleName = rule,
            offendingLexeme = found.lexeme.ifEmpty { "EOF" },
            justification = AcademicErrorTemplates.ruleViolation(
                rule = rule,
                expected = expected,
                found = foundName,
                foundLexeme = found.lexeme,
                production = GlcV7.production(rule)
            ),
            span = spanOverride ?: found.span
        )
    }

    /** Violación de una consideración de la GLC v7: se reporta pero el análisis de la unidad continúa. */
    private fun consideration(rule: String, detail: String, at: Token) {
        reporter.reportSyntacticError(
            ruleName = rule,
            offendingLexeme = at.lexeme,
            justification = AcademicErrorTemplates.grammarConsiderationViolation(rule, detail, GlcV7.production(rule)),
            span = at.span
        )
    }

    private fun expect(category: TokenCategory, rule: String): SyntaxTreeNode {
        if (check(category)) return term(cursor.advance())
        syntaxError(rule, category.mnemonic, cursor.peek())
        return error(category.mnemonic, rule, cursor.peek().span)
    }

    /** `FI` faltante: el error se ubica en la línea de la instrucción (token anterior), como en las láminas. */
    private fun expectFi(rule: String): SyntaxTreeNode {
        if (check(FI)) return term(cursor.advance())
        syntaxError(rule, "FI", cursor.peek(), cursor.previous().span)
        return error("FI", rule, cursor.previous().span)
    }

    private fun expectClosingBrace(rule: String): SyntaxTreeNode {
        if (check(LLC)) return term(cursor.advance())
        unit { syntaxError(rule, "LLC", cursor.peek(), cursor.previous().span) }
        return error("LLC", rule, cursor.previous().span)
    }

    /**
     * Espera [category]; si no está, reporta y busca el token en los siguientes [maxSkip] tokens (sin cruzar
     * `FI`, `LLA` ni `LLC`) para saltar el sobrante. Si no aparece, lo da por insertado y no consume nada.
     */
    private fun expectOrSkip(category: TokenCategory, rule: String, maxSkip: Int = 1): SyntaxTreeNode {
        if (check(category)) return term(cursor.advance())
        syntaxError(rule, category.mnemonic, cursor.peek())
        for (offset in 1..maxSkip) {
            val cat = peekCat(offset)
            if (cat == category) {
                repeat(offset) { cursor.advance() }
                return term(cursor.advance())
            }
            if (cat == FI || cat == LLA || cat == LLC || cat == EOF) break
        }
        return error(category.mnemonic, rule, cursor.peek().span)
    }

    private inline fun rule(name: String, block: () -> List<SyntaxTreeNode>): SyntaxTreeNode.NonTerminal {
        cursor.enterRule()
        try {
            return wrap(name, block())
        } finally {
            cursor.exitRule()
        }
    }

    private fun wrap(name: String, children: List<SyntaxTreeNode>): SyntaxTreeNode.NonTerminal {
        val hasError = children.any {
            it is SyntaxTreeNode.ErrorPlaceholder || (it is SyntaxTreeNode.NonTerminal && it.isRecoveredWithError)
        }
        return SyntaxTreeNode.NonTerminal(
            id = allocateId(),
            ruleName = name,
            children = children,
            span = computeSpan(children),
            isRecoveredWithError = hasError
        )
    }

    private fun term(token: Token): SyntaxTreeNode.Terminal = SyntaxTreeNode.Terminal(allocateId(), token)

    private fun epsilon(parentRule: String): SyntaxTreeNode.Epsilon {
        val pos = cursor.peek().span.start
        return SyntaxTreeNode.Epsilon(allocateId(), parentRule, SourceSpan(pos, pos))
    }

    private fun error(expected: String, rule: String, span: SourceSpan): SyntaxTreeNode.ErrorPlaceholder =
        SyntaxTreeNode.ErrorPlaceholder(allocateId(), expected, rule, span)

    private fun computeSpan(children: List<SyntaxTreeNode>): SourceSpan {
        val real = children.filter { it !is SyntaxTreeNode.Epsilon }
        if (real.isEmpty()) return children.firstOrNull()?.span ?: cursor.peek().span
        val first = real.first().span.start
        val last = real.last().span.end
        return if (last.offset >= first.offset) SourceSpan(first, last) else SourceSpan(first, first)
    }

    private fun allocateId(): Int = nextNodeId++
}

/** Tokens que, después de una expresión completa, indican que el alumno siguió escribiéndola (ver `continuesExpression`). */
private val EXPRESSION_CONTINUATION: Set<TokenCategory> = setOf(
    OPT, DOP, ODA, CA, COA, COC, PC, OPA, OPR, OPL, OPB, XOR, OPN, INC, DEC
)
