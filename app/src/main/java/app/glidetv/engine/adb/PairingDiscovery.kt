package app.glidetv.engine.adb

import android.content.Context
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class PairingEndpoint(val host: String, val port: Int)

/**
 * Пока на ТВ открыт диалог «Сопряжение по коду», adbd анонсирует `_adb-tls-pairing._tcp`.
 * Так мы узнаём порт, и пользователю нужно ввести только 6 цифр.
 */
object PairingDiscovery {
    fun endpoints(context: Context): Flow<PairingEndpoint?> = callbackFlow {
        val mdns = AdbMdns(context.applicationContext, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            trySend(if (host != null && port > 0) PairingEndpoint(host.hostAddress ?: "127.0.0.1", port) else null)
        }
        mdns.start()
        awaitClose { mdns.stop() }
    }.distinctUntilChanged()
}
