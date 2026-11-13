package app.glidetv.engine.adb

import app.glidetv.util.Logx
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Одноразовая веб-страница в локальной сети для ввода кода сопряжения с телефона.
 *
 * Зачем: на ТВ нет шторки уведомлений, а диалог с кодом закрывается (и код меняется),
 * как только пользователь уходит из настроек в Glide TV. С телефона код вводится,
 * пока диалог на ТВ остаётся открытым.
 *
 * Безопасность: случайный токен в пути, только локальная сеть, сервер живёт, пока открыт экран
 * сопряжения, и сам останавливается после успешного сопряжения.
 */
class PairingServer(
    private val onCode: (String) -> PairingReply,
) {
    data class PairingReply(val success: Boolean, val message: String)

    private var server: ServerSocket? = null
    val token: String = randomToken()

    @Volatile var url: String? = null
        private set

    fun start(): String? {
        if (server != null) return url
        val ip = lanAddress() ?: return null
        val socket = ServerSocket(0)
        server = socket
        url = "http://$ip:${socket.localPort}/$token"
        thread(name = "glide-pairing-http", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: SocketException) { break }
                runCatching { handle(client) }.onFailure { Logx.w("pairing http", it) }
            }
        }
        Logx.i("Страница сопряжения: $url")
        return url
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        url = null
    }

    private fun handle(client: Socket) = client.use { s ->
        s.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1]
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        if (path.trimEnd('/') != "/$token") {
            respond(s, 404, "<h1>404</h1>")
            return
        }
        when (method) {
            "GET" -> respond(s, 200, page(null))
            "POST" -> {
                if (contentLength !in 1..1024) { respond(s, 400, page(PairingReply(false, "Bad request"))); return }
                val body = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(body, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                val form = String(body, 0, read).split('&').associate {
                    val k = it.substringBefore('=')
                    val v = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                    k to v
                }
                val code = form["code"].orEmpty().filter { it.isDigit() }
                val reply = if (code.length == 6) onCode(code)
                else PairingReply(false, "Нужно 6 цифр / 6 digits required")
                respond(s, 200, page(reply))
                if (reply.success) stop()
            }
            else -> respond(s, 405, "<h1>405</h1>")
        }
    }

    private fun respond(s: Socket, status: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        s.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private fun page(reply: PairingReply?): String {
        val status = when {
            reply == null -> ""
            reply.success -> "<p class='ok'>✅ ${escape(reply.message)}</p>"
            else -> "<p class='err'>⚠️ ${escape(reply.message)}</p>"
        }
        return """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Glide TV</title>
<style>
body{font-family:system-ui,sans-serif;background:#0B1B3A;color:#fff;margin:0;padding:24px;text-align:center}
h1{font-size:24px;margin:8px 0 4px}.sub{color:#9fb3d9;margin:0 0 20px}
input{font-size:36px;letter-spacing:10px;width:240px;text-align:center;padding:12px;border-radius:12px;border:0}
button{margin-top:18px;font-size:20px;padding:14px 32px;border-radius:12px;border:0;background:#3EE6FF;color:#0B1B3A;font-weight:700}
.ok{color:#5CFFB0;font-size:18px}.err{color:#FF8A80;font-size:18px}ol{text-align:left;max-width:420px;margin:20px auto;color:#cfd9ee}
</style></head><body>
<h1>Glide TV</h1>
<p class="sub">Код сопряжения · Pairing code</p>
$status
<form method="post"><input name="code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="one-time-code" autofocus required><br>
<button type="submit">Подключить · Connect</button></form>
<ol>
<li>На ТВ: Настройки → Для разработчиков → Отладка по Wi-Fi → «Подключить устройство с помощью кода».</li>
<li>Не закрывайте это окно на ТВ и введите здесь 6 цифр.</li>
<li>On the TV: Developer options → Wireless debugging → Pair device with pairing code. Keep it open and type the 6 digits here.</li>
</ol>
</body></html>
""".trimIndent()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private fun randomToken(): String {
            val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..10).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }

        /** IPv4 адрес в локальной сети (Wi-Fi или Ethernet). */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
