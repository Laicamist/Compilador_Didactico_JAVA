package edu.itssg.compiler.core.semantic

import edu.itssg.compiler.core.error.AcademicErrorTemplates
import edu.itssg.compiler.core.error.CompilerNote
import edu.itssg.compiler.core.error.CompilerNoteKind
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
import edu.itssg.compiler.core.parser.SwitchCase
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
 * @property notes Observaciones didácticas que no son errores (estructuras de control omitidas, división entre cero,
 *   recursión cortada), en orden de posición.
 */
data class SemanticResult(
    val abstractSemanticTree: SemanticAstNode,
    val principalExpressionNotations: List<String>,
    val hasSemanticErrors: Boolean,
    val resolvedTokenSymbols: Map<Int, Int> = emptyMap(),
    val notes: List<CompilerNote> = emptyList()
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
 * ID nuevo.
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
 * Las estructuras de control (`if`, `while`, `do`, `for`, `switch`) se validan semánticamente (cada bloque `{ }` tiene
 * su propio ámbito), pero no forman parte del árbol abstracto ni del código intermedio: la teoría de la clase no definió
 * su representación. Cada una deja una observación ([SemanticResult.notes]) y las variables de afuera que modifica
 * quedan con valor desconocido (una letra) a partir de ahí.
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

    /**
     * Ámbito de nombres. El método (o `main`) es la raíz; cada bloque `{ }` de `if`, `while`, `do`, `for` y `switch`
     * abre un ámbito hijo: lo que se declara dentro deja de existir al cerrar la llave, como en Java.
     *
     * - [find] busca del bloque actual hacia afuera.
     * - Java no permite volver a declarar una variable local de un bloque exterior, así que la redeclaración se revisa
     *   en toda la cadena; dos bloques hermanos (`if { int y; } else { int y; }`) sí pueden usar el mismo nombre.
     * - [initialized] guarda lo que se inicializó en este bloque; al cerrarlo, solo pasa al exterior lo que Java
     *   considera "asignación definitiva" (ver [checkStatement]).
     */
    private class Scope(val label: String, val method: MethodDecl?, val parent: Scope? = null) {
        val names = LinkedHashMap<String, Binding>()
        val initialized = HashSet<Binding>()

        /** Variables cuyo "se usa sin inicializar" ya se reportó (una sola vez por variable en todo el método). */
        private val reportedUninitialized: HashSet<Binding> = parent?.reportedUninitialized ?: HashSet()

        fun child(): Scope = Scope(label, method, this)

        fun find(name: String): Binding? {
            var current: Scope? = this
            while (current != null) {
                current.names[name]?.let { return it }
                current = current.parent
            }
            return null
        }

        fun isInitialized(b: Binding): Boolean {
            var current: Scope? = this
            while (current != null) {
                if (b in current.initialized) return true
                current = current.parent
            }
            return false
        }

        fun markInitialized(b: Binding) {
            initialized += b
        }

        /** `true` la primera vez que se pide reportar a [b] como no inicializada. */
        fun shouldReportUninitialized(b: Binding): Boolean = reportedUninitialized.add(b)
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
            resolvedTokenSymbols = finalIds,
            notes = builder.notes
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
    // 1. Declaraciones

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

    // 2. Validaciones semánticas

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
                if (scope.find(s.name.lexeme) != null) {
                    semanticError(s.name, AcademicErrorTemplates.redeclaredIdentifier(s.name.lexeme, scope.label))
                    s.init?.let { checkExpr(it, scope) }
                    return
                }
                val init = s.init
                val info = if (init != null) checkExpr(init, scope) else null
                val b = declare(s.name, SymbolRole.VARIABLE, typeName, type, scope.label)
                b.localInit = init
                scope.names[s.name.lexeme] = b
                if (init != null && info != null) {
                    checkAssignment(s.name, typeName, type, b.className, info, init)
                    scope.markInitialized(b)
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
                        // El destino también es un uso del símbolo: sin esto la tabla léxica no le pone su IDn y el
                        // árbol semántico no encuentra la asignación (la descartaba y se seguía usando el valor previo).
                        tokenBinding[s.target.span.start.offset] = target
                        checkAssignment(s.target, target.typeName, target.type, target.className, info, s.value)
                        scope.markInitialized(target)
                    }
                }
            }
            is ObjDeclStmt -> {
                checkObjectCreation(s)
                if (scope.find(s.name.lexeme) != null) {
                    semanticError(s.name, AcademicErrorTemplates.redeclaredIdentifier(s.name.lexeme, scope.label))
                    return
                }
                val (typeName, type) = s.classToken.lexeme to SemanticDataType.CLASS_INSTANCE
                val b = declare(s.name, SymbolRole.OBJECT, typeName, type, scope.label)
                b.obj = s
                scope.names[s.name.lexeme] = b
                scope.markInitialized(b)
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
            // Cada cuerpo es un bloque con ámbito propio. Asignación definitiva (reglas de Java): después de un
            // if/else una variable queda inicializada solo si ambos brazos la inicializan; un do-while ejecuta su cuerpo
            // al menos una vez; un while, un for o un if sin else pueden no ejecutarse, así que no inicializan nada afuera.
            is IfStmt -> {
                checkCondition("IFEL", s.condition, scope)
                val thenScope = scope.child().also { checkStatements(s.thenBody, it) }
                val elseBody = s.elseBody
                if (elseBody != null) {
                    val elseScope = scope.child().also { checkStatements(elseBody, it) }
                    thenScope.initialized.filter { it in elseScope.initialized }.forEach { scope.markInitialized(it) }
                }
            }
            is WhileStmt -> {
                checkCondition("WHILE", s.condition, scope)
                checkStatements(s.body, scope.child())
            }
            is DoWhileStmt -> {
                val bodyScope = scope.child().also { checkStatements(s.body, it) }
                bodyScope.initialized.forEach { scope.markInitialized(it) }
                checkCondition("DO", s.condition, scope)
            }
            is ForStmt -> {
                // La variable de control (`for (int i = 0; ...)`) solo existe dentro del for.
                val forScope = scope.child()
                s.init?.let { checkStatement(it, forScope) }
                s.condition?.let { checkCondition("FOR", it, forScope) }
                s.update?.let { checkExpr(it, forScope) }
                checkStatements(s.body, forScope.child())
            }
            is SwitchStmt -> {
                val sel = checkExpr(s.selector, scope)
                if (sel.type != SemanticDataType.UNKNOWN && !sel.type.isIntegral && sel.type != SemanticDataType.STRING) {
                    semanticError(firstToken(s.selector), "El TD ${sel.display} no puede evaluarse en un SWITCH (se espera int, char o String)")
                }
                // En Java todos los case comparten el bloque { } del switch.
                val switchScope = scope.child()
                for (c in s.cases) {
                    c.value?.let { checkExpr(it, scope) }
                    checkStatements(c.body, switchScope)
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
        scope.find(token.lexeme)?.let { return it }
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
                if (!b.isClassLevel && b.role == SymbolRole.VARIABLE && !scope.isInitialized(b) &&
                    scope.shouldReportUninitialized(b) // un solo reporte por variable
                ) {
                    semanticError(token, AcademicErrorTemplates.uninitializedVariable(token.lexeme))
                }
                TypeInfo(b.type, className = b.className)
            }
        }
    }

    private fun checkAccess(expr: AccessExpr, scope: Scope, fieldLimit: Int?): TypeInfo {
        val member = expr.member.lexeme
        val target = expr.target
        // Métodos de Java sin objeto: Math.random(), Integer.parseInt(...), Double.parseDouble(...)
        if (target is NameExpr && target.token.lexeme in JavaApi.STATIC_CLASSES &&
            resolveName(target.token, scope, fieldLimit) == null
        ) {
            val cls = target.token.lexeme
            val argInfos = expr.args?.map { checkExpr(it, scope, fieldLimit) }
            val spec = JavaApi.staticMethod(cls, member)
            return when {
                expr.args == null && JavaApi.staticField(cls, member) != null -> TypeInfo(SemanticDataType.DOUBLE)
                expr.args != null && spec != null -> javaMethodType(spec, cls, expr, argInfos.orEmpty())
                else -> {
                    semanticError(expr.member, AcademicErrorTemplates.memberNotFound(member, cls))
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
                val argInfos = expr.args?.map { checkExpr(it, scope, fieldLimit) }
                val method = JavaApi.STRING_METHODS[member]
                when {
                    expr.args == null -> {
                        semanticError(expr.member, AcademicErrorTemplates.stringAttributeNotFound(member))
                        TypeInfo(SemanticDataType.UNKNOWN)
                    }
                    method == null -> {
                        semanticError(expr.member, AcademicErrorTemplates.stringMethodNotFound(member))
                        TypeInfo(SemanticDataType.UNKNOWN)
                    }
                    else -> javaMethodType(method, "String", expr, argInfos.orEmpty())
                }
            }
            targetInfo.type == SemanticDataType.CLASS_INSTANCE && targetInfo.className == "Scanner" -> {
                val argInfos = expr.args?.map { checkExpr(it, scope, fieldLimit) }
                val method = JavaApi.SCANNER_METHODS[member]
                when {
                    expr.args == null || method == null -> {
                        semanticError(expr.member, AcademicErrorTemplates.memberNotFound(member, "Scanner"))
                        TypeInfo(SemanticDataType.UNKNOWN)
                    }
                    else -> javaMethodType(method, "Scanner", expr, argInfos.orEmpty())
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

    /** Tipo de retorno de un método de Java; reporta si la cantidad de argumentos no corresponde. */
    private fun javaMethodType(spec: JavaApi.MethodSpec, owner: String, expr: AccessExpr, argInfos: List<TypeInfo>): TypeInfo {
        if (!spec.acceptsArgCount(argInfos.size)) {
            semanticError(
                expr.member,
                AcademicErrorTemplates.javaArgumentCountMismatch(owner, spec.name, spec.expectedArgsText, argInfos.size)
            )
            return TypeInfo(SemanticDataType.UNKNOWN)
        }
        return TypeInfo(spec.returnType(argInfos.map { it.type }))
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

    // 3. Numeración definitiva de la Tabla de Símbolos

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
    // 4. Árbol Abstracto-Semántico

    /** Contexto de evaluación perezosa de un método invocado (parámetros con su argumento, locales con su valor). */
    private class Frame(
        val method: MethodDecl?,
        val methodBinding: Binding?,
        val params: Map<Binding, Pair<Expr, Frame>>,
        val locals: Map<Binding, Pair<Expr, Frame>>,
        val depth: Int,
        val isMain: Boolean,
        val materialized: MutableMap<Binding, SemanticAstNode>,
        /**
         * Variables modificadas dentro de una estructura de control (que no se representa): desde ahí su valor no se
         * conoce al compilar y se usa una letra (`x`, `y`…), como los valores desconocidos de la U3.
         */
        val opaque: Map<Binding, EvaluatedValue> = emptyMap()
    )

    private inner class TreeBuilder {
        private var nextId = 1
        private var nodeCount = 0
        private var limitReported = false
        private val fieldsInExpansion = HashSet<Binding>()
        private val classFrame = Frame(null, null, emptyMap(), emptyMap(), 0, false, HashMap())

        /** Variables del main que quedaron con valor desconocido tras una estructura de control. */
        private val mainOpaque = HashMap<Binding, EvaluatedValue>()

        /** Pasos de `a++` usados dentro de una instrucción del main; se agregan al árbol antes de ella. */
        private val pendingIncrements = ArrayList<SemanticAstNode>()

        /** Incrementos del main ya aplicados (por posición del operador): valor anterior y paso del incremento. */
        private val appliedIncrements = HashMap<Int, Pair<SemanticAstNode, SemanticAstNode>>()

        /** Observaciones sin repetir (una por tipo y posición), en orden de aparición. */
        private val noteMap = LinkedHashMap<Pair<CompilerNoteKind, Int>, CompilerNote>()

        val notes: List<CompilerNote>
            get() = noteMap.values.sortedWith(compareBy({ it.line }, { it.column }))

        private fun note(kind: CompilerNoteKind, message: String, span: SourceSpan) {
            noteMap.getOrPut(kind to span.start.offset) { CompilerNote(kind, message, span) }
        }

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
                val frame = Frame(null, null, emptyMap(), emptyMap(), 0, true, HashMap(), mainOpaque)
                for (s in main.body) {
                    val node = statementNode(s, frame, owner = null)
                    // `y = x++;`: el paso del incremento va antes de la instrucción (como `iinc` en Java) y la
                    // instrucción sigue usando el valor anterior de x, así ninguna referencia apunta hacia adelante.
                    children += pendingIncrements
                    pendingIncrements.clear()
                    node?.let { children += it }
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
                // Un valor que el sintáctico no pudo derivar completo (ErrorExpr) no se representa: el ID queda sin valor.
                if (b == null || init == null || init is ErrorExpr || !frame.isMain) null
                else assignment(b, expand(init, frame), s.span).also { frame.materialized[b] = it }
            }
            is AssignStmt -> {
                val b = tokenBinding[s.target.span.start.offset]
                when {
                    b == null || !frame.isMain -> null
                    // Asignación incompleta (`y = a ? 1 : 0;`, `x += 4;`): desde aquí el valor del ID es desconocido.
                    s.value is ErrorExpr -> {
                        frame.materialized.remove(b)
                        mainOpaque[b] = EvaluatedValue.UnknownVal
                        null
                    }
                    else -> assignment(b, expand(s.value, frame), s.span).also {
                        frame.materialized[b] = it
                        mainOpaque.remove(b)
                    }
                }
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
            is ExprStmt -> {
                // `a++;` / `--a;` como instrucción del main: los usos siguientes de `a` reutilizan este paso.
                // (Como instrucción, `a++` y `++a` son lo mismo: no hay un valor anterior que se use.)
                val incremented = incrementedBinding(s.expr)
                if (frame.isMain && incremented != null) {
                    incrementNode(s.expr as UnaryExpr, frame).also {
                        frame.materialized[incremented] = it
                        mainOpaque.remove(incremented)
                    }
                } else {
                    expand(s.expr, frame)
                }
            }
            is IfStmt, is WhileStmt, is DoWhileStmt, is ForStmt, is SwitchStmt -> {
                if (frame.isMain) {
                    for ((b, value) in opaqueValuesFor(s)) {
                        frame.materialized.remove(b)
                        mainOpaque[b] = value
                    }
                }
                null
            }
            else -> null
        }

        /**
         * Separa los `++`/`--` sobre variables que hay dentro de [expr] (para los métodos, que sustituyen valores):
         * regresa la expresión con cada postfijo `a++` cambiado por `a` (Java usa el valor anterior) y cada prefijo
         * intacto, más la lista de incrementos en forma prefija para aplicarlos después de la instrucción.
         */
        private fun splitIncrements(expr: Expr): Pair<Expr, List<UnaryExpr>> {
            val increments = ArrayList<UnaryExpr>()
            fun walk(e: Expr): Expr = when (e) {
                is UnaryExpr -> {
                    val isStep = e.operator.category == TokenCategory.INC || e.operator.category == TokenCategory.DEC
                    when {
                        isStep && e.operand is NameExpr -> {
                            increments += if (e.isPrefix) e else e.copy(isPrefix = true)
                            if (e.isPrefix) e else e.operand
                        }
                        else -> e.copy(operand = walk(e.operand))
                    }
                }
                is BinaryExpr -> e.copy(left = walk(e.left), right = walk(e.right))
                is CastExpr -> e.copy(operand = walk(e.operand))
                is GroupExpr -> e.copy(inner = walk(e.inner))
                is NewExpr -> e.copy(args = e.args.map(::walk))
                is AccessExpr -> e.copy(target = walk(e.target), args = e.args?.map(::walk))
                is CallExpr -> e.copy(args = e.args.map(::walk))
                else -> e
            }
            val rewritten = walk(expr)
            return if (increments.isEmpty()) expr to emptyList() else rewritten to increments
        }

        /** Símbolo que incrementa o decrementa una instrucción `a++`, `++a`, `a--` o `--a` (si lo es). */
        private fun incrementedBinding(expr: Expr): Binding? {
            if (expr !is UnaryExpr) return null
            if (expr.operator.category != TokenCategory.INC && expr.operator.category != TokenCategory.DEC) return null
            val operand = expr.operand as? NameExpr ?: return null
            return tokenBinding[operand.token.span.start.offset]
        }

        /**
         * Registra la observación de una estructura de control y regresa el valor desconocido (una letra nueva si es
         * numérico) de cada variable que se modifica dentro de ella.
         */
        private fun opaqueValuesFor(s: Stmt): Map<Binding, EvaluatedValue> {
            val assigned = LinkedHashSet<Binding>()
            val declaredInside = HashSet<Binding>()
            collectAssigned(listOf(s), assigned, declaredInside)
            val values = LinkedHashMap<Binding, EvaluatedValue>()
            // Lo declarado dentro del bloque deja de existir al cerrarlo; solo importan las variables de afuera.
            for (b in assigned - declaredInside) {
                values[b] = if (b.type.isNumeric) symbolFor("control:${b.id}:${s.span.start.offset}", b.type)
                else EvaluatedValue.UnknownVal
            }
            val name = when (s) {
                is IfStmt -> "IFEL"
                is WhileStmt -> "WHILE"
                is DoWhileStmt -> "DO"
                is ForStmt -> "FOR"
                else -> "SWITCH"
            }
            val changed = if (values.isEmpty()) "" else {
                val list = values.entries.joinToString(", ") { (b, v) ->
                    if (v is EvaluatedValue.SymbolicVal) "ID${b.id} (${b.name}) = ${v.formatForTreeAndQuadruple()}"
                    else "ID${b.id} (${b.name}) = ?"
                }
                " Las variables que modifica quedan con valor desconocido: $list."
            }
            note(
                CompilerNoteKind.CONTROL_FLOW_OMITTED,
                "La estructura $name se validó, pero no se representa en el árbol abstracto-semántico ni en el " +
                    "código intermedio (las láminas no definen su representación).$changed",
                s.span
            )
            return values
        }

        private fun collectAssigned(stmts: List<Stmt>, out: MutableSet<Binding>, declared: MutableSet<Binding>) {
            for (s in stmts) when (s) {
                is AssignStmt -> tokenBinding[s.target.span.start.offset]?.let { out += it }
                is ExprStmt -> incrementedBinding(s.expr)?.let { out += it }
                is VarDeclStmt -> tokenBinding[s.name.span.start.offset]?.let { declared += it }
                is ObjDeclStmt -> tokenBinding[s.name.span.start.offset]?.let { declared += it }
                is IfStmt -> {
                    collectAssigned(s.thenBody, out, declared)
                    s.elseBody?.let { collectAssigned(it, out, declared) }
                }
                is WhileStmt -> collectAssigned(s.body, out, declared)
                is DoWhileStmt -> collectAssigned(s.body, out, declared)
                is ForStmt -> {
                    s.init?.let { collectAssigned(listOf(it), out, declared) }
                    s.update?.let { u -> incrementedBinding(u)?.let { out += it } }
                    collectAssigned(s.body, out, declared)
                }
                is SwitchStmt -> s.cases.forEach { c: SwitchCase -> collectAssigned(c.body, out, declared) }
                else -> Unit
            }
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
                    val type = SemanticDataType.binaryResultType(l.resolvedType, op, r.resolvedType)
                    if ((op == "/" || op == "%") && type.isIntegral && r.evaluatedValue.isConstant &&
                        r.evaluatedValue.asDoubleOrNull() == 0.0
                    ) {
                        note(
                            CompilerNoteKind.DIVISION_BY_ZERO,
                            "La operación ${sourceText(expr)} divide un entero entre cero: Java la compila, pero al " +
                                "ejecutarse lanza ArithmeticException, así que su resultado queda desconocido (?).",
                            expr.span
                        )
                    }
                    val value = unknownResult(op, l, r, ValueOps.binary(op, l.evaluatedValue, r.evaluatedValue), type)
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
                frame.opaque.containsKey(b) -> leaf(
                    SemanticNodeKind.IDENTIFIER_REF, "ID${b.id}", frame.opaque.getValue(b), token.span,
                    type = b.type, symbolId = b.id
                )
                // Lo asignado dentro del método (local, parámetro o atributo) gana sobre su valor inicial.
                frame.locals.containsKey(b) -> {
                    val (valueExpr, declFrame) = frame.locals.getValue(b)
                    assignment(b, expand(valueExpr, declFrame), token.span)
                }
                b.isClassLevel && b.field != null -> fieldNode(b, token.span)
                frame.params.containsKey(b) -> {
                    val (argExpr, callerFrame) = frame.params.getValue(b)
                    assignment(b, expand(argExpr, callerFrame), token.span)
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

        /**
         * Paso de `++a` / `a--`: `(1, +, a)` o `(a, -, 1)` (U2, clase A: `int uno = ++a;` → `(1, +, ID2)`). En la resta
         * el 1 va a la derecha porque no es conmutativa.
         */
        private fun incrementNode(expr: UnaryExpr, frame: Frame): SemanticAstNode {
            val op = expr.operator
            val operand = expand(expr.operand, frame)
            val one = leaf(SemanticNodeKind.LITERAL_VALUE, "1", EvaluatedValue.IntVal(1), op.span)
            val sym = if (op.category == TokenCategory.INC) "+" else "-"
            val children = if (sym == "+") listOf(one, operand) else listOf(operand, one)
            val value = ValueOps.coerce(
                ValueOps.binary(sym, children[0].evaluatedValue, children[1].evaluatedValue),
                operand.resolvedType
            )
            return SemanticAstNode(
                id = id(), kind = SemanticNodeKind.BINARY_OPERATOR, primaryLabel = sym,
                resolvedType = operand.resolvedType, evaluatedValue = value, children = children, span = expr.span
            )
        }

        /**
         * `++a` / `a++` dentro de una expresión del main (`int y = a++;`, `println(++a)`): además de su valor, el
         * incremento cambia a `a` para las instrucciones siguientes, como en Java.
         * - Prefijo: la expresión usa el valor ya incrementado (el paso queda dentro de la expresión).
         * - Postfijo: la expresión usa el valor anterior; el paso del incremento se agrega al árbol antes de la
         *   instrucción (ver `buildRoot`).
         */
        private fun expandIncrementInMain(expr: UnaryExpr, b: Binding, frame: Frame): SemanticAstNode {
            val key = expr.operator.span.start.offset
            // Un argumento se expande cada vez que el método usa su parámetro: el incremento se aplica una sola vez.
            appliedIncrements[key]?.let { (before, step) ->
                val target = if (expr.isPrefix) step else before
                // Una hoja se puede repetir con un id nuevo; un paso con hijos se referencia (como `[n]`).
                return if (target.children.isEmpty()) target.copy(id = id())
                else leaf(
                    SemanticNodeKind.IDENTIFIER_REF, "ID${b.id}", target.evaluatedValue, expr.span,
                    type = target.resolvedType, symbolId = b.id, referencedNodeId = target.id
                )
            }
            val before = expand(expr.operand, frame)
            val step = incrementNode(expr, frame)
            appliedIncrements[key] = before to step
            frame.materialized[b] = step
            mainOpaque.remove(b)
            if (expr.isPrefix) return step
            pendingIncrements += step
            return before
        }

        private fun expandUnary(expr: UnaryExpr, frame: Frame): SemanticAstNode {
            val op = expr.operator
            return when (op.category) {
                TokenCategory.INC, TokenCategory.DEC -> {
                    val b = incrementedBinding(expr)
                    if (frame.isMain && b != null) expandIncrementInMain(expr, b, frame) else incrementNode(expr, frame)
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
            // Métodos de Java sin objeto: Math.random(), Integer.parseInt(...), Double.parseDouble(...)
            if (target is NameExpr && target.token.lexeme in JavaApi.STATIC_CLASSES &&
                tokenBinding[target.token.span.start.offset] == null
            ) {
                val cls = target.token.lexeme
                val classLeaf = leaf(SemanticNodeKind.IDENTIFIER_REF, cls, EvaluatedValue.UnknownVal, target.span)
                val args = expr.args?.map { expand(it, frame) }
                val spec = JavaApi.staticMethod(cls, member)
                val label = memberLabel(member, expr.args, args)
                val type: SemanticDataType
                val value: EvaluatedValue
                if (args == null) {
                    value = JavaApi.staticField(cls, member) ?: EvaluatedValue.UnknownVal
                    type = value.dataType
                } else {
                    type = spec?.returnType(args.map { it.resolvedType.takeIf { t -> t != SemanticDataType.UNKNOWN } ?: it.evaluatedValue.dataType })
                        ?: SemanticDataType.UNKNOWN
                    value = JavaApi.evaluateStatic(cls, member, args.map { it.evaluatedValue })
                        ?: if (spec != null && spec.acceptsArgCount(args.size) &&
                            type != SemanticDataType.UNKNOWN && type != SemanticDataType.VOID
                        ) {
                            symbolFor("$cls.$label", type)
                        } else {
                            EvaluatedValue.UnknownVal
                        }
                }
                val memberLeaf = memberNode(member, label, value, type, args, expr.member.span)
                return SemanticAstNode(
                    id = id(), kind = SemanticNodeKind.MEMBER_ACCESS, primaryLabel = ".",
                    resolvedType = type, evaluatedValue = value,
                    children = listOf(classLeaf, memberLeaf), span = expr.span
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
            // Métodos de Java sobre un objeto: "LOL".length(), s.charAt(0), sc.nextLine(), new Scanner(System.in).next()
            val targetNode = if (target is NewExpr && target.classToken.lexeme != className) {
                // Como en las láminas: (new Scanner, ., nextLine(), x)
                leaf(
                    SemanticNodeKind.IDENTIFIER_REF, "new ${target.classToken.lexeme}",
                    EvaluatedValue.ObjectVal(target.classToken.lexeme), target.span, type = SemanticDataType.CLASS_INSTANCE
                )
            } else {
                expand(target, frame)
            }
            val receiverValue = targetNode.evaluatedValue
            val receiverClass = when {
                receiverValue is EvaluatedValue.ObjectVal -> receiverValue.className
                receiverValue is EvaluatedValue.StringVal || targetNode.resolvedType == SemanticDataType.STRING ||
                    receiverValue.dataType == SemanticDataType.STRING -> "String"
                else -> null
            }
            val args = expr.args?.map { expand(it, frame) }
            val spec = if (args != null) JavaApi.instanceMethod(receiverClass, member) else null
            val label = memberLabel(member, expr.args, args)
            val type = spec?.returnType(args.orEmpty().map { it.resolvedType }) ?: SemanticDataType.UNKNOWN
            val value: EvaluatedValue = when {
                spec == null || !spec.acceptsArgCount(args.orEmpty().size) -> EvaluatedValue.UnknownVal
                else -> JavaApi.evaluateInstance(receiverClass, receiverValue, member, args.orEmpty().map { it.evaluatedValue })
                    ?: if (type != SemanticDataType.VOID) symbolFor("${valueKey(targetNode)}.$label", type) else EvaluatedValue.UnknownVal
            }
            val memberLeaf = memberNode(member, label, value, type, args, expr.member.span)
            return SemanticAstNode(
                id = id(), kind = SemanticNodeKind.MEMBER_ACCESS, primaryLabel = ".",
                resolvedType = type, evaluatedValue = value, children = listOf(targetNode, memberLeaf), span = expr.span
            )
        }

        /**
         * Miembro de un acceso a Java: atributo (`PI`) o método con sus argumentos como hijos (`charAt(0)`); los
         * argumentos que son operaciones (`sc.nextLine()` dentro de `Integer.parseInt(...)`) se calculan antes.
         */
        private fun memberNode(
            member: String,
            label: String,
            value: EvaluatedValue,
            type: SemanticDataType,
            args: List<SemanticAstNode>?,
            span: SourceSpan
        ): SemanticAstNode = SemanticAstNode(
            id = id(),
            kind = SemanticNodeKind.IDENTIFIER_REF,
            primaryLabel = label,
            resolvedType = type,
            evaluatedValue = value,
            children = args.orEmpty(),
            span = span,
            callName = if (args != null) member else null
        )

        //Valores desconocidos: letras x, y, z…

        /** Letra de cada expresión desconocida; la misma expresión (`Math.random()`, `sc.nextLine()`) usa la misma letra. */
        private val symbolLetters = LinkedHashMap<String, String>()

        private fun symbolFor(key: String, type: SemanticDataType): EvaluatedValue.SymbolicVal {
            val letter = symbolLetters.getOrPut(key) { EvaluatedValue.SymbolicVal.letterAt(symbolLetters.size) }
            return EvaluatedValue.SymbolicVal.of(letter, type)
        }

        /** Texto que identifica el valor de un nodo (para reconocer expresiones repetidas). */
        private fun valueKey(node: SemanticAstNode): String {
            val v = node.evaluatedValue
            return if (v is EvaluatedValue.SymbolicVal || v.isConstant) v.formatForTreeAndQuadruple() else node.primaryLabel
        }

        /**
         * Resultado de una operación entre valores desconocidos que no es lineal (`x*y`, `x/y`, `s + x`, `x > 5`): toma
         * su propia letra, con el tipo del resultado.
         */
        private fun unknownResult(op: String, l: SemanticAstNode, r: SemanticAstNode, value: EvaluatedValue, type: SemanticDataType): EvaluatedValue {
            if (value != EvaluatedValue.UnknownVal) return value
            if (l.evaluatedValue !is EvaluatedValue.SymbolicVal && r.evaluatedValue !is EvaluatedValue.SymbolicVal) return value
            if (type == SemanticDataType.UNKNOWN || type == SemanticDataType.VOID) return value
            return symbolFor("(${valueKey(l)})$op(${valueKey(r)})", type)
        }

        /** Etiqueta del método en el código intermedio, con sus argumentos: `charAt(0)`, `substring(0,4)`, `nextLine()`. */
        private fun memberLabel(member: String, argExprs: List<Expr>?, args: List<SemanticAstNode>?): String {
            if (argExprs == null || args == null) return member
            return argExprs.indices.joinToString(",", "$member(", ")") { i -> argText(argExprs[i], args[i]) }
        }

        private fun argText(expr: Expr, node: SemanticAstNode): String = when {
            expr is LiteralExpr -> expr.token.lexeme
            node.evaluatedValue.isConstant || node.evaluatedValue is EvaluatedValue.SymbolicVal ->
                node.evaluatedValue.formatForTreeAndQuadruple()
            else -> sourceText(expr)
        }

        /** Texto compacto de una expresión (`System.in`, `ID3+1`). */
        private fun sourceText(expr: Expr): String = when (expr) {
            is LiteralExpr -> expr.token.lexeme
            is NameExpr -> tokenBinding[expr.token.span.start.offset]?.let { "ID${it.id}" } ?: expr.token.lexeme
            is GroupExpr -> "(${sourceText(expr.inner)})"
            is BinaryExpr -> "${sourceText(expr.left)}${expr.operator.lexeme}${sourceText(expr.right)}"
            is UnaryExpr -> if (expr.isPrefix) "${expr.operator.lexeme}${sourceText(expr.operand)}" else "${sourceText(expr.operand)}${expr.operator.lexeme}"
            is CastExpr -> "(${expr.typeToken.lexeme})${sourceText(expr.operand)}"
            is NewExpr -> "new ${expr.classToken.lexeme}()"
            is AccessExpr -> "${sourceText(expr.target)}.${expr.member.lexeme}" +
                (expr.args?.joinToString(",", "(", ")") { sourceText(it) } ?: "")
            is CallExpr -> "${expr.name.lexeme}(${expr.args.joinToString(",") { sourceText(it) }})"
            is ErrorExpr -> "?"
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
            if (callerFrame.depth >= MAX_CALL_DEPTH) {
                note(
                    CompilerNoteKind.CALL_DEPTH_LIMIT,
                    "Las llamadas a ${m.name.lexeme} se anidan más de $MAX_CALL_DEPTH niveles (recursión): el árbol " +
                        "y el código intermedio se cortan en ese nivel y el valor que regresa queda desconocido (?).",
                    span
                )
                return listOf(unknownLeaf("ID${mb.id}", span))
            }
            val params = LinkedHashMap<Binding, Pair<Expr, Frame>>()
            m.params.forEachIndexed { i, p ->
                val pb = tokenBinding[p.name.span.start.offset] ?: return@forEachIndexed
                args.getOrNull(i)?.let { params[pb] = it to callerFrame }
            }
            var locals: Map<Binding, Pair<Expr, Frame>> = emptyMap()
            var opaque: Map<Binding, EvaluatedValue> = emptyMap()
            // Cada declaración o asignación guarda el contexto de ESE momento (evaluación perezosa): una instrucción
            // posterior no cambia el valor con el que se calculó.
            fun currentFrame() = Frame(m, mb, params, locals, callerFrame.depth + 1, false, HashMap(), opaque)

            // `y = x++;` / `println(++x);` dentro del método: después de la instrucción, `x` vale `++x` (calculado con
            // el contexto anterior a ella).
            fun applyIncrements(increments: List<UnaryExpr>, frame0: Frame) {
                for (inc in increments) {
                    val ib = incrementedBinding(inc) ?: continue
                    locals = locals + (ib to (inc to frame0))
                    opaque = opaque - ib
                }
            }

            val nodes = ArrayList<SemanticAstNode>()
            for (s in m.body) {
                when (s) {
                    is VarDeclStmt -> {
                        val b = tokenBinding[s.name.span.start.offset]
                        val init = s.init
                        if (b != null && init is ErrorExpr) {
                            // Valor que el sintáctico no pudo derivar completo: el ID queda con valor desconocido.
                            locals = locals - b
                            opaque = opaque + (b to EvaluatedValue.UnknownVal)
                        } else if (b != null && init != null) {
                            val frame0 = currentFrame()
                            val (value, increments) = splitIncrements(init)
                            locals = locals + (b to (value to frame0))
                            opaque = opaque - b
                            applyIncrements(increments, frame0)
                        }
                    }
                    is AssignStmt -> {
                        // Locales, parámetros y atributos: desde aquí el método usa el valor asignado.
                        val b = tokenBinding[s.target.span.start.offset]
                        if (b != null && s.value is ErrorExpr) {
                            locals = locals - b
                            opaque = opaque + (b to EvaluatedValue.UnknownVal)
                        } else if (b != null) {
                            val frame0 = currentFrame()
                            val (value, increments) = splitIncrements(s.value)
                            locals = locals + (b to (value to frame0))
                            opaque = opaque - b
                            applyIncrements(increments, frame0)
                        }
                    }
                    is SoutStmt -> {
                        val argument = s.argument
                        if (argument == null) {
                            statementNode(s, currentFrame(), owner = mb)?.let { nodes += it }
                        } else {
                            val frame0 = currentFrame()
                            val (value, increments) = splitIncrements(argument)
                            statementNode(s.copy(argument = value), frame0, owner = mb)?.let { nodes += it }
                            applyIncrements(increments, frame0)
                        }
                    }
                    is ExprStmt -> {
                        val incremented = incrementedBinding(s.expr)
                        if (incremented != null) {
                            // `a++;` dentro de un método equivale a `a = a + 1;`: se sustituye donde se use `a`.
                            locals = locals + (incremented to (s.expr to currentFrame()))
                            opaque = opaque - incremented
                        } else {
                            statementNode(s, currentFrame(), owner = mb)?.let { nodes += it }
                        }
                    }
                    is IfStmt, is WhileStmt, is DoWhileStmt, is ForStmt, is SwitchStmt -> {
                        val values = opaqueValuesFor(s)
                        locals = locals - values.keys
                        opaque = opaque + values
                    }
                    else -> Unit
                }
            }
            val ret = m.returnStmt
            // `return x++;` regresa el valor anterior de x (el incremento ya no se usa).
            val retValue = ret?.value?.let { splitIncrements(it).first }
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
        val JAVA_API_NAMES = KNOWN_JAVA_CLASSES + JavaApi.ALL_NAMES
    }
}
