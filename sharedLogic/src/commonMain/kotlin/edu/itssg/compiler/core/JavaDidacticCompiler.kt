package edu.itssg.compiler.core

import edu.itssg.compiler.core.error.CompilerNote
import edu.itssg.compiler.core.error.CompilerError
import edu.itssg.compiler.core.error.ErrorPhaseType
import edu.itssg.compiler.core.error.ErrorReporter
import edu.itssg.compiler.core.ir.IntermediateCodeBundle
import edu.itssg.compiler.core.ir.IntermediateCodeGenerator
import edu.itssg.compiler.core.lexer.Lexer
import edu.itssg.compiler.core.lexer.Token
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.core.optimizer.CodeOptimizer
import edu.itssg.compiler.core.optimizer.OptimizationResult
import edu.itssg.compiler.core.parser.Parser
import edu.itssg.compiler.core.parser.SyntaxTreeNode
import edu.itssg.compiler.core.security.CompilerSecurityConfig
import edu.itssg.compiler.core.semantic.SemanticAnalyzer
import edu.itssg.compiler.core.semantic.SemanticAstNode
import edu.itssg.compiler.core.symboltable.LexicalTableRow
import edu.itssg.compiler.core.symboltable.SymbolEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Snapshot inmutable y completo del resultado de ejecutar todas las fases del
 * Compilador Didáctico de Java (GLC v7).
 *
 * Diseñado para ser consumido directamente por los `ViewModels` / `StateHolders`
 * de Compose Multiplatform en Android, iOS y Desktop.
 */
data class CompilationReport(
    val sourceCode: String,
    val tokens: List<Token>,
    val lexicalTableRows: List<LexicalTableRow>,
    val symbolEntries: List<SymbolEntry>,
    val errors: List<CompilerError>,
    val syntaxTree: SyntaxTreeNode.NonTerminal,
    val semanticTree: SemanticAstNode,
    val principalExpressionNotations: List<String>,
    val intermediateCode: IntermediateCodeBundle,
    val optimizationResult: OptimizationResult,
    val quotaAbortedMessage: String? = null,
    /** Observaciones didácticas que no son errores ni bloquean el código intermedio ([CompilerNote]). */
    val notes: List<CompilerNote> = emptyList()
) {
    val hasErrors: Boolean
        get() = errors.isNotEmpty() || quotaAbortedMessage != null

    val lexicalErrorCount: Int
        get() = errors.count { it.type == ErrorPhaseType.LEXICAL }

    val syntacticErrorCount: Int
        get() = errors.count { it.type == ErrorPhaseType.SYNTACTIC }

    val semanticErrorCount: Int
        get() = errors.count { it.type == ErrorPhaseType.SEMANTIC }
}

/**
 * Fachada y Orquestador Principal del Motor del Compilador (Pipeline Completo).
 *
 * Ejecuta secuencialmente y fuera del hilo principal (UI Thread):
 * 1. Análisis Léxico (`Lexer`) -> Limpieza de código y Tabla de Tokens/Símbolos (`ID1`, `ID2`...).
 * 2. Análisis Sintáctico (`Parser` GLC v7) -> Validación gramatical y Árbol Sintáctico.
 * 3. Análisis Semántico (`SemanticAnalyzer`) -> Validación de tipos (`TD`), constructores, ámbitos y Árbol Abstracto-Semántico.
 * 4. Generación de Código Intermedio (`IntermediateCodeGenerator`) -> Triplos, Cuádruplos y Tres Direcciones (`T1..T3`).
 * 5. Optimización Local (`CodeOptimizer`) -> Simplificación (`SE`), Reducción de subexpresiones (`RS`), Folding (`FO`),
 *    Propagación de constantes (`PC`) y Código Muerto (`CM`), con su variante derivada (`SED`, `RSD`, `FOD`, `PCD`, `CMD`).
 */
