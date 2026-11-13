package app.glidetv.engine

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
