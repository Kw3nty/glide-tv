package app.glidetv.engine.root

import app.glidetv.engine.EngineKind
import app.glidetv.engine.PackageName
import app.glidetv.engine.PrivilegedEngine
import app.glidetv.engine.Query
import app.glidetv.engine.ShellCmd
import app.glidetv.engine.ShellResult
import app.glidetv.engine.parseExitCode
import app.glidetv.engine.wrapWithExitCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Root (Magisk/KernelSU/SuperSU). Используется, только если `su` найден и пользователь разрешил. */
class RootEngine(private val selfPkg: PackageName) : PrivilegedEngine {
    override val kind = EngineKind.ROOT

    override suspend fun isAlive(): Boolean {
        val r = run(ShellCmd.Read(Query.WHOAMI))
        return r.ok && r.output.contains("uid=0")
    }

    override suspend fun run(cmd: ShellCmd): ShellResult = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder("su", "-c", wrapWithExitCode(cmd.render(selfPkg)))
                .redirectErrorStream(true)
                .start()
            // readText() ждёт EOF; Process.waitFor(timeout) появился только в API 26
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            parseExitCode(out)
        } catch (t: Throwable) {
            ShellResult(-1, t.message ?: t.javaClass.simpleName)
        }
    }

    override fun close() = Unit

    companion object {
        private val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/system_ext/bin/su",
        )

        /** Быстрая проверка без запроса прав: есть ли бинарник su. */
        fun isSuPresent(): Boolean = SU_PATHS.any { File(it).exists() }
    }
}
