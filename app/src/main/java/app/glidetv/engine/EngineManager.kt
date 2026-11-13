package app.glidetv.engine

import android.content.Context
import android.os.Build
import android.provider.Settings
import app.glidetv.engine.adb.AdbEngine
import app.glidetv.engine.adb.ConnectResult
import app.glidetv.engine.root.RootEngine
import app.glidetv.system.SpecialAccess
import app.glidetv.util.Logx
import app.glidetv.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class EngineState(val kind: EngineKind, val connecting: Boolean = false)

/**
 * Выбирает лучший доступный движок: Root → встроенный ADB → без прав.
 * UI подписывается на [state] и показывает только реально доступные функции.
 */
class EngineManager(
    context: Context,
    private val prefs: Prefs,
    @Suppress("unused") private val scope: CoroutineScope,
) {
    private val app = context.applicationContext
    val selfPkg = PackageName(app.packageName)

    val adb = AdbEngine(app, selfPkg)
    private val root = RootEngine(selfPkg)

    private val lock = Mutex()
    private val _state = MutableStateFlow(EngineState(EngineKind.NONE))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    val rootAvailable: Boolean get() = RootEngine.isSuPresent()

    fun current(): PrivilegedEngine? = when (_state.value.kind) {
        EngineKind.ROOT -> root
        EngineKind.ADB -> adb
        EngineKind.NONE -> null
    }

    /** Есть ли права shell/root прямо сейчас. */
    val isPrivileged: Boolean get() = _state.value.kind != EngineKind.NONE

    /**
     * Тихое восстановление при запуске: root (если уже разрешали) или ADB с сохранённым ключом.
     * Никаких диалогов, кроме системного «Разрешить отладку?», если ключ ещё не подтверждён.
     */
    suspend fun restore(): EngineKind = lock.withLock {
        val currentEngine = current()
        if (currentEngine != null && currentEngine.isAlive()) return@withLock _state.value.kind
        _state.value = EngineState(EngineKind.NONE, connecting = true)

        if (prefs.allowRoot && prefs.rootGranted && rootAvailable && root.isAlive()) {
            return@withLock set(EngineKind.ROOT)
        }
        if (prefs.turboEnabled) {
            val ok = withTimeoutOrNull(20_000) { reconnectAdb() } ?: false
            if (ok) return@withLock set(EngineKind.ADB)
        }
        set(EngineKind.NONE)
    }

    /** Явный запрос root по кнопке (появится окно Magisk/KernelSU). */
    suspend fun requestRoot(): Boolean = lock.withLock {
        if (!rootAvailable) return@withLock false
        val ok = root.isAlive()
        prefs.rootGranted = ok
        if (ok) set(EngineKind.ROOT)
        ok
    }

    /** Вызывается мастером «Турбо» после успешного подключения. */
    suspend fun onAdbConnected(result: ConnectResult.Connected) {
        prefs.turboEnabled = true
        prefs.lastAdbTls = result.tls
        if (!result.tls) prefs.lastAdbPort = result.port
        set(EngineKind.ADB)
        TurboSetup.selfGrant(adb, tls = result.tls)
    }

    private suspend fun reconnectAdb(): Boolean {
        if (prefs.lastAdbTls && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // После перезагрузки Android 14 выключает Wireless debugging и меняет порт.
            // С WRITE_SECURE_SETTINGS (выдали себе при первом подключении) включаем обратно сами.
            if (SpecialAccess.hasWriteSecureSettings(app)) {
                runCatching { Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 1) }
                delay(2_500)
            }
            if (adb.connectTls() is ConnectResult.Connected) return true
        }
        val ports = listOfNotNull(prefs.lastAdbPort.takeIf { it > 0 }, 5555).distinct()
        for (p in ports) {
            if (adb.connectLoopback(p) is ConnectResult.Connected) return true
        }
        return false
    }

    /** Выключить отладку на ТВ (по просьбе пользователя). «Турбо» после этого недоступно. */
    suspend fun disableDebugging() {
        val e = current() ?: return
        e.run(ShellCmd.DisableDebugging)
        adb.close()
        prefs.turboEnabled = false
        set(EngineKind.NONE)
    }

    fun forget() {
        adb.close()
        prefs.turboEnabled = false
        set(EngineKind.NONE)
    }

    private fun set(kind: EngineKind): EngineKind {
        _state.value = EngineState(kind)
        Logx.i("Движок: $kind")
        return kind
    }
}
