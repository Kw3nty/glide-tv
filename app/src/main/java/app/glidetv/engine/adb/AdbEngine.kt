package app.glidetv.engine.adb

import android.content.Context
import app.glidetv.engine.EngineKind
import app.glidetv.engine.PackageName
import app.glidetv.engine.PrivilegedEngine
import app.glidetv.engine.Query
import app.glidetv.engine.ShellCmd
import app.glidetv.engine.ShellResult
import app.glidetv.engine.parseExitCode
import app.glidetv.engine.wrapWithExitCode
import app.glidetv.util.Logx
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

sealed interface ConnectResult {
    data class Connected(val port: Int, val tls: Boolean) : ConnectResult
    /** adbd ответил, но требует сопряжения (Wireless debugging). */
    data object PairingRequired : ConnectResult
    /** Порт открыт, но пользователь не подтвердил ключ (или истекло время ожидания). */
    data object Unauthorized : ConnectResult
    data class Failed(val reason: String) : ConnectResult
}

/** «Турбо» через встроенный ADB-клиент. Никаких внешних приложений (Shizuku и т. п.) не требуется. */
class AdbEngine(context: Context, private val selfPkg: PackageName) : PrivilegedEngine {
    override val kind = EngineKind.ADB

    private val appContext = context.applicationContext
    private val manager by lazy { GlideAdbManager.get(appContext) }
    private val mutex = Mutex()

    override suspend fun isAlive(): Boolean = withContext(Dispatchers.IO) {
        if (!manager.isConnected) return@withContext false
        val r = runRaw(Query.WHOAMI.command, timeoutMs = 5_000)
        r.ok && r.output.contains("uid=")
    }

    override suspend fun run(cmd: ShellCmd): ShellResult = runRaw(cmd.render(selfPkg), timeoutMs = 180_000)

    private suspend fun runRaw(command: String, timeoutMs: Long): ShellResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val stream = manager.openStream("shell:" + wrapWithExitCode(command))
                val out = ByteArrayOutputStream()
                coroutineScope {
                    val watchdog = launch {
                        delay(timeoutMs)
                        runCatching { stream.close() }
                    }
                    val input = stream.openInputStream()
                    val buf = ByteArray(8192)
                    try {
                        while (true) {
                            val n = input.read(buf, 0, buf.size)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    } catch (_: IOException) {
                        // поток закрыт: adbd завершил команду или сработал watchdog
                    }
                    watchdog.cancel()
                }
                runCatching { stream.close() }
                parseExitCode(out.toString("UTF-8"))
            } catch (t: Throwable) {
                Logx.w("adb shell", t)
                ShellResult(-1, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    override fun close() {
        runCatching { manager.disconnect() }
    }

    // ---------- подключение ----------

    /** Подключение к adbd на этом же устройстве (Fire TV, Android TV с сетевым ADB). */
    suspend fun connectLoopback(port: Int): ConnectResult = withContext(Dispatchers.IO) {
        if (!isPortOpen("127.0.0.1", port)) return@withContext ConnectResult.Failed("port $port closed")
        try {
            runCatching { manager.disconnect() }
            manager.setHostAddress("127.0.0.1")
            if (manager.connect(port) || manager.isConnected) {
                Logx.i("ADB: подключено к 127.0.0.1:$port")
                ConnectResult.Connected(port, tls = false)
            } else {
                ConnectResult.Unauthorized
            }
        } catch (_: AdbPairingRequiredException) {
            ConnectResult.PairingRequired
        } catch (t: Throwable) {
            Logx.w("ADB loopback $port", t)
            ConnectResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Wireless debugging (Android 11+): порт случайный, находим через mDNS `_adb-tls-connect`. */
    suspend fun connectTls(timeoutMs: Long = 12_000): ConnectResult = withContext(Dispatchers.IO) {
        try {
            runCatching { manager.disconnect() }
            if (manager.connectTls(appContext, timeoutMs) || manager.isConnected) {
                Logx.i("ADB: подключено по TLS")
                ConnectResult.Connected(-1, tls = true)
            } else {
                ConnectResult.Unauthorized
            }
        } catch (_: AdbPairingRequiredException) {
            ConnectResult.PairingRequired
        } catch (t: Throwable) {
            Logx.w("ADB TLS", t)
            ConnectResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Сопряжение по 6-значному коду из «Отладка по Wi-Fi → Сопряжение по коду». */
    suspend fun pair(host: String, port: Int, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        require(code.length == 6 && code.all { it.isDigit() }) { "pairing code must be 6 digits" }
        runCatching {
            manager.pair(host, port, code)
            Logx.i("ADB: сопряжение успешно ($host:$port)")
            Unit
        }.onFailure { Logx.w("ADB pair", it) }
    }

    /**
     * Некоторые прошивки поднимают adbd на нестандартном порту (например 5877 на проекторах Wanbo).
     * Сканируем loopback: закрытый порт отвечает мгновенно, поэтому это быстро.
     */
    suspend fun findOpenLoopbackPorts(limit: Int = 8): List<Int> = withContext(Dispatchers.IO) {
        // TLS-порты Wireless debugging (30000–60999) здесь не ищем: их находит mDNS в connectTls()
        val known = listOf(5555, 5556, 5557, 5877)
        val candidates = (known + (5500..5900).toList()).distinct()
        coroutineScope {
            candidates.chunked(64).flatMap { chunk ->
                chunk.map { p -> async { if (isPortOpen("127.0.0.1", p, 150)) p else null } }.awaitAll()
            }.filterNotNull().take(limit)
        }
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int = 400): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (_: IOException) {
        false
    }
}
