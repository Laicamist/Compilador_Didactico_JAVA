package edu.itssg.compiler.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tabla de ajustes (`AppSetting`) y migración 1 → 2 sobre SQLite real, con el mismo driver que usa Desktop.
 * Ejecutar con `./gradlew :sharedLogic:jvmTest`.
 */
class AppSettingsPersistenceJvmTest {

    private val tempFiles = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempFiles.forEach { it.delete() }
    }

    private fun tempDatabase(): File =
        File.createTempFile("itssg_compiler_test", ".db").also { tempFiles += it }

    private fun providerFor(file: File) = CompilerDatabaseProvider {
        JdbcSqliteDriver(
            url = "jdbc:sqlite:${file.absolutePath}",
            properties = Properties().apply { put("foreign_keys", "true") },
            schema = CompilerDatabase.Schema
        )
    }

    private fun userVersion(file: File): Int =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }
        }

    private fun tableExists(file: File, table: String): Boolean =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.prepareStatement("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?").use { st ->
                st.setString(1, table)
                st.executeQuery().use { rs -> rs.next() && rs.getInt(1) == 1 }
            }
        }

    @Test
    fun elEsquemaActualEsLaVersion2() {
        assertEquals(2L, CompilerDatabase.Schema.version)
    }

    @Test
    fun baseNuevaIncluyeLaTablaDeAjustes() = runBlocking {
        val file = tempDatabase()
        val provider = providerFor(file)
        val settings = AppSettingsRepository(provider)

        assertEquals(emptyMap<String, String>(), settings.loadAll())
        provider.close()

        assertTrue(tableExists(file, "AppSetting"))
        assertEquals(2, userVersion(file))
    }

    @Test
    fun guardarReemplazaYConservaLosDemasAjustes() = runBlocking {
        val provider = providerFor(tempDatabase())
        val settings = AppSettingsRepository(provider)

        settings.saveAll(mapOf("theme_mode" to "DARK", "editor_font_size_sp" to "15"))
        settings.saveAll(mapOf("editor_font_size_sp" to "18", "tour_completed" to "true"))

        assertEquals(
            mapOf("theme_mode" to "DARK", "editor_font_size_sp" to "18", "tour_completed" to "true"),
            settings.loadAll()
        )
        provider.close()
    }

    @Test
    fun migraUnaBaseDeLaVersion1SinPerderElHistorial() = runBlocking {
        val file = tempDatabase()
        createVersion1Database(file)

        val provider = providerFor(file)
        val sessions = CompilerSessionRepository(provider)
        val settings = AppSettingsRepository(provider)

        val saved = sessions.getAllSavedSessions()
        assertEquals(listOf("Uno.java"), saved.map { it.title })

        settings.saveAll(mapOf("tour_completed" to "true"))
        assertEquals(mapOf("tour_completed" to "true"), settings.loadAll())
        provider.close()

        assertEquals(2, userVersion(file))
    }

    /** Base con el esquema de la versión 1 (antes de `AppSetting`) y una sesión guardada. */
    private fun createVersion1Database(file: File) {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE CompilationSession (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        title TEXT NOT NULL,
                        source_code TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        has_errors INTEGER NOT NULL DEFAULT 0 CHECK (has_errors IN (0, 1))
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE SymbolTableEntryEntity (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        session_id INTEGER NOT NULL,
                        symbol_index INTEGER,
                        lexeme TEXT NOT NULL,
                        token_code TEXT NOT NULL,
                        data_type TEXT,
                        scope TEXT,
                        line_number INTEGER NOT NULL,
                        column_number INTEGER NOT NULL,
                        FOREIGN KEY (session_id) REFERENCES CompilationSession(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE ErrorTableEntryEntity (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        session_id INTEGER NOT NULL,
                        error_type TEXT NOT NULL CHECK (error_type IN ('Léxico', 'Sintáctico', 'Semántico')),
                        lexeme TEXT NOT NULL,
                        line_number INTEGER NOT NULL,
                        column_number INTEGER NOT NULL,
                        justification TEXT NOT NULL,
                        FOREIGN KEY (session_id) REFERENCES CompilationSession(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IntermediateCodeEntity (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        session_id INTEGER NOT NULL,
                        representation_kind TEXT NOT NULL CHECK (representation_kind IN ('TRIPLE', 'QUADRUPLE', 'THREE_ADDRESS')),
                        is_optimized INTEGER NOT NULL DEFAULT 0 CHECK (is_optimized IN (0, 1)),
                        step_label TEXT NOT NULL,
                        val1 TEXT NOT NULL,
                        operator TEXT NOT NULL,
                        val2 TEXT NOT NULL,
                        result_value TEXT,
                        FOREIGN KEY (session_id) REFERENCES CompilationSession(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    "INSERT INTO CompilationSession(title, source_code, created_at, has_errors) " +
                            "VALUES ('Uno.java', 'public class Uno{}', 1000, 0)"
                )
                st.executeUpdate("PRAGMA user_version = 1")
            }
        }
    }
}