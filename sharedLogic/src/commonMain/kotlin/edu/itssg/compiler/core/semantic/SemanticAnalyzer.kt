package edu.itssg.compiler.core.semantic

import edu.itssg.compiler.core.error.AcademicErrorTemplates
import edu.itssg.compiler.core.error.ErrorReporter
import edu.itssg.compiler.core.lexer.Token
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.core.parser.AccessExpr
import edu.itssg.compiler.core.parser.AssignStmt
import edu.itssg.compiler.core.parser.BinaryExpr
import edu.itssg.compiler.core.parser.CallExpr
import edu.itssg.compiler.core.parser.CastExpr
import edu.itssg.compiler.core.parser.CheckOnlyStmt
import edu.itssg.compiler.core.parser.ClassDecl
import edu.itssg.compiler.core.parser.DoWhileStmt
import edu.itssg.compiler.core.parser.ErrorExpr
import edu.itssg.compiler.core.parser.Expr
import edu.itssg.compiler.core.parser.ExprStmt
import edu.itssg.compiler.core.parser.FieldDecl
import edu.itssg.compiler.core.parser.ForStmt
import edu.itssg.compiler.core.parser.GroupExpr
import edu.itssg.compiler.core.parser.IfStmt
import edu.itssg.compiler.core.parser.LiteralExpr
import edu.itssg.compiler.core.parser.MethodDecl
import edu.itssg.compiler.core.parser.NameExpr
import edu.itssg.compiler.core.parser.NewExpr
import edu.itssg.compiler.core.parser.ObjDeclStmt
import edu.itssg.compiler.core.parser.ProgramAst
import edu.itssg.compiler.core.parser.ReturnStmt
import edu.itssg.compiler.core.parser.SoutStmt
import edu.itssg.compiler.core.parser.Stmt
import edu.itssg.compiler.core.parser.SwitchStmt
import edu.itssg.compiler.core.parser.UnaryExpr
import edu.itssg.compiler.core.parser.VarDeclStmt
import edu.itssg.compiler.core.parser.WhileStmt
import edu.itssg.compiler.core.security.CompilerSecurityConfig
import edu.itssg.compiler.core.security.SourcePosition
import edu.itssg.compiler.core.security.SourceSpan
import edu.itssg.compiler.core.symboltable.SymbolEntry
import edu.itssg.compiler.core.symboltable.SymbolRole
import edu.itssg.compiler.core.symboltable.SymbolTable

/**
 * Resultado del Análisis Semántico.
 *
 * @property abstractSemanticTree Raíz del Árbol Abstracto-Semántico (`IDn PMAIN`).
 * @property principalExpressionNotations Notación de la expresión principal (U2 "Código → Notación → Expresión
 *   principal"), con sus formas prefija y postfija.
 * @property resolvedTokenSymbols Número de ID definitivo de cada token identificador, por `offset` del token.
 */
data class SemanticResult(
    val abstractSemanticTree: SemanticAstNode,
    val principalExpressionNotations: List<String>,
    val hasSemanticErrors: Boolean,
    val resolvedTokenSymbols: Map<Int, Int> = emptyMap()
)

/**
 * Analizador Semántico: "Valida que el uso/significado de las palabras sea el correcto, verifica que las cadenas
 * tengan correspondencia (que sean compatibles), genera el árbol abstracto-semántico, escribe en la tabla de
 * símbolos y clasifica en la tabla de errores lo que no tiene correspondencia" (U1 e Infografía).
 *
 * ## Tabla de símbolos
 * Se registran solo los elementos creados por el programador (paquete, clase, atributos, métodos, parámetros,
 * variables y objetos), numerados por orden de aparición (`ID1`, `ID2`...). Los nombres de Java (`System`, `out`,
 * `println`, `Math`, `random`, `length`) no se numeran. Un nombre que se vuelve a declarar en otro ámbito recibe un
 * ID nuevo (ejemplo `Viernes`: `ID3 a` método y `ID7 a` parámetro de main).
 *
 * ## Árbol abstracto-semántico (U1)
 * 1. La raíz es la clase principal (`IDn PMAIN`) y se conecta únicamente con el método principal.
 * 2. Las acciones son ramas binarias; asignaciones, `SOUT`, `return` y métodos de Java no son binarias.
 * 3. La jerarquía de operadores se respeta (las expresiones ya vienen derivadas por el Parser).
 * 4. Cada uso de un atributo, parámetro o variable de un método se sustituye por su asignación (`ID2 =` con su valor);
 *    las variables locales del main que ya se calcularon en una instrucción anterior reutilizan ese paso.
 * 5. **La ejecución se realiza de abajo hacia arriba y de izquierda a derecha**: dentro de cada instrucción del main
 *    se ejecutan primero las acciones del nivel más profundo y, en un mismo nivel, de izquierda a derecha. Ese número
 *    es [SemanticAstNode.evaluationStepNumber] y es el número de renglón de los códigos intermedios.
 *
 * Las estructuras de control (`if`, `while`, `do`, `for`, `switch`) se validan semánticamente, pero no forman parte
 * del árbol abstracto ni del código intermedio: las láminas no definen su representación.
 */