class JavaDidacticCompiler(
    private val securityConfig: CompilerSecurityConfig = CompilerSecurityConfig()
) {
    private val lexer = Lexer(config = securityConfig)
    private val parser = Parser(config = securityConfig)
    private val semanticAnalyzer = SemanticAnalyzer(config = securityConfig)
    private val irGenerator = IntermediateCodeGenerator()
    private val codeOptimizer = CodeOptimizer(irGenerator = irGenerator)

    /**
     * Ejecuta el pipeline de compilación de forma asíncrona y cooperativa en `Dispatchers.Default`,
     * garantizando cero bloqueos (ANR / UI Freeze) en la interfaz de Compose Multiplatform.
     */
    suspend fun compileAsync(
        sourceCode: String,
        useVSTAliasInTable: Boolean = false
    ): CompilationReport = withContext(Dispatchers.Default) {
        val context = coroutineContext
        context.ensureActive()
        // "Cancelar" en la interfaz se atiende entre fases: la fase en curso termina y las siguientes ya no corren.
        compileSynchronously(
            sourceCode = sourceCode,
            useVSTAliasInTable = useVSTAliasInTable,
            checkpoint = { context.ensureActive() }
        )
    }

    /**
     * Ejecución determinista síncrona (útil para pruebas unitarias en `commonTest`).
     *
     * @param checkpoint Se llama entre fases; [compileAsync] lo usa para detener la compilación cancelada
     * (lanza `CancellationException`). Por defecto no hace nada.
     */
    fun compileSynchronously(
        sourceCode: String,
        useVSTAliasInTable: Boolean = false,
        checkpoint: () -> Unit = {}
    ): CompilationReport {
        val errorReporter = ErrorReporter(config = securityConfig)

        // FASE 1: Análisis Léxico y Tabla de Símbolos inicial
        val lexerResult = lexer.tokenize(sourceCode)
        errorReporter.ingestLexicalAnomalies(lexerResult.lexicalAnomalies)

        checkpoint()

        // FASE 2: Análisis Sintáctico (GLC v7) y Árbol Sintáctico.
        // Si el léxico rechazó la fuente completa (\u0000 o tamaño excedido) no hay nada que derivar.
        val parserResult = if (lexerResult.sourceRejected) {
            parser.rejectedSourceResult()
        } else {
            parser.parse(
                tokens = lexerResult.tokens,
                errorReporter = errorReporter
            )
        }

        checkpoint()

        // FASE 3: Análisis Semántico, Árbol Abstracto-Semántico y numeración definitiva de la Tabla de Símbolos
        val semanticResult = semanticAnalyzer.analyze(
            program = parserResult.program,
            symbolTable = lexerResult.symbolTable,
            errorReporter = errorReporter
        )
        val finalTokens = renumberTokens(lexerResult.tokens, semanticResult.resolvedTokenSymbols)

        checkpoint()

        // FASE 4: Generación de Código Intermedio (Triplos, Cuádruplos y Tres Direcciones)
        val rawIrBundle = irGenerator.generate(semanticResult.abstractSemanticTree)

        checkpoint()

        // FASE 5: Optimización de Código Intermedio (Unidad 3)
        val optimizationResult = codeOptimizer.optimize(rawIrBundle)

        checkpoint()

        // Construir vistas tabulares inmutables
        val lexicalRows = lexerResult.symbolTable.buildLexicalTableRows(
            tokens = finalTokens,
            useVSTAlias = useVSTAliasInTable
        )

        return CompilationReport(
            sourceCode = sourceCode,
            tokens = finalTokens,
            lexicalTableRows = lexicalRows,
            symbolEntries = lexerResult.symbolTable.getAllSymbols(),
            errors = sortedByPosition(errorReporter.getAllErrors()),
            syntaxTree = parserResult.syntaxTree,
            semanticTree = semanticResult.abstractSemanticTree,
            principalExpressionNotations = semanticResult.principalExpressionNotations,
            intermediateCode = rawIrBundle,
            optimizationResult = optimizationResult,
            quotaAbortedMessage = lexerResult.quotaAbortedMessage,
            notes = semanticResult.notes
        )
    }

    /**
     * Tabla de Errores ordenada por línea y columna (como en las tablas de la Unidad 1); a igual posición se
     * respeta el orden de las fases (léxico, sintáctico, semántico). Se renumera la columna del índice.
     */
    private fun sortedByPosition(errors: List<CompilerError>): List<CompilerError> =
        errors.sortedWith(compareBy<CompilerError>({ it.line }, { it.column }, { it.type.ordinal }))
            .mapIndexed { i, e -> e.copy(index = i + 1) }


    private fun renumberTokens(tokens: List<Token>, finalIds: Map<Int, Int>): List<Token> {
        val seen = HashSet<Int>()
        return tokens.map { token ->
            if (token.category != TokenCategory.ID) return@map token
            val id = finalIds[token.span.start.offset]
            if (id == null) {
                token.copy(symbolId = null, isFirstDeclaration = false)
            } else {
                token.copy(symbolId = id, isFirstDeclaration = seen.add(id))
            }
        }
    }
}