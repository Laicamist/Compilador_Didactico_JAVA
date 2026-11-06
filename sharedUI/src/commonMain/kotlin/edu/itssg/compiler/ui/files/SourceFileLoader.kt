package edu.itssg.compiler.ui.files

import edu.itssg.compiler.core.security.CompilerSecurityConfig

/**
 * Archivo elegido por el usuario, leído por el código de cada plataforma ([rememberSourceFilePicker]).
 * Las plataformas leen como máximo [SourceFileLoader.maxFileBytes] + 1 bytes, así un archivo enorme se
 * rechaza sin cargarlo completo en memoria.
 *
 * @property declaredSize Tamaño que informa el sistema de archivos, si se conoce.
 */
class PickedSourceFile(
    val name: String,
    val bytes: ByteArray,
    val declaredSize: Long? = null
)

/** Resultado de validar y decodificar un archivo de código. */
sealed interface SourceLoadResult {
    /**
     * @property title Nombre para la sesión (el nombre del archivo).
     * @property notes Avisos para el usuario (p. ej., la codificación que se usó).
     */
    data class Success(val title: String, val text: String, val notes: List<String> = emptyList()) : SourceLoadResult

    data class Failure(val message: String) : SourceLoadResult
}

/**
 * Carga segura de archivos de código fuente (Paso 3.2). Revisa en este orden:
 * 1. Extensión permitida (`.java` o `.txt`) y nombre razonable.
 * 2. Tamaño en bytes (antes de decodificar).
 * 3. Codificación: UTF-8 (con o sin BOM), UTF-16 con BOM (Bloc de notas "Unicode") o, si no es UTF-8 válido,
 *    Windows-1252 ("ANSI"), que es como guardan muchos editores en Windows los acentos y la ñ.
 * 4. Que sea texto: un byte nulo o muchos caracteres de control indican un archivo binario.
 * 5. Limpieza: saltos de línea `\r\n`/`\r` → `\n`, sin BOM ni caracteres de control sueltos.
 * 6. Límite de caracteres de [CompilerSecurityConfig.maxSourceLength], el mismo que aplica el compilador.
 */
object SourceFileLoader {

    /** Tope absoluto en bytes: los programas de la materia pesan unos cuantos KB. */
    const val MAX_FILE_BYTES: Int = 2 * 1024 * 1024

    val ALLOWED_EXTENSIONS: Set<String> = setOf("java", "txt")

    /** Tipos MIME que se ofrecen en el selector de Android (`.java` suele llegar como `text/x-java`). */
    val PICKER_MIME_TYPES: Array<String> = arrayOf("text/*", "application/octet-stream", "application/java")

    private const val MAX_NAME_LENGTH = 255

    /** Máximo de caracteres de control sueltos que se eliminan sin considerar binario el archivo. */
    private const val MAX_STRAY_CONTROL_CHARS = 16

    fun maxFileBytes(config: CompilerSecurityConfig): Int =
        minOf(MAX_FILE_BYTES.toLong(), config.maxSourceLength.toLong() * 4L).toInt()

