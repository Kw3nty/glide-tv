package app.glidetv.engine

import android.os.Build
import app.glidetv.util.Logx

/**
 * Одноразовая донастройка после первого подключения «Турбо»: Glide TV выдаёт права
 * только самому себе (списки — в [SelfPermission] и [SelfAppOp]).
 *
 * Это решает проблему ТВ-прошивок без экранов «Доступ к истории использования»
 * и «Доступ ко всем файлам», и позволяет переподключаться после перезагрузки.
 */
object TurboSetup {
    suspend fun selfGrant(engine: PrivilegedEngine, tls: Boolean) {
        val sdk = Build.VERSION.SDK_INT
        val steps = buildList {
            add(ShellCmd.GrantSelf(SelfPermission.WRITE_SECURE_SETTINGS))
            SelfAppOp.entries.filter { sdk >= it.minSdk }.forEach { add(ShellCmd.AllowSelfAppOp(it)) }
            // app-ops пишутся на диск лениво: без этого выданное может пропасть после перезагрузки
            if (sdk >= Build.VERSION_CODES.P) add(ShellCmd.PersistAppOps)
            if (tls && sdk >= Build.VERSION_CODES.R) add(ShellCmd.EnableWirelessDebugging)
        }
        for (cmd in steps) {
            val r = engine.run(cmd)
            if (r.ok) Logx.i("Турбо: ${cmd.javaClass.simpleName} ✓")
            else Logx.w("Турбо: ${cmd.javaClass.simpleName} → ${r.exitCode} ${r.output.take(200)}")
        }
    }
}
