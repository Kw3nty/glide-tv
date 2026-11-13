package app.glidetv.engine

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class EngineKind { NONE, ADB, ROOT }

interface PrivilegedEngine {
    val kind: EngineKind

    /** Соединение живо и команды выполняются. */
    suspend fun isAlive(): Boolean

    suspend fun run(cmd: ShellCmd): ShellResult

    fun close()
}

/** Маркер кода возврата: adbd shell v1 не передаёт exit code, поэтому печатаем его сами. */
internal const val EXIT_MARKER = "__GLIDE_EXIT__"

internal fun wrapWithExitCode(command: String) = "$command; echo $EXIT_MARKER$?"

internal fun parseExitCode(raw: String): ShellResult {
    val idx = raw.lastIndexOf(EXIT_MARKER)
    if (idx < 0) return ShellResult(-1, raw.trim())
    val code = raw.substring(idx + EXIT_MARKER.length).trim().takeWhile { it.isDigit() }.toIntOrNull() ?: -1
    return ShellResult(code, raw.substring(0, idx).trim())
}

private val EXIT_LINE = Regex("$EXIT_MARKER\\d+\\r?\\n")

/**
 * Читает вывод команды до строки с кодом возврата, не дожидаясь конца потока.
 *
 * libadb-android 3.1.1 иногда не отдаёт EOF после того, как adbd закрыл shell-поток
 * (гонка при быстром ответе): `read()` висит, пока его не прервёт watchdog. Строка
 * [EXIT_MARKER] печатается последней, так что её достаточно, чтобы считать команду завершённой.
 *
 * @return true — маркер получен; false — поток закончился или оборвался раньше.
 */
internal fun readUntilExitMarker(input: InputStream, out: OutputStream): Boolean {
    val buf = ByteArray(8192)
    // Маркер — ASCII, поэтому ищем его в «хвосте» как ISO-8859-1: не важно, где режутся UTF-8 символы
    var tail = ""
    try {
        while (true) {
            val n = input.read(buf, 0, buf.size)
            if (n < 0) return false
            if (n == 0) continue
            out.write(buf, 0, n)
            tail = (tail + String(buf, 0, n, Charsets.ISO_8859_1)).takeLast(64)
            if (EXIT_LINE.containsMatchIn(tail)) return true
        }
    } catch (_: IOException) {
        // поток закрыт: adbd завершил команду или сработал watchdog
        return false
    }
}