    /** Mensaje de error si el nombre no es aceptable, o `null` si sí lo es. */
    fun validateName(name: String): String? {
        val clean = displayName(name)
        if (clean.isEmpty()) return "El archivo no tiene nombre."
        if (clean.length > MAX_NAME_LENGTH) return "El nombre del archivo es demasiado largo."
        val extension = clean.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension !in ALLOWED_EXTENSIONS) {
            return "Solo se pueden abrir archivos .java o .txt (se eligió “$clean”)."
        }
        return null
    }

    /** Mensaje de error si [size] bytes excede el límite, o `null`. Las plataformas lo usan antes de leer. */
    fun validateSize(size: Long, config: CompilerSecurityConfig): String? {
        val max = maxFileBytes(config)
        return if (size > max) "El archivo pesa ${formatSize(size)} y el máximo es ${formatSize(max.toLong())}." else null
    }

    fun load(file: PickedSourceFile, config: CompilerSecurityConfig): SourceLoadResult {
        validateName(file.name)?.let { return SourceLoadResult.Failure(it) }
        val size = maxOf(file.bytes.size.toLong(), file.declaredSize ?: 0L)
        validateSize(size, config)?.let { return SourceLoadResult.Failure(it) }
        if (file.bytes.isEmpty()) return SourceLoadResult.Failure("El archivo está vacío.")

        val notes = mutableListOf<String>()
        val decoded = decode(file.bytes, notes)
            ?: return SourceLoadResult.Failure("No se pudo leer el archivo como texto.")

        if (decoded.indexOf('\u0000') >= 0) {
            return SourceLoadResult.Failure("El archivo parece binario (contiene bytes nulos); elige un archivo de código.")
        }

        var text = decoded.removePrefix(BOM.toString())
        text = text.replace("\r\n", "\n").replace('\r', '\n')

        val strayControls = text.count { isStrayControl(it) }
        if (strayControls > MAX_STRAY_CONTROL_CHARS) {
            return SourceLoadResult.Failure("El archivo parece binario (tiene $strayControls caracteres de control).")
        }
        if (strayControls > 0) {
            text = text.filterNot { isStrayControl(it) }
            notes += "Se quitaron $strayControls caracteres de control invisibles."
        }

        if (text.length > config.maxSourceLength) {
            return SourceLoadResult.Failure(
                "El código tiene ${text.length} caracteres y el límite seguro es ${config.maxSourceLength}."
            )
        }
        if (text.isBlank()) return SourceLoadResult.Failure("El archivo no contiene código.")

        return SourceLoadResult.Success(title = displayName(file.name), text = text, notes = notes)
    }

    /** Solo el nombre (sin carpetas), sin espacios sobrantes. */
    fun displayName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').trim()

    //Decodificación

    private fun decode(bytes: ByteArray, notes: MutableList<String>): String? {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return decodeUtf16(bytes, offset = 2, littleEndian = true)
            if (b0 == 0xFE && b1 == 0xFF) return decodeUtf16(bytes, offset = 2, littleEndian = false)
        }
        val start = if (hasUtf8Bom(bytes)) 3 else 0
        return try {
            bytes.decodeToString(startIndex = start, endIndex = bytes.size, throwOnInvalidSequence = true)
        } catch (e: Exception) {
            notes += "El archivo no está en UTF-8; se leyó como Windows-1252 (ANSI)."
            decodeWindows1252(bytes)
        }
    }

    private fun hasUtf8Bom(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
                (bytes[0].toInt() and 0xFF) == 0xEF &&
                (bytes[1].toInt() and 0xFF) == 0xBB &&
                (bytes[2].toInt() and 0xFF) == 0xBF

    private fun decodeUtf16(bytes: ByteArray, offset: Int, littleEndian: Boolean): String? {
        if ((bytes.size - offset) % 2 != 0) return null
        val builder = StringBuilder((bytes.size - offset) / 2)
        var i = offset
        while (i + 1 < bytes.size) {
            val first = bytes[i].toInt() and 0xFF
            val second = bytes[i + 1].toInt() and 0xFF
            val unit = if (littleEndian) (second shl 8) or first else (first shl 8) or second
            builder.append(unit.toChar())
            i += 2
        }
        return builder.toString()
    }

    private fun decodeWindows1252(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(
                if (value in 0x80..0x9F) WINDOWS_1252_HIGH[value - 0x80] else value.toChar()
            )
        }
        return builder.toString()
    }

    /** Caracteres de control que no tienen lugar en un programa (se conservan tabulador, salto de línea y avance de página). */
    private fun isStrayControl(c: Char): Boolean =
        (c < ' ' && c != '\t' && c != '\n' && c != '\u000C') || c == '\u007F'

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "${(bytes * 10 / (1024L * 1024L)) / 10.0} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes bytes"
    }

    private const val BOM: Char = '﻿'

    /** Bytes 0x80–0x9F de Windows-1252 (el resto coincide con ISO-8859-1). */
    private val WINDOWS_1252_HIGH: CharArray = charArrayOf(
        '€', '\u0081', '‚', 'ƒ', '„', '…', '†', '‡',
        'ˆ', '‰', 'Š', '‹', 'Œ', '\u008D', 'Ž', '\u008F',
        '\u0090', '‘', '’', '“', '”', '•', '–', '—',
        '˜', '™', 'š', '›', 'œ', '\u009D', 'ž', 'Ÿ'
    )
}