class SemanticAnalyzer(
    private val config: CompilerSecurityConfig = CompilerSecurityConfig()
) {
    // ------------------------------------------------------------------ Modelo de símbolos

    private class Binding(
        val name: String,
        val role: SymbolRole,
        val typeName: String,
        val type: SemanticDataType,
        val declToken: Token,
        val scope: String
    ) {
        var id: Int = 0
        var field: FieldDecl? = null
        var method: MethodDecl? = null
        var localInit: Expr? = null
        var obj: ObjDeclStmt? = null
        val className: String? get() = if (type == SemanticDataType.CLASS_INSTANCE) typeName else null
        val isClassLevel: Boolean get() = scope == GLOBAL
    }

    /** Información de tipo de una expresión. */
    private data class TypeInfo(
        val type: SemanticDataType,
        val className: String? = null,
        val isClassReference: Boolean = false,
        val constant: EvaluatedValue? = null
    ) {
        val display: String get() = className ?: type.keyword
    }

    private class Scope(val label: String, val method: MethodDecl?) {
        val names = LinkedHashMap<String, Binding>()
        val initialized = HashSet<Binding>()
    }

    // ------------------------------------------------------------------ Estado por análisis

    private lateinit var reporter: ErrorReporter
    private var classDecl: ClassDecl? = null
    private var className: String = "Clase"
    private var classBinding: Binding? = null
    private val bindings = ArrayList<Binding>()
    private val fields = LinkedHashMap<String, Binding>()
    private val methods = LinkedHashMap<String, Binding>()
    private val methodScopes = HashMap<MethodDecl, Scope>()
    private var mainScope: Scope? = null

    /** Token (por offset) → símbolo al que se refiere. */
    private val tokenBinding = HashMap<Int, Binding>()

    /** Identificadores del programador que nunca se declararon: nombre → primera aparición. */
    private val undeclared = LinkedHashMap<String, Token>()
    private val undeclaredTokens = HashMap<Int, String>()

    fun analyze(
        program: ProgramAst,
        symbolTable: SymbolTable,
        errorReporter: ErrorReporter
    ): SemanticResult {
        reset(errorReporter)
        val errorsBefore = errorReporter.errorCount
        classDecl = program.classDecl
        val clazz = program.classDecl

        program.packageName?.let { declare(it, SymbolRole.PACKAGE, "PACKAGE", SemanticDataType.UNKNOWN, GLOBAL) }
        if (clazz?.name != null) {
            className = clazz.name.lexeme
            classBinding = declare(clazz.name, SymbolRole.CLASS, "CLASS", SemanticDataType.CLASS_INSTANCE, GLOBAL)
        }

        if (clazz != null) {
            declareClassMembers(clazz)
            checkFieldInitializers(clazz)
            for (m in clazz.methods) checkMethod(m)
            clazz.main?.let { checkMain(it) }
        }

        val finalIds = assignFinalIds(symbolTable)

        val builder = TreeBuilder()
        val root = builder.buildRoot(clazz)
        val numbered = numberExecutionOrder(root)
        val notations = if (clazz?.main != null) NotationBuilder().build(clazz.main.body) else emptyList()

        return SemanticResult(
            abstractSemanticTree = numbered,
            principalExpressionNotations = notations,
            hasSemanticErrors = errorReporter.errorCount > errorsBefore,
            resolvedTokenSymbols = finalIds
        )
    }

    private fun reset(errorReporter: ErrorReporter) {
        reporter = errorReporter
        classDecl = null
        className = "Clase"
        classBinding = null
        bindings.clear()
        fields.clear()
        methods.clear()
        methodScopes.clear()
        mainScope = null
        tokenBinding.clear()
        undeclared.clear()
        undeclaredTokens.clear()
    }

    // =====================================================================================
    // 1. Declaraciones
    // =====================================================================================

    private fun declare(
        token: Token,
        role: SymbolRole,
        typeName: String,
        type: SemanticDataType,
        scope: String
    ): Binding {
        val b = Binding(token.lexeme, role, typeName, type, token, scope)
        bindings += b
        tokenBinding[token.span.start.offset] = b
        return b
    }

    private fun typeOfToken(typeToken: Token, reportUnknown: Boolean = true): Pair<String, SemanticDataType> {
        val name = typeToken.lexeme
        val type = SemanticDataType.fromTypeName(name)
        if (typeToken.category == TokenCategory.ID) {
            when {
                name == className -> {
                    classBinding?.let { tokenBinding[typeToken.span.start.offset] = it }
                }
                type == SemanticDataType.CLASS_INSTANCE && name !in KNOWN_JAVA_CLASSES && reportUnknown ->
                    semanticError(typeToken, AcademicErrorTemplates.unknownClass(name))
            }
        }
        return name to type
    }

    private fun declareClassMembers(clazz: ClassDecl) {
        for (f in clazz.fields) {
            val obj = f.objectDecl
            val (typeName, type) = typeOfToken(f.typeToken)
            val role = if (obj != null) SymbolRole.OBJECT else SymbolRole.VARIABLE
            if (fields.containsKey(f.name.lexeme)) {
                semanticError(f.name, AcademicErrorTemplates.redeclaredIdentifier(f.name.lexeme, className))
                continue
            }
            val b = declare(f.name, role, typeName, type, GLOBAL)
            b.field = f
            b.obj = obj
            fields[f.name.lexeme] = b
        }
        for (m in clazz.methods) {
            val returnName = m.returnTypeToken.lexeme
            val returnType = if (m.returnTypeToken.category == TokenCategory.TM) SemanticDataType.VOID
            else typeOfToken(m.returnTypeToken).second
            if (methods.containsKey(m.name.lexeme)) {
                semanticError(m.name, AcademicErrorTemplates.redeclaredIdentifier(m.name.lexeme, className))
                continue
            }
            val b = declare(m.name, SymbolRole.METODO, returnName, returnType, GLOBAL)
            b.method = m
            methods[m.name.lexeme] = b
        }
    }

    // =====================================================================================
    // 2. Validaciones semánticas
    // =====================================================================================

    private fun checkFieldInitializers(clazz: ClassDecl) {
        val classScope = Scope(GLOBAL, null)
        for ((index, f) in clazz.fields.withIndex()) {
            val b = fields[f.name.lexeme]?.takeIf { it.field === f } ?: continue
            val obj = f.objectDecl
            if (obj != null) {
                checkObjectCreation(obj)
                continue
            }
            val init = f.init ?: continue
            val info = checkExpr(init, classScope, fieldLimit = index)
            checkAssignment(f.name, b.typeName, b.type, b.className, info, init)
        }
    }

    private fun checkMethod(m: MethodDecl) {
        val scope = Scope(m.name.lexeme, m)
        methodScopes[m] = scope
        for (p in m.params) {
            if (scope.names.containsKey(p.name.lexeme)) {
                semanticError(p.name, AcademicErrorTemplates.redeclaredIdentifier(p.name.lexeme, m.name.lexeme))
                continue
            }
            val (typeName, type) = typeOfToken(p.typeToken)
            val b = declare(p.name, SymbolRole.PARAMETER, typeName, type, m.name.lexeme)
            scope.names[p.name.lexeme] = b
            scope.initialized += b
        }
        checkStatements(m.body, scope)
        val methodBinding = methods[m.name.lexeme]
        val returnType = methodBinding?.type ?: SemanticDataType.UNKNOWN
        val ret = m.returnStmt
        if (ret != null) {
            checkReturn(ret, m, returnType, methodBinding?.typeName ?: "?", scope)
        } else if (returnType != SemanticDataType.VOID && returnType != SemanticDataType.UNKNOWN &&
            !containsReturn(m.body) && !m.hadSyntaxErrors // el return pudo perderse en la recuperación del Modo Pánico
        ) {
            semanticError(m.name, AcademicErrorTemplates.missingReturn(m.name.lexeme, methodBinding?.typeName ?: "?"))
        }
    }

    private fun containsReturn(body: List<Stmt>): Boolean = body.any { s ->
        when (s) {
            is ReturnStmt -> true
            is IfStmt -> containsReturn(s.thenBody) || (s.elseBody?.let { containsReturn(it) } ?: false)
            is WhileStmt -> containsReturn(s.body)
            is DoWhileStmt -> containsReturn(s.body)
            is ForStmt -> containsReturn(s.body)
            is SwitchStmt -> s.cases.any { containsReturn(it.body) }
            else -> false
        }
    }

    private fun checkReturn(ret: ReturnStmt, m: MethodDecl, returnType: SemanticDataType, returnTypeName: String, scope: Scope) {
        val value = ret.value
        when {
            returnType == SemanticDataType.VOID && value != null -> {
                checkExpr(value, scope)
                semanticError(ret.keyword, AcademicErrorTemplates.voidReturnsValue(m.name.lexeme))
            }
            returnType != SemanticDataType.VOID && value == null ->
                semanticError(ret.keyword, AcademicErrorTemplates.missingReturn(m.name.lexeme, returnTypeName))
            value != null -> {
                val info = checkExpr(value, scope)
                if (!info.isClassReference && !isCompatible(returnType, returnTypeName, info)) {
                    semanticError(ret.keyword, AcademicErrorTemplates.incompatibleReturn(m.name.lexeme, returnTypeName, info.display))
                }
            }
        }
    }

    private fun checkMain(main: edu.itssg.compiler.core.parser.MainDecl) {
        val scope = Scope("main", null)
        mainScope = scope
        main.argsName?.let {
            val b = declare(it, SymbolRole.PARAMETER, "String[]", SemanticDataType.UNKNOWN, "main")
            scope.names[it.lexeme] = b
            scope.initialized += b
        }
        checkStatements(main.body, scope)
    }

    private fun checkStatements(stmts: List<Stmt>, scope: Scope) {
        for (s in stmts) checkStatement(s, scope)
    }

    private fun checkStatement(s: Stmt, scope: Scope) {
        when (s) {
            is VarDeclStmt -> {
                val (typeName, type) = typeOfToken(s.typeToken)
                if (scope.names.containsKey(s.name.lexeme)) {
                    semanticError(s.name, AcademicErrorTemplates.redeclaredIdentifier(s.name.lexeme, scope.label))
                    s.init?.let { checkExpr(it, scope) }
                    return
                }
                val info = s.init?.let { checkExpr(it, scope) }
                val b = declare(s.name, SymbolRole.VARIABLE, typeName, type, scope.label)
                b.localInit = s.init
                scope.names[s.name.lexeme] = b
                if (info != null && s.init != null) {
                    checkAssignment(s.name, typeName, type, b.className, info, s.init)
                    scope.initialized += b
                }
            }
            is AssignStmt -> {
                val info = checkExpr(s.value, scope)
                val target = resolveName(s.target, scope, fieldLimit = null)
                when {
                    target == null -> reportUndeclared(s.target)
                    target.role == SymbolRole.CLASS || target.role == SymbolRole.METODO || target.role == SymbolRole.PACKAGE ->
                        semanticError(s.target, AcademicErrorTemplates.notAssignable(s.target.lexeme, target.role.label))
                    else -> {
                        checkAssignment(s.target, target.typeName, target.type, target.className, info, s.value)
                        scope.initialized += target
                    }
                }
            }
            is ObjDeclStmt -> {
                checkObjectCreation(s)
                if (scope.names.containsKey(s.name.lexeme)) {
                    semanticError(s.name, AcademicErrorTemplates.redeclaredIdentifier(s.name.lexeme, scope.label))
                    return
                }
                val (typeName, type) = s.classToken.lexeme to SemanticDataType.CLASS_INSTANCE
                val b = declare(s.name, SymbolRole.OBJECT, typeName, type, scope.label)
                b.obj = s
                scope.names[s.name.lexeme] = b
                scope.initialized += b
            }
            is SoutStmt -> {
                if (s.printToken.lexeme != "println" && s.printToken.lexeme != "print") {
                    semanticError(s.printToken, AcademicErrorTemplates.memberNotFound(s.printToken.lexeme, "PrintStream (System.out)"))
                }
                val arg = s.argument
                if (arg != null) {
                    val info = checkExpr(arg, scope)
                    if (info.type == SemanticDataType.VOID) {
                        semanticError(s.printToken, "El valor que se imprime proviene de un método void (TM), que no regresa ningún valor")
                    }
                }
            }
            is ExprStmt -> checkExpr(s.expr, scope)
            is CheckOnlyStmt -> checkExpr(s.expr, scope)
            is ReturnStmt -> {
                val m = scope.method
                if (m != null) {
                    val mb = methods[m.name.lexeme]
                    checkReturn(s, m, mb?.type ?: SemanticDataType.UNKNOWN, mb?.typeName ?: "?", scope)
                } else {
                    s.value?.let { checkExpr(it, scope) }
                }
            }
            is IfStmt -> {
                checkCondition("IFEL", s.condition, scope)
                checkStatements(s.thenBody, scope)
                s.elseBody?.let { checkStatements(it, scope) }
            }
            is WhileStmt -> {
                checkCondition("WHILE", s.condition, scope)
                checkStatements(s.body, scope)
            }
            is DoWhileStmt -> {
                checkStatements(s.body, scope)
                checkCondition("DO", s.condition, scope)
            }
            is ForStmt -> {
                s.init?.let { checkStatement(it, scope) }
                s.condition?.let { checkCondition("FOR", it, scope) }
                s.update?.let { checkExpr(it, scope) }
                checkStatements(s.body, scope)
            }
            is SwitchStmt -> {
                val sel = checkExpr(s.selector, scope)
                if (sel.type != SemanticDataType.UNKNOWN && !sel.type.isIntegral && sel.type != SemanticDataType.STRING) {
                    semanticError(firstToken(s.selector), "El TD ${sel.display} no puede evaluarse en un SWITCH (se espera int, char o String)")
                }
                for (c in s.cases) {
                    c.value?.let { checkExpr(it, scope) }
                    checkStatements(c.body, scope)
                }
            }
        }
    }

    private fun checkCondition(rule: String, cond: Expr, scope: Scope) {
        val info = checkExpr(cond, scope)
        if (info.type != SemanticDataType.BOOLEAN && info.type != SemanticDataType.UNKNOWN) {
            semanticError(firstToken(cond), AcademicErrorTemplates.nonBooleanCondition(rule, info.display))
        }
    }

    private fun checkObjectCreation(obj: ObjDeclStmt) {
        val declared = obj.classToken.lexeme
        val created = obj.constructorToken.lexeme
        typeOfToken(obj.classToken)
        if (created == className) {
            classBinding?.let { tokenBinding[obj.constructorToken.span.start.offset] = it }
        } else if (created !in KNOWN_JAVA_CLASSES) {
            semanticError(obj.constructorToken, AcademicErrorTemplates.unknownClass(created))
        }
        if (declared != created) {
            semanticError(obj.constructorToken, AcademicErrorTemplates.objectTypeMismatch(declared, created))
        }
        if (created == className && obj.args.isNotEmpty()) {
            val scope = mainScope ?: Scope(GLOBAL, null)
            obj.args.forEach { checkExpr(it, scope) }
            semanticError(
                obj.constructorToken,
                AcademicErrorTemplates.incompatibleDefaultConstructor(created, obj.args.joinToString(", ") { describeArgToken(it) })
            )
        }
    }

    private fun describeArgToken(e: Expr): String = when (e) {
        is LiteralExpr -> e.token.category.mnemonic
        is NameExpr -> "ID"
        else -> "EXP"
    }

    /** Asignación `ID = valor` (declaración, reasignación o parámetro): compatibilidad de TD. */
    private fun checkAssignment(
        targetToken: Token,
        targetTypeName: String,
        targetType: SemanticDataType,
        targetClass: String?,
        info: TypeInfo,
        valueExpr: Expr
    ) {
        if (info.isClassReference) return // ya se reportó "es una clase"
        if (!isCompatible(targetType, targetTypeName, info, targetClass)) {
            semanticError(
                targetToken,
                AcademicErrorTemplates.incompatibleAssignment(targetToken.lexeme, targetTypeName, info.display)
            )
        }
    }

    private fun isCompatible(
        target: SemanticDataType,
        targetName: String,
        info: TypeInfo,
        targetClass: String? = if (target == SemanticDataType.CLASS_INSTANCE) targetName else null
    ): Boolean {
        if (target == SemanticDataType.UNKNOWN || info.type == SemanticDataType.UNKNOWN) return true
        if (target == SemanticDataType.CLASS_INSTANCE && info.type == SemanticDataType.CLASS_INSTANCE) {
            return targetClass == null || info.className == null || targetClass == info.className
        }
        if (SemanticDataType.isAssignable(target, info.type)) return true
        val constant = info.constant
        return constant != null && info.type.isIntegral && SemanticDataType.isConstantNarrowingAllowed(target, constant)
    }

    // ------------------------------------------------------------------ Expresiones

    /**
     * Resuelve un nombre en el ámbito actual (locales/parámetros), luego atributos y por último la clase.
     * @param fieldLimit al validar el inicializador del atributo número `fieldLimit`, solo los atributos
     *   declarados ANTES son válidos ("validación de declaraciones previas").
     */
    private fun resolveName(token: Token, scope: Scope, fieldLimit: Int?): Binding? {
        scope.names[token.lexeme]?.let { return it }
        fields[token.lexeme]?.let { b ->
            if (fieldLimit != null) {
                val idx = classDecl?.fields?.indexOf(b.field) ?: -1
                if (idx >= fieldLimit) return null
            }
            return b
        }
        if (token.lexeme == className) return classBinding
        return null
    }

    private fun reportUndeclared(token: Token) {
        if (token.lexeme !in JAVA_API_NAMES) {
            undeclared.getOrPut(token.lexeme) { token }
            undeclaredTokens[token.span.start.offset] = token.lexeme
        }
        semanticError(token, AcademicErrorTemplates.undeclaredIdentifier(token.lexeme))
    }

    private fun checkExpr(expr: Expr, scope: Scope, fieldLimit: Int? = null): TypeInfo = when (expr) {
        is LiteralExpr -> {
            val v = ValueOps.literalValue(expr.token)
            TypeInfo(ValueOps.literalType(expr.token.category, expr.token.lexeme), constant = v.takeIf { it.isConstant })
        }
        is NameExpr -> checkName(expr.token, scope, fieldLimit)
        is GroupExpr -> checkExpr(expr.inner, scope, fieldLimit)
        is BinaryExpr -> {
            val l = checkExpr(expr.left, scope, fieldLimit)
            val r = checkExpr(expr.right, scope, fieldLimit)
            if (l.isClassReference || r.isClassReference) {
                TypeInfo(SemanticDataType.UNKNOWN)
            } else {
                val op = expr.operator.lexeme
                val result = SemanticDataType.binaryResultType(l.type, op, r.type)
                if (result == SemanticDataType.UNKNOWN && l.type != SemanticDataType.UNKNOWN && r.type != SemanticDataType.UNKNOWN) {
                    semanticError(expr.operator, AcademicErrorTemplates.incompatibleOperands(op, l.display, r.display))
                }
                val constant = if (l.constant != null && r.constant != null) {
                    ValueOps.binary(op, l.constant, r.constant).takeIf { it.isConstant }
                } else null
                TypeInfo(result, constant = constant)
            }
        }
        is UnaryExpr -> {
            val op = expr.operator
            val info = checkExpr(expr.operand, scope, fieldLimit)
            when (op.category) {
                TokenCategory.INC, TokenCategory.DEC -> {
                    if (expr.operand !is NameExpr || (!info.type.isNumeric && info.type != SemanticDataType.UNKNOWN)) {
                        semanticError(op, AcademicErrorTemplates.incrementOnNonVariable(op.lexeme))
                    }
                    TypeInfo(info.type)
                }
                TokenCategory.OPN -> {
                    if (info.type != SemanticDataType.BOOLEAN && info.type != SemanticDataType.UNKNOWN) {
                        semanticError(op, AcademicErrorTemplates.incompatibleUnary(op.lexeme, info.display))
                    }
                    TypeInfo(SemanticDataType.BOOLEAN)
                }
                TokenCategory.OPB -> {
                    if (!info.type.isIntegral && info.type != SemanticDataType.UNKNOWN) {
                        semanticError(op, AcademicErrorTemplates.incompatibleUnary(op.lexeme, info.display))
                    }
                    TypeInfo(SemanticDataType.numericPromotion(info.type, SemanticDataType.INT))
                }
                else -> {
                    if (!info.type.isNumeric && info.type != SemanticDataType.UNKNOWN) {
                        semanticError(op, AcademicErrorTemplates.incompatibleUnary(op.lexeme, info.display))
                    }
                    val t = if (info.type == SemanticDataType.UNKNOWN) info.type
                    else SemanticDataType.numericPromotion(info.type, SemanticDataType.INT)
                    TypeInfo(t, constant = info.constant?.let { ValueOps.unary(op.lexeme, it) }?.takeIf { it.isConstant })
                }
            }
        }
        is CastExpr -> {
            val target = SemanticDataType.fromTypeName(expr.typeToken.lexeme)
            val info = checkExpr(expr.operand, scope, fieldLimit)
            val ok = info.type == SemanticDataType.UNKNOWN || info.type == target ||
                (target.isNumeric && info.type.isNumeric)
            if (!ok && !info.isClassReference) {
                semanticError(expr.typeToken, AcademicErrorTemplates.invalidCast(info.display, target.keyword))
            }
            TypeInfo(target, constant = info.constant?.let { ValueOps.cast(it, target) }?.takeIf { it.isConstant })
        }
        is NewExpr -> {
            val cls = expr.classToken.lexeme
            if (cls == className) {
                classBinding?.let { tokenBinding[expr.classToken.span.start.offset] = it }
                expr.args.forEach { checkExpr(it, scope, fieldLimit) }
                if (expr.args.isNotEmpty()) {
                    semanticError(
                        expr.classToken,
                        AcademicErrorTemplates.incompatibleDefaultConstructor(cls, expr.args.joinToString(", ") { describeArgToken(it) })
                    )
                }
            } else if (cls !in KNOWN_JAVA_CLASSES) {
                semanticError(expr.classToken, AcademicErrorTemplates.unknownClass(cls))
            }
            TypeInfo(SemanticDataType.CLASS_INSTANCE, className = cls)
        }
        is AccessExpr -> checkAccess(expr, scope, fieldLimit)
        is CallExpr -> {
            val mb = methods[expr.name.lexeme]
            if (mb == null) {
                expr.args.forEach { checkExpr(it, scope, fieldLimit) }
                reportUndeclared(expr.name)
                TypeInfo(SemanticDataType.UNKNOWN)
            } else {
                tokenBinding[expr.name.span.start.offset] = mb
                checkCallArguments(mb, expr.name, expr.args, scope, fieldLimit)
                TypeInfo(mb.type, className = mb.className)
            }
        }
        is ErrorExpr -> TypeInfo(SemanticDataType.UNKNOWN)
    }

    private fun checkName(token: Token, scope: Scope, fieldLimit: Int?): TypeInfo {
        val b = resolveName(token, scope, fieldLimit)
        if (b == null) {
            if (methods.containsKey(token.lexeme)) {
                methods[token.lexeme]?.let { tokenBinding[token.span.start.offset] = it }
                semanticError(token, AcademicErrorTemplates.methodUsedAsValue(token.lexeme))
                return TypeInfo(SemanticDataType.UNKNOWN)
            }
            reportUndeclared(token)
            return TypeInfo(SemanticDataType.UNKNOWN)
        }
        tokenBinding[token.span.start.offset] = b
        return when (b.role) {
            SymbolRole.CLASS -> {
                semanticError(token, AcademicErrorTemplates.classUsedAsValue(token.lexeme))
                TypeInfo(SemanticDataType.CLASS_INSTANCE, className = b.name, isClassReference = true)
            }
            SymbolRole.PACKAGE -> {
                semanticError(token, AcademicErrorTemplates.classUsedAsValue(token.lexeme))
                TypeInfo(SemanticDataType.UNKNOWN, isClassReference = true)
            }
            else -> {
                if (!b.isClassLevel && b !in scope.initialized && b.role == SymbolRole.VARIABLE) {
                    semanticError(token, AcademicErrorTemplates.uninitializedVariable(token.lexeme))
                    scope.initialized += b // un solo reporte por variable
                }
                TypeInfo(b.type, className = b.className)
            }
        }
    }

    private fun checkAccess(expr: AccessExpr, scope: Scope, fieldLimit: Int?): TypeInfo {
        val member = expr.member.lexeme
        val target = expr.target
        // Métodos de Java sin objeto: Math.random()
        if (target is NameExpr && target.token.lexeme == "Math" && resolveName(target.token, scope, fieldLimit) == null) {
            expr.args?.forEach { checkExpr(it, scope, fieldLimit) }
            return when {
                member == "PI" && expr.args == null -> TypeInfo(SemanticDataType.DOUBLE)
                member in MATH_METHODS && expr.args != null -> TypeInfo(SemanticDataType.DOUBLE)
                else -> {
                    semanticError(expr.member, AcademicErrorTemplates.memberNotFound(member, "Math"))
                    TypeInfo(SemanticDataType.UNKNOWN)
                }
            }
        }
        val targetInfo = if (target is NameExpr && resolveName(target.token, scope, fieldLimit)?.role == SymbolRole.CLASS) {
            classBinding?.let { tokenBinding[target.token.span.start.offset] = it }
            TypeInfo(SemanticDataType.CLASS_INSTANCE, className = className)
        } else {
            checkExpr(target, scope, fieldLimit)
        }
        return when {
            targetInfo.type == SemanticDataType.UNKNOWN -> {
                expr.args?.forEach { checkExpr(it, scope, fieldLimit) }
                TypeInfo(SemanticDataType.UNKNOWN)
            }
            targetInfo.type == SemanticDataType.STRING -> {
                expr.args?.forEach { checkExpr(it, scope, fieldLimit) }
                val method = STRING_METHODS[member]
                when {
                    expr.args == null -> {
                        semanticError(expr.member, AcademicErrorTemplates.stringAttributeNotFound(member))
                        TypeInfo(SemanticDataType.UNKNOWN)
                    }
                    method == null -> {
                        semanticError(expr.member, AcademicErrorTemplates.stringMethodNotFound(member))
                        TypeInfo(SemanticDataType.UNKNOWN)
                    }
                    else -> TypeInfo(method)
                }
            }
            targetInfo.type == SemanticDataType.CLASS_INSTANCE && targetInfo.className == className -> {
                val fieldB = fields[member]
                val methodB = methods[member]
                when {
                    expr.args == null && fieldB != null -> {
                        tokenBinding[expr.member.span.start.offset] = fieldB
                        TypeInfo(fieldB.type, className = fieldB.className)
                    }
                    expr.args != null && methodB != null -> {
                        tokenBinding[expr.member.span.start.offset] = methodB
                        checkCallArguments(methodB, expr.member, expr.args, scope, fieldLimit)
                        TypeInfo(methodB.type, className = methodB.className)
                    }
                    expr.args != null && fieldB != null -> {
                        tokenBinding[expr.member.span.start.offset] = fieldB
                        semanticError(expr.member, AcademicErrorTemplates.notAMethod(member))
                        TypeInfo(SemanticDataType.UNKNOWN)
                    }
                    expr.args == null && methodB != null -> {
                        tokenBinding[expr.member.span.start.offset] = methodB
                        semanticError(expr.member, AcademicErrorTemplates.methodUsedAsValue(member))
                        TypeInfo(SemanticDataType.UNKNOWN)
                    }
                    else -> {
                        expr.args?.forEach { checkExpr(it, scope, fieldLimit) }
                        semanticError(expr.member, AcademicErrorTemplates.memberNotFound(member, className))
                        TypeInfo(SemanticDataType.UNKNOWN)
                    }
                }
            }
            targetInfo.type == SemanticDataType.CLASS_INSTANCE -> {
                // Clases de Java permitidas (p. ej. Scanner): no se conoce su contenido
                expr.args?.forEach { checkExpr(it, scope, fieldLimit) }
                TypeInfo(SemanticDataType.UNKNOWN)
            }
            else -> {
                expr.args?.forEach { checkExpr(it, scope, fieldLimit) }
                semanticError(expr.member, AcademicErrorTemplates.memberNotFound(member, targetInfo.display))
                TypeInfo(SemanticDataType.UNKNOWN)
            }
        }
    }

    private fun checkCallArguments(method: Binding, at: Token, args: List<Expr>, scope: Scope, fieldLimit: Int?) {
        val params = method.method?.params.orEmpty()
        val infos = args.map { checkExpr(it, scope, fieldLimit) }
        if (params.size != args.size) {
            semanticError(at, AcademicErrorTemplates.argumentCountMismatch(method.name, params.size, args.size))
            return
        }
        for ((i, p) in params.withIndex()) {
            val (pName, pType) = p.typeToken.lexeme to SemanticDataType.fromTypeName(p.typeToken.lexeme)
            val info = infos[i]
            if (!info.isClassReference && !isCompatible(pType, pName, info)) {
                semanticError(firstToken(args[i]), AcademicErrorTemplates.argumentTypeMismatch(method.name, p.name.lexeme, pName, info.display))
            }
        }
    }

    // =====================================================================================
    // 3. Numeración definitiva de la Tabla de Símbolos
    // =====================================================================================

    private fun assignFinalIds(symbolTable: SymbolTable): Map<Int, Int> {
        data class Slot(val offset: Int, val binding: Binding?, val undeclaredName: String?, val token: Token)
        val slots = ArrayList<Slot>()
        bindings.forEach { slots += Slot(it.declToken.span.start.offset, it, null, it.declToken) }
        undeclared.forEach { (name, tok) -> slots += Slot(tok.span.start.offset, null, name, tok) }
        slots.sortBy { it.offset }

        val undeclaredIds = HashMap<String, Int>()
        val entries = ArrayList<SymbolEntry>()
        for ((i, slot) in slots.withIndex()) {
            val id = i + 1
            val b = slot.binding
            if (b != null) {
                b.id = id
                entries += SymbolEntry(
                    symbolId = id,
                    lexeme = b.name,
                    role = b.role,
                    dataType = b.typeName,
                    scope = b.scope,
                    firstSeen = slot.token.span.start
                )
            } else {
                undeclaredIds[slot.undeclaredName!!] = id
                entries += SymbolEntry(
                    symbolId = id,
                    lexeme = slot.undeclaredName,
                    role = SymbolRole.UNKNOWN,
                    dataType = null,
                    scope = "NO DECLARADO",
                    firstSeen = slot.token.span.start
                )
            }
        }
        symbolTable.replaceAllSymbols(entries)

        val result = HashMap<Int, Int>()
        tokenBinding.forEach { (offset, b) -> result[offset] = b.id }
        undeclaredTokens.forEach { (offset, name) -> undeclaredIds[name]?.let { result[offset] = it } }
        return result
    }

    // =====================================================================================
    // 4. Árbol Abstracto-Semántico
    // =====================================================================================

    /** Contexto de evaluación perezosa de un método invocado (parámetros con su argumento, locales con su valor). */
    private class Frame(
        val method: MethodDecl?,
        val methodBinding: Binding?,
        val params: Map<Binding, Pair<Expr, Frame>>,
        val locals: Map<Binding, Pair<Expr, Frame>>,
        val depth: Int,
        val isMain: Boolean,
        val materialized: MutableMap<Binding, SemanticAstNode>
    )

    private inner class TreeBuilder {
        private var nextId = 1
        private var nodeCount = 0
        private var limitReported = false
        private val fieldsInExpansion = HashSet<Binding>()
        private val classFrame = Frame(null, null, emptyMap(), emptyMap(), 0, false, HashMap())

        private fun id(): Int {
            nodeCount++
            return nextId++
        }

        private val budgetExceeded: Boolean
            get() {
                if (nodeCount <= MAX_TREE_NODES) return false
                if (!limitReported) {
                    limitReported = true
                    reporter.reportSemanticError(
                        offendingLexeme = className,
                        justification = AcademicErrorTemplates.treeLimitExceeded(MAX_TREE_NODES),
                        span = classDecl?.span ?: SourceSpan(SourcePosition.START, SourcePosition.START)
                    )
                }
                return true
            }

        fun buildRoot(clazz: ClassDecl?): SemanticAstNode {
            val rootSpan = clazz?.span ?: SourceSpan(SourcePosition.START, SourcePosition.START)
            val classId = classBinding?.id
            val rootLabel = classId?.let { "ID$it" } ?: "ID?"
            val main = clazz?.main
            val children = ArrayList<SemanticAstNode>()
            if (main != null) {
                val frame = Frame(null, null, emptyMap(), emptyMap(), 0, true, HashMap())
                for (s in main.body) {
                    statementNode(s, frame, owner = null)?.let { children += it }
                }
            }
            val last = children.lastOrNull()
            return SemanticAstNode(
                id = id(),
                kind = SemanticNodeKind.PROGRAM_ROOT,
                primaryLabel = rootLabel,
                secondaryLabel = "PMAIN",
                resolvedType = last?.resolvedType ?: SemanticDataType.UNKNOWN,
                evaluatedValue = last?.evaluatedValue ?: EvaluatedValue.UnknownVal,
                symbolId = classId,
                children = children,
                span = main?.span ?: rootSpan
            )
        }

        /** Nodo de una instrucción del main o de un método invocado (las declaraciones del método son perezosas). */
        private fun statementNode(s: Stmt, frame: Frame, owner: Binding?): SemanticAstNode? = when (s) {
            is SoutStmt -> {
                val arg = s.argument?.let { expand(it, frame) }
                    ?: leaf(SemanticNodeKind.LITERAL_VALUE, "\"\"", EvaluatedValue.StringVal(""), s.span)
                SemanticAstNode(
                    id = id(),
                    kind = SemanticNodeKind.OUTPUT_STATEMENT,
                    primaryLabel = "SOUT",
                    secondaryLabel = owner?.let { "ID${it.id}" } ?: "PMAIN",
                    resolvedType = arg.resolvedType,
                    evaluatedValue = arg.evaluatedValue,
                    symbolId = owner?.id,
                    children = listOf(arg),
                    span = s.span
                )
            }
            is VarDeclStmt -> {
                val b = tokenBinding[s.name.span.start.offset]
                val init = s.init
                if (b == null || init == null || !frame.isMain) null
                else assignment(b, expand(init, frame), s.span).also { frame.materialized[b] = it }
            }
            is AssignStmt -> {
                val b = tokenBinding[s.target.span.start.offset]
                if (b == null || !frame.isMain) null
                else assignment(b, expand(s.value, frame), s.span).also { frame.materialized[b] = it }
            }
            is ObjDeclStmt -> {
                val b = tokenBinding[s.name.span.start.offset]
                if (b == null || !frame.isMain) null
                else {
                    val cls = classBinding?.takeIf { s.constructorToken.lexeme == className }
                    val value = leaf(
                        SemanticNodeKind.IDENTIFIER_REF,
                        "new ${cls?.let { "ID${it.id}" } ?: s.constructorToken.lexeme}()",
                        EvaluatedValue.ObjectVal(s.constructorToken.lexeme),
                        s.span,
                        type = SemanticDataType.CLASS_INSTANCE
                    )
                    assignment(b, value, s.span).also { frame.materialized[b] = it }
                }
            }
            is ExprStmt -> expand(s.expr, frame)
            else -> null
        }

        private fun leaf(
            kind: SemanticNodeKind,
            label: String,
            value: EvaluatedValue,
            span: SourceSpan,
            type: SemanticDataType = value.dataType,
            symbolId: Int? = null,
            referencedNodeId: Int? = null
        ) = SemanticAstNode(
            id = id(),
            kind = kind,
            primaryLabel = label,
            resolvedType = type,
            evaluatedValue = value,
            symbolId = symbolId,
            referencedNodeId = referencedNodeId,
            span = span
        )

        private fun assignment(b: Binding, value: SemanticAstNode, span: SourceSpan): SemanticAstNode {
            val coerced = if (b.type.isNumeric) ValueOps.coerce(value.evaluatedValue, b.type) else value.evaluatedValue
            return SemanticAstNode(
                id = id(),
                kind = SemanticNodeKind.ASSIGNMENT_LINK,
                primaryLabel = "ID${b.id}",
                secondaryLabel = "=",
                resolvedType = if (b.type == SemanticDataType.UNKNOWN) value.resolvedType else b.type,
                evaluatedValue = coerced,
                symbolId = b.id,
                children = listOf(value),
                span = span
            )
        }

        private fun unknownLeaf(label: String, span: SourceSpan) =
            leaf(SemanticNodeKind.IDENTIFIER_REF, label, EvaluatedValue.UnknownVal, span)

        /** Expande una expresión en el contexto [frame]. */
        fun expand(expr: Expr, frame: Frame): SemanticAstNode {
            if (budgetExceeded) return unknownLeaf("…", expr.span)
            return when (expr) {
                is LiteralExpr -> {
                    val v = ValueOps.literalValue(expr.token)
                    leaf(SemanticNodeKind.LITERAL_VALUE, expr.token.lexeme, v, expr.span)
                }
                is NameExpr -> expandName(expr.token, frame)
                is GroupExpr -> expand(expr.inner, frame)
                is BinaryExpr -> {
                    val l = expand(expr.left, frame)
                    val r = expand(expr.right, frame)
                    val op = expr.operator.lexeme
                    val value = ValueOps.binary(op, l.evaluatedValue, r.evaluatedValue)
                    val type = SemanticDataType.binaryResultType(l.resolvedType, op, r.resolvedType)
                    SemanticAstNode(
                        id = id(),
                        kind = SemanticNodeKind.BINARY_OPERATOR,
                        primaryLabel = op,
                        resolvedType = if (type == SemanticDataType.UNKNOWN) value.dataType else type,
                        evaluatedValue = value,
                        children = listOf(l, r),
                        span = expr.span
                    )
                }
                is UnaryExpr -> expandUnary(expr, frame)
                is CastExpr -> {
                    val target = SemanticDataType.fromTypeName(expr.typeToken.lexeme)
                    val inner = expand(expr.operand, frame)
                    inner.copy(
                        castTypeAnnotation = expr.typeToken.lexeme,
                        resolvedType = target,
                        evaluatedValue = ValueOps.cast(inner.evaluatedValue, target)
                    )
                }
                is NewExpr -> {
                    val cls = classBinding?.takeIf { expr.classToken.lexeme == className }
                    leaf(
                        SemanticNodeKind.IDENTIFIER_REF,
                        cls?.let { "ID${it.id}" } ?: expr.classToken.lexeme,
                        EvaluatedValue.ObjectVal(expr.classToken.lexeme),
                        expr.span,
                        type = SemanticDataType.CLASS_INSTANCE,
                        symbolId = cls?.id
                    )
                }
                is AccessExpr -> expandAccess(expr, frame)
                is CallExpr -> {
                    val mb = tokenBinding[expr.name.span.start.offset]
                    val m = mb?.method
                    if (mb == null || m == null) unknownLeaf(expr.name.lexeme, expr.span)
                    else {
                        val classLeaf = classLeaf(expr.span)
                        val members = expandMethodCall(mb, m, expr.args, frame, expr.span)
                        access(classLeaf, members, expr.span)
                    }
                }
                is ErrorExpr -> unknownLeaf("?", expr.span)
            }
        }

        private fun classLeaf(span: SourceSpan): SemanticAstNode {
            val cb = classBinding
            return leaf(
                SemanticNodeKind.IDENTIFIER_REF,
                cb?.let { "ID${it.id}" } ?: className,
                EvaluatedValue.ObjectVal(className),
                span,
                type = SemanticDataType.CLASS_INSTANCE,
                symbolId = cb?.id
            )
        }

        private fun access(target: SemanticAstNode, members: List<SemanticAstNode>, span: SourceSpan): SemanticAstNode {
            val last = members.lastOrNull()
            return SemanticAstNode(
                id = id(),
                kind = SemanticNodeKind.MEMBER_ACCESS,
                primaryLabel = ".",
                resolvedType = last?.resolvedType ?: SemanticDataType.UNKNOWN,
                evaluatedValue = last?.evaluatedValue ?: EvaluatedValue.UnknownVal,
                children = listOf(target) + members,
                span = span
            )
        }

        private fun expandName(token: Token, frame: Frame): SemanticAstNode {
            val b = tokenBinding[token.span.start.offset] ?: return unknownLeaf(token.lexeme, token.span)
            return when {
                b.role == SymbolRole.CLASS -> classLeaf(token.span)
                b.isClassLevel && b.field != null -> fieldNode(b, token.span)
                frame.params.containsKey(b) -> {
                    val (argExpr, callerFrame) = frame.params.getValue(b)
                    assignment(b, expand(argExpr, callerFrame), token.span)
                }
                frame.locals.containsKey(b) -> {
                    val (valueExpr, declFrame) = frame.locals.getValue(b)
                    assignment(b, expand(valueExpr, declFrame), token.span)
                }
                frame.isMain && frame.materialized.containsKey(b) -> {
                    val node = frame.materialized.getValue(b)
                    leaf(
                        SemanticNodeKind.IDENTIFIER_REF, "ID${b.id}", node.evaluatedValue, token.span,
                        type = node.resolvedType, symbolId = b.id, referencedNodeId = node.id
                    )
                }
                else -> leaf(SemanticNodeKind.IDENTIFIER_REF, "ID${b.id}", EvaluatedValue.UnknownVal, token.span,
                    type = b.type, symbolId = b.id)
            }
        }

        /** Uso de un atributo: su asignación con el valor inicial (o el valor por defecto, ej. `float numero;` → `0.0f`). */
        private fun fieldNode(b: Binding, span: SourceSpan): SemanticAstNode {
            val f = b.field
            if (b in fieldsInExpansion) return unknownLeaf("ID${b.id}", span)
            val obj = b.obj
            val valueNode = when {
                obj != null -> leaf(
                    SemanticNodeKind.IDENTIFIER_REF,
                    "new ${classBinding?.takeIf { obj.constructorToken.lexeme == className }?.let { "ID${it.id}" } ?: obj.constructorToken.lexeme}()",
                    EvaluatedValue.ObjectVal(obj.constructorToken.lexeme), span, type = SemanticDataType.CLASS_INSTANCE
                )
                f?.init != null -> {
                    fieldsInExpansion += b
                    try {
                        expand(f.init, classFrame)
                    } finally {
                        fieldsInExpansion -= b
                    }
                }
                else -> leaf(SemanticNodeKind.LITERAL_VALUE, b.type.defaultLiteralText(), b.type.defaultValue(), span, type = b.type)
            }
            return assignment(b, valueNode, span)
        }

        private fun expandUnary(expr: UnaryExpr, frame: Frame): SemanticAstNode {
            val op = expr.operator
            return when (op.category) {
                // `++a` se representa como `(1, +, a)` (U2: "float tres = dos + 1; // ++dos")
                TokenCategory.INC, TokenCategory.DEC -> {
                    val operand = expand(expr.operand, frame)
                    val one = leaf(SemanticNodeKind.LITERAL_VALUE, "1", EvaluatedValue.IntVal(1), op.span)
                    val sym = if (op.category == TokenCategory.INC) "+" else "-"
                    val children = if (sym == "+") listOf(one, operand) else listOf(operand, one)
                    val value = ValueOps.coerce(
                        ValueOps.binary(sym, children[0].evaluatedValue, children[1].evaluatedValue),
                        operand.resolvedType
                    )
                    SemanticAstNode(
                        id = id(), kind = SemanticNodeKind.BINARY_OPERATOR, primaryLabel = sym,
                        resolvedType = operand.resolvedType, evaluatedValue = value, children = children, span = expr.span
                    )
                }
                else -> {
                    val operandExpr = expr.operand
                    // Signo de un literal numérico: `-5` es un VALOR, no una acción.
                    if (op.lexeme == "-" && operandExpr is LiteralExpr && operandExpr.token.category in NUMERIC_LITERALS) {
                        val v = ValueOps.unary("-", ValueOps.literalValue(operandExpr.token))
                        return leaf(SemanticNodeKind.LITERAL_VALUE, "-${operandExpr.token.lexeme}", v, expr.span)
                    }
                    val operand = expand(operandExpr, frame)
                    if (op.lexeme == "+") return operand
                    val value = ValueOps.unary(op.lexeme, operand.evaluatedValue)
                    SemanticAstNode(
                        id = id(), kind = SemanticNodeKind.UNARY_OPERATOR, primaryLabel = op.lexeme,
                        resolvedType = value.dataType.takeIf { it != SemanticDataType.UNKNOWN } ?: operand.resolvedType,
                        evaluatedValue = value, children = listOf(operand), span = expr.span
                    )
                }
            }
        }

        private fun expandAccess(expr: AccessExpr, frame: Frame): SemanticAstNode {
            val member = expr.member.lexeme
            val target = expr.target
            if (target is NameExpr && target.token.lexeme == "Math" && tokenBinding[target.token.span.start.offset] == null) {
                val value: EvaluatedValue = if (member == "random") EvaluatedValue.SymbolicVal()
                else if (member == "PI" && expr.args == null) EvaluatedValue.DoubleVal(kotlin.math.PI)
                else EvaluatedValue.UnknownVal
                val mathLeaf = leaf(SemanticNodeKind.IDENTIFIER_REF, "Math", EvaluatedValue.UnknownVal, target.span)
                val memberLeaf = leaf(SemanticNodeKind.IDENTIFIER_REF, if (expr.args != null) "$member()" else member,
                    value, expr.member.span, type = SemanticDataType.DOUBLE)
                return SemanticAstNode(
                    id = id(), kind = SemanticNodeKind.MEMBER_ACCESS, primaryLabel = ".",
                    resolvedType = SemanticDataType.DOUBLE, evaluatedValue = value,
                    children = listOf(mathLeaf, memberLeaf), span = expr.span
                )
            }
            val memberBinding = tokenBinding[expr.member.span.start.offset]
            if (memberBinding != null && (memberBinding.field != null || memberBinding.method != null)) {
                val targetNode = when (target) {
                    is NewExpr -> expand(target, frame)
                    is NameExpr -> {
                        val tb = tokenBinding[target.token.span.start.offset]
                        if (tb != null && tb.role != SymbolRole.CLASS) {
                            leaf(SemanticNodeKind.IDENTIFIER_REF, "ID${tb.id}", EvaluatedValue.ObjectVal(className),
                                target.span, type = SemanticDataType.CLASS_INSTANCE, symbolId = tb.id)
                        } else classLeaf(target.span)
                    }
                    else -> expand(target, frame)
                }
                val m = memberBinding.method
                val members = if (m != null) {
                    expandMethodCall(memberBinding, m, expr.args.orEmpty(), frame, expr.span)
                } else {
                    listOf(fieldNode(memberBinding, expr.member.span))
                }
                return access(targetNode, members, expr.span)
            }
            // Métodos de String: "LOL".length(), (a+c).length()
            val targetNode = expand(target, frame)
            val text = (targetNode.evaluatedValue as? EvaluatedValue.StringVal)?.value
            val value: EvaluatedValue = when {
                expr.args == null || text == null -> EvaluatedValue.UnknownVal
                member == "length" -> EvaluatedValue.IntVal(text.length.toLong())
                member == "toUpperCase" -> EvaluatedValue.StringVal(text.uppercase())
                member == "toLowerCase" -> EvaluatedValue.StringVal(text.lowercase())
                member == "trim" -> EvaluatedValue.StringVal(text.trim())
                member == "isEmpty" -> EvaluatedValue.BoolVal(text.isEmpty())
                else -> EvaluatedValue.UnknownVal
            }
            val type = STRING_METHODS[member] ?: SemanticDataType.UNKNOWN
            val memberLeaf = leaf(SemanticNodeKind.IDENTIFIER_REF, if (expr.args != null) "$member()" else member,
                value, expr.member.span, type = type)
            return SemanticAstNode(
                id = id(), kind = SemanticNodeKind.MEMBER_ACCESS, primaryLabel = ".",
                resolvedType = type, evaluatedValue = value, children = listOf(targetNode, memberLeaf), span = expr.span
            )
        }

        /**
         * Expansión de la invocación de un método: sus instrucciones con efecto (`SOUT`, llamadas) y su `RTN`.
         * Los parámetros quedan ligados a los argumentos y las variables locales se evalúan al usarse.
         */
        private fun expandMethodCall(
            mb: Binding,
            m: MethodDecl,
            args: List<Expr>,
            callerFrame: Frame,
            span: SourceSpan
        ): List<SemanticAstNode> {
            if (callerFrame.depth >= MAX_CALL_DEPTH) return listOf(unknownLeaf("ID${mb.id}", span))
            val params = LinkedHashMap<Binding, Pair<Expr, Frame>>()
            m.params.forEachIndexed { i, p ->
                val pb = tokenBinding[p.name.span.start.offset] ?: return@forEachIndexed
                args.getOrNull(i)?.let { params[pb] = it to callerFrame }
            }
            var locals: Map<Binding, Pair<Expr, Frame>> = emptyMap()
            fun currentFrame() = Frame(m, mb, params, locals, callerFrame.depth + 1, false, HashMap())

            val nodes = ArrayList<SemanticAstNode>()
            for (s in m.body) {
                when (s) {
                    is VarDeclStmt -> {
                        val b = tokenBinding[s.name.span.start.offset]
                        val init = s.init
                        if (b != null && init != null) locals = locals + (b to (init to currentFrame()))
                    }
                    is AssignStmt -> {
                        val b = tokenBinding[s.target.span.start.offset]
                        if (b != null && !b.isClassLevel) locals = locals + (b to (s.value to currentFrame()))
                    }
                    is SoutStmt, is ExprStmt -> statementNode(s, currentFrame(), owner = mb)?.let { nodes += it }
                    else -> Unit
                }
            }
            val ret = m.returnStmt
            val retValue = ret?.value
            if (ret != null && retValue != null) {
                val frame = currentFrame()
                val castType = (retValue as? CastExpr)?.typeToken?.lexeme
                val child = if (retValue is CastExpr) expand(retValue.operand, frame) else expand(retValue, frame)
                var value = child.evaluatedValue
                if (castType != null) value = ValueOps.cast(value, SemanticDataType.fromTypeName(castType))
                if (mb.type.isNumeric) value = ValueOps.coerce(value, mb.type)
                nodes += SemanticAstNode(
                    id = id(),
                    kind = SemanticNodeKind.METHOD_RETURN,
                    primaryLabel = "RTN",
                    secondaryLabel = "ID${mb.id}",
                    castTypeAnnotation = castType,
                    resolvedType = mb.type,
                    evaluatedValue = value,
                    symbolId = mb.id,
                    children = listOf(child),
                    span = ret.span
                )
            }
            if (nodes.isEmpty()) {
                nodes += leaf(SemanticNodeKind.IDENTIFIER_REF, "ID${mb.id}", EvaluatedValue.UnknownVal, span,
                    type = mb.type, symbolId = mb.id)
            }
            return nodes
        }
    }

    /**
     * Orden de ejecución "de abajo hacia arriba, de izquierda a derecha" (U1): para cada instrucción del main, se
     * numeran primero las acciones del nivel más profundo; dentro de un nivel, de izquierda a derecha.
     */
    private fun numberExecutionOrder(root: SemanticAstNode): SemanticAstNode {
        val stepOf = HashMap<Int, Int>()
        var step = 1
        for (statement in root.children) {
            data class Visit(val id: Int, val depth: Int, val order: Int)
            val visits = ArrayList<Visit>()
            var order = 0
            val stack = ArrayDeque<Pair<SemanticAstNode, Int>>()
            stack.addLast(statement to 0)
            while (stack.isNotEmpty()) {
                val (node, depth) = stack.removeLast()
                if (node.kind.isOperation) visits += Visit(node.id, depth, order++)
                for (child in node.children.asReversed()) stack.addLast(child to depth + 1)
            }
            visits.sortWith(compareByDescending<Visit> { it.depth }.thenBy { it.order })
            for (v in visits) stepOf[v.id] = step++
        }
        val renumber = DeepRecursiveFunction<SemanticAstNode, SemanticAstNode> { node ->
            val newChildren = ArrayList<SemanticAstNode>(node.children.size)
            for (c in node.children) newChildren += callRecursive(c)
            node.copy(evaluationStepNumber = stepOf[node.id], children = newChildren)
        }
        return renumber(root)
    }

    // =====================================================================================
    // 5. Notación de la expresión principal (U2: Código → Notación → Expresión principal)
    // =====================================================================================

    private sealed interface NTree {
        data class Leaf(val text: String) : NTree
        data class Op(val op: String, val left: NTree, val right: NTree) : NTree
    }

    /** Entorno de sustitución de la notación: parámetro → (argumento, entorno del llamador). */
    private class NEnv(val bindings: Map<Binding, Pair<Expr, NEnv>>) {
        companion object {
            val EMPTY = NEnv(emptyMap())
        }
    }

    private inner class NotationBuilder {
        private val maxLevels = 12
        private var nodes = 0

        fun build(mainBody: List<Stmt>): List<String> {
            var env = NEnv.EMPTY
            var expr: Expr = mainBody.firstNotNullOfOrNull { (it as? SoutStmt)?.argument }
                ?: mainBody.lastOrNull()?.let {
                    when (it) {
                        is ExprStmt -> it.expr
                        is VarDeclStmt -> it.init
                        is AssignStmt -> it.value
                        else -> null
                    }
                } ?: return emptyList()
            val lines = ArrayList<String>()
            lines += "Código: ${render(expr, 0, env)}"
            // Llamada a un método void: la expresión principal es la que imprime su SOUT (ejemplo `Uno`).
            if (expr is AccessExpr) {
                val m = tokenBinding[expr.member.span.start.offset]?.method
                val soutArg = m?.body?.firstNotNullOfOrNull { (it as? SoutStmt)?.argument }
                if (m != null && m.returnStmt?.value == null && soutArg != null) {
                    env = paramEnv(m, expr.args, env)
                    expr = soutArg
                    lines += render(expr, 0, env)
                }
            }
            for (level in 1..maxLevels) {
                nodes = 0
                val line = render(expr, level, env)
                if (line == lines.last().removePrefix("Código: ")) break
                lines += line
            }
            nodes = 0
            val tree = toTree(expr, env, 0)
            lines += "Expresión principal: ${infix(tree, 0, false)}"
            lines += "Prefija: ${prefix(tree)}"
            lines += "Postfija: ${postfix(tree)}"
            return lines
        }

        /** Sustitución de una definición: solo se encierra entre paréntesis si es una operación. */
        private fun substituted(def: Expr, levels: Int, env: NEnv): String {
            val text = render(def, levels, env)
            return if (def is LiteralExpr || def is NameExpr || def is AccessExpr || def is GroupExpr ||
                (def is BinaryExpr && def.parenthesized)
            ) text else "($text)"
        }

        private fun precedence(op: String): Int = when (op) {
            "||", "|" -> 1
            "&&", "&", "^" -> 2
            "==", "!=" -> 3
            "<", ">", "<=", ">=" -> 4
            "+", "-" -> 5
            "*", "/", "%" -> 6
            else -> 7 // "." (nivel 3 de las láminas: mayor jerarquía)
        }

        /** Expresión depurada: solo los paréntesis que cambian la jerarquía. */
        private fun infix(t: NTree, parentPrecedence: Int, isRightChild: Boolean): String = when (t) {
            is NTree.Leaf -> t.text
            is NTree.Op -> {
                val p = precedence(t.op)
                if (t.op == ".") {
                    "${infix(t.left, 8, false)}.${infix(t.right, 8, true)}"
                } else {
                    val text = "${infix(t.left, p, false)}${t.op}${infix(t.right, p, true)}"
                    if (p < parentPrecedence || (p == parentPrecedence && isRightChild)) "($text)" else text
                }
            }
        }

        private fun lookupDefinition(token: Token, env: NEnv): Pair<Expr, NEnv>? {
            val b = tokenBinding[token.span.start.offset] ?: return null
            env.bindings[b]?.let { return it }
            if (b.isClassLevel) {
                val init = b.field?.init ?: return null
                return init to NEnv.EMPTY
            }
            b.localInit?.let { return it to env }
            return null
        }

        private fun paramEnv(m: MethodDecl, args: List<Expr>?, callerEnv: NEnv): NEnv {
            val map = HashMap<Binding, Pair<Expr, NEnv>>()
            m.params.forEachIndexed { i, p ->
                val pb = tokenBinding[p.name.span.start.offset]
                val a = args?.getOrNull(i)
                if (pb != null && a != null) map[pb] = a to callerEnv
            }
            return NEnv(map)
        }

        private fun render(expr: Expr, levels: Int, env: NEnv): String {
            if (++nodes > 4000) return "…"
            return when (expr) {
                is LiteralExpr -> expr.token.lexeme
                is NameExpr -> {
                    val b = tokenBinding[expr.token.span.start.offset]
                    if (levels <= 0) return expr.token.lexeme
                    if (b?.isClassLevel == true && b.field != null && b.field?.init == null && b.obj == null) {
                        return b.type.defaultLiteralText()
                    }
                    val def = lookupDefinition(expr.token, env) ?: return expr.token.lexeme
                    substituted(def.first, levels - 1, def.second)
                }
                is GroupExpr -> "(${render(expr.inner, levels, env)})"
                is BinaryExpr -> {
                    val inner = "${render(expr.left, levels, env)}${expr.operator.lexeme}${render(expr.right, levels, env)}"
                    if (expr.parenthesized) "($inner)" else inner
                }
                is UnaryExpr -> if (expr.isPrefix) "${expr.operator.lexeme}${render(expr.operand, levels, env)}"
                else "${render(expr.operand, levels, env)}${expr.operator.lexeme}"
                is CastExpr -> "(${expr.typeToken.lexeme})${render(expr.operand, levels, env)}"
                is NewExpr -> "new ${expr.classToken.lexeme}()"
                is AccessExpr -> {
                    val mb = tokenBinding[expr.member.span.start.offset]
                    if (levels > 0 && mb != null) {
                        val m = mb.method
                        if (m != null) {
                            val retExpr = m.returnStmt?.value
                            if (retExpr != null) {
                                return substituted(retExpr, levels - 1, paramEnv(m, expr.args, env))
                            }
                        } else if (mb.field?.init != null) {
                            return substituted(mb.field!!.init!!, levels - 1, NEnv.EMPTY)
                        } else if (mb.field != null) {
                            return mb.type.defaultLiteralText()
                        }
                    }
                    val args = expr.args?.joinToString(",", "(", ")") { render(it, levels, env) } ?: ""
                    "${render(expr.target, levels, env)}.${expr.member.lexeme}$args"
                }
                is CallExpr -> "${expr.name.lexeme}(${expr.args.joinToString(",") { render(it, levels, env) }})"
                is ErrorExpr -> "?"
            }
        }

        private fun toTree(expr: Expr, env: NEnv, depth: Int): NTree {
            if (++nodes > 4000 || depth > 64) return NTree.Leaf("…")
            return when (expr) {
                is LiteralExpr -> NTree.Leaf(expr.token.lexeme)
                is NameExpr -> {
                    val b = tokenBinding[expr.token.span.start.offset]
                    if (b?.isClassLevel == true && b.field != null && b.field?.init == null && b.obj == null) {
                        return NTree.Leaf(b.type.defaultLiteralText())
                    }
                    val def = lookupDefinition(expr.token, env) ?: return NTree.Leaf(expr.token.lexeme)
                    toTree(def.first, def.second, depth + 1)
                }
                is GroupExpr -> toTree(expr.inner, env, depth + 1)
                is BinaryExpr -> NTree.Op(expr.operator.lexeme, toTree(expr.left, env, depth + 1), toTree(expr.right, env, depth + 1))
                is UnaryExpr -> when (expr.operator.category) {
                    TokenCategory.INC -> NTree.Op("+", NTree.Leaf("1"), toTree(expr.operand, env, depth + 1))
                    TokenCategory.DEC -> NTree.Op("-", toTree(expr.operand, env, depth + 1), NTree.Leaf("1"))
                    else -> {
                        val inner = toTree(expr.operand, env, depth + 1)
                        if (inner is NTree.Leaf) NTree.Leaf("${expr.operator.lexeme}${inner.text}") else inner
                    }
                }
                is CastExpr -> toTree(expr.operand, env, depth + 1)
                is NewExpr -> NTree.Leaf("new ${expr.classToken.lexeme}()")
                is AccessExpr -> {
                    val mb = tokenBinding[expr.member.span.start.offset]
                    val m = mb?.method
                    when {
                        m?.returnStmt?.value != null ->
                            toTree(m.returnStmt!!.value!!, paramEnv(m, expr.args, env), depth + 1)
                        mb?.field?.init != null -> toTree(mb.field!!.init!!, NEnv.EMPTY, depth + 1)
                        mb?.field != null -> NTree.Leaf(mb.type.defaultLiteralText())
                        else -> NTree.Op(".", toTree(expr.target, env, depth + 1),
                            NTree.Leaf(expr.member.lexeme + if (expr.args != null) "()" else ""))
                    }
                }
                is CallExpr -> NTree.Leaf("${expr.name.lexeme}()")
                is ErrorExpr -> NTree.Leaf("?")
            }
        }

        private fun prefix(t: NTree): String = when (t) {
            is NTree.Leaf -> t.text
            is NTree.Op -> "${t.op} ${prefix(t.left)} ${prefix(t.right)}"
        }

        private fun postfix(t: NTree): String = when (t) {
            is NTree.Leaf -> t.text
            is NTree.Op -> "${postfix(t.left)} ${postfix(t.right)} ${t.op}"
        }
    }

    // =====================================================================================
    // Utilidades
    // =====================================================================================

    private fun firstToken(expr: Expr): Token = when (expr) {
        is LiteralExpr -> expr.token
        is NameExpr -> expr.token
        is BinaryExpr -> firstToken(expr.left)
        is UnaryExpr -> if (expr.isPrefix) expr.operator else firstToken(expr.operand)
        is CastExpr -> expr.typeToken
        is GroupExpr -> firstToken(expr.inner)
        is NewExpr -> expr.classToken
        is AccessExpr -> firstToken(expr.target)
        is CallExpr -> expr.name
        is ErrorExpr -> Token(TokenCategory.EOF, "?", span = expr.span)
    }

    private fun semanticError(at: Token, justification: String) {
        reporter.reportSemanticError(offendingLexeme = at.lexeme, justification = justification, span = at.span)
    }

    private companion object {
        const val GLOBAL = "GLOBAL"
        const val MAX_TREE_NODES = 20_000
        const val MAX_CALL_DEPTH = 16

        val NUMERIC_LITERALS = setOf(TokenCategory.VTE, TokenCategory.VTD, TokenCategory.VTF)

        /** Clases de Java que pueden aparecer como tipo o en `new` sin ser error. */
        val KNOWN_JAVA_CLASSES = setOf(
            "String", "Scanner", "Object", "Math", "System",
            "Integer", "Double", "Float", "Long", "Short", "Byte", "Character", "Boolean"
        )

        /** Identificadores de Java que no son creados por el programador (no se numeran en la Tabla de Símbolos). */
        val JAVA_API_NAMES = KNOWN_JAVA_CLASSES + setOf(
            "out", "in", "println", "print", "random", "length", "charAt", "equals", "toUpperCase", "toLowerCase",
            "substring", "indexOf", "trim", "isEmpty", "nextInt", "nextDouble", "nextLine", "next", "abs", "pow",
            "sqrt", "max", "min", "round", "PI", "parseInt", "parseDouble", "valueOf", "toString"
        )

        val MATH_METHODS = setOf("random", "abs", "pow", "sqrt", "max", "min", "round")

        val STRING_METHODS: Map<String, SemanticDataType> = mapOf(
            "length" to SemanticDataType.INT,
            "charAt" to SemanticDataType.CHAR,
            "equals" to SemanticDataType.BOOLEAN,
            "isEmpty" to SemanticDataType.BOOLEAN,
            "indexOf" to SemanticDataType.INT,
            "toUpperCase" to SemanticDataType.STRING,
            "toLowerCase" to SemanticDataType.STRING,
            "trim" to SemanticDataType.STRING,
            "substring" to SemanticDataType.STRING
        )
    }
}
