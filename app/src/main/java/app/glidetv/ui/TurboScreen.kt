package app.glidetv.ui

import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.glidetv.R
import app.glidetv.engine.EngineKind
import app.glidetv.engine.adb.ConnectResult
import app.glidetv.engine.adb.PairingDiscovery
import app.glidetv.engine.adb.PairingEndpoint
import app.glidetv.engine.adb.PairingServer
import app.glidetv.system.AdbRoute
import app.glidetv.system.SpecialAccess
import app.glidetv.ui.widgets.Palette
import app.glidetv.ui.widgets.QrCode
import app.glidetv.ui.widgets.WRAP
import app.glidetv.ui.widgets.actionButton
import app.glidetv.ui.widgets.addSpaced
import app.glidetv.ui.widgets.card
import app.glidetv.ui.widgets.dp
import app.glidetv.ui.widgets.horizontal
import app.glidetv.ui.widgets.lp
import app.glidetv.ui.widgets.rounded
import app.glidetv.ui.widgets.text
import app.glidetv.ui.widgets.vertical
import app.glidetv.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Мастер «Турбо»: включение встроенного ADB без компьютера и без сторонних приложений.
 */
class TurboScreen(activity: MainActivity) : Screen(activity) {
    private lateinit var state: TextView
    private lateinit var steps: TextView
    private lateinit var connectButton: TextView
    private lateinit var pairButton: TextView
    private lateinit var rootButton: TextView
    private lateinit var connectedActions: LinearLayout
    private lateinit var pairingPanel: LinearLayout
    private lateinit var qr: ImageView
    private lateinit var pairingUrl: TextView
    private lateinit var pairingPort: TextView
    private lateinit var codeInput: EditText
    private lateinit var log: TextView

    private var server: PairingServer? = null
    private var pairingScope: CoroutineScope? = null
    @Volatile private var endpoint: PairingEndpoint? = null
    private var busy = false

    override fun createView(): View = ScrollView(ctx).apply {
        addView(ctx.vertical {
            addSpaced(ctx.text(string(R.string.turbo_title), 30f, bold = true), 6)
            addSpaced(ctx.text(string(R.string.turbo_subtitle), 15f, Palette.TEXT_DIM), 16)
            state = ctx.text("", 20f, bold = true).also { addSpaced(it, 12) }

            addSpaced(ctx.card {
                steps = ctx.text("", 16f, Palette.TEXT).also { addView(it) }
            }, 16)

            val actions = ctx.horizontal()
            actions.addSpaced(ctx.actionButton(string(R.string.open_dev_settings)) {
                if (!SpecialAccess.openDeveloperSettings(ctx)) state.text = string(R.string.dev_settings_missing)
            }, 16, LinearLayout.LayoutParams(WRAP, WRAP))
            connectButton = ctx.actionButton(string(R.string.connect), primary = true) { connect() }
            actions.addSpaced(connectButton, 16, LinearLayout.LayoutParams(WRAP, WRAP))
            pairButton = ctx.actionButton(string(R.string.pair_by_code)) { showPairing() }
            actions.addSpaced(pairButton, 16, LinearLayout.LayoutParams(WRAP, WRAP))
            rootButton = ctx.actionButton(string(R.string.use_root)) { useRoot() }
            actions.addSpaced(rootButton, 0, LinearLayout.LayoutParams(WRAP, WRAP))
            addSpaced(actions, 16)

            pairingPanel = ctx.horizontal {
                visibility = View.GONE
                qr = ImageView(ctx).also { addView(it, LinearLayout.LayoutParams(dp(220), dp(220)).apply { rightMargin = dp(24) }) }
                addView(ctx.vertical {
                    addSpaced(ctx.text(string(R.string.pairing_phone_title), 20f, bold = true), 8)
                    addSpaced(ctx.text(string(R.string.pairing_phone_text), 15f, Palette.TEXT_DIM), 8)
                    pairingUrl = ctx.text("", 15f, Palette.ACCENT).also { addSpaced(it, 8) }
                    pairingPort = ctx.text("", 15f, Palette.TEXT_DIM).also { addSpaced(it, 16) }
                    addSpaced(ctx.text(string(R.string.pairing_manual), 14f, Palette.TEXT_DIM), 6)
                    val row = ctx.horizontal()
                    codeInput = EditText(ctx).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER
                        hint = "123456"
                        setTextColor(Palette.TEXT)
                        setHintTextColor(Palette.TEXT_DIM)
                        background = rounded(Palette.BG_DEEP, dp(10).toFloat(), Palette.ACCENT, dp(2))
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                    }
                    row.addSpaced(codeInput, 12, LinearLayout.LayoutParams(dp(180), WRAP))
                    row.addSpaced(ctx.actionButton(string(R.string.pair)) {
                        val code = codeInput.text.toString().trim()
                        scope.launch { state.text = withContext(Dispatchers.IO) { pairWith(code).message } }
                    }, 0, LinearLayout.LayoutParams(WRAP, WRAP))
                    addView(row)
                }, lp(0, WRAP, 1f))
            }
            addSpaced(pairingPanel, 16)

            connectedActions = ctx.horizontal {
                addSpaced(ctx.actionButton(string(R.string.disable_debugging)) { disableDebugging() }, 16,
                    LinearLayout.LayoutParams(WRAP, WRAP))
                addSpaced(ctx.actionButton(string(R.string.forget_turbo)) {
                    graph.engines.forget(); render()
                }, 0, LinearLayout.LayoutParams(WRAP, WRAP))
            }
            addSpaced(connectedActions, 16)

            addSpaced(ctx.text(string(R.string.log), 14f, Palette.TEXT_DIM, bold = true), 4)
            log = ctx.text("", 12f, Palette.TEXT_DIM).apply { typeface = Typeface.MONOSPACE }
            addView(log)
        })
    }

    override fun defaultFocus(): View = connectButton

    override fun onShow() {
        render()
        scope.launch { graph.engines.state.collect { render() } }
        scope.launch {
            while (isActive) {
                log.text = Logx.snapshot().takeLast(14).joinToString("\n")
                delay(1_000)
            }
        }
    }

    /**
     * Пока пользователь в системных настройках (окно с кодом сопряжения открыто), Glide TV в фоне.
     * Веб-страница для телефона и поиск порта должны продолжать работать — останавливаем только UI.
     */
    override fun onPause() {
        super.onHide()
    }

    override fun onHide() {
        stopPairing()
        super.onHide()
    }

    private fun stopPairing() {
        server?.stop()
        server = null
        pairingScope?.cancel()
        pairingScope = null
    }

    private fun render() {
        val kind = graph.engines.state.value.kind
        val p = graph.platform
        state.text = when (kind) {
            EngineKind.ADB -> string(R.string.turbo_state_adb)
            EngineKind.ROOT -> string(R.string.turbo_state_root)
            EngineKind.NONE -> string(R.string.turbo_state_off)
        }
        state.setTextColor(if (kind == EngineKind.NONE) Palette.WARN else Palette.OK)
        steps.text = string(
            when (p.preferredAdbRoute) {
                AdbRoute.FIRE_TV_ADB -> R.string.steps_fire_tv
                AdbRoute.USB_DEBUG_TCP -> if (p.supportsWirelessDebugging) R.string.steps_usb_debug_11 else R.string.steps_usb_debug
                AdbRoute.WIRELESS_PAIRING -> R.string.steps_wireless
            },
        )
        pairButton.visibility = if (p.supportsWirelessDebugging && kind == EngineKind.NONE) View.VISIBLE else View.GONE
        rootButton.visibility = if (graph.engines.rootAvailable && kind != EngineKind.ROOT) View.VISIBLE else View.GONE
        connectButton.visibility = if (kind == EngineKind.NONE) View.VISIBLE else View.GONE
        connectedActions.visibility = if (kind == EngineKind.ADB) View.VISIBLE else View.GONE
        if (kind != EngineKind.NONE) {
            pairingPanel.visibility = View.GONE
            stopPairing()
        }
    }

    private fun connect() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                state.text = string(R.string.connecting_confirm)
                val adb = graph.engines.adb
                var result: ConnectResult = adb.connectLoopback(graph.prefs.lastAdbPort.takeIf { it > 0 } ?: 5555)
                if (result !is ConnectResult.Connected && graph.platform.supportsWirelessDebugging) {
                    // уже сопряжены раньше? тогда TLS-подключение пройдёт без кода
                    val tls = adb.connectTls(8_000)
                    if (tls is ConnectResult.Connected || tls is ConnectResult.PairingRequired) result = tls
                }
                if (result is ConnectResult.Failed && !graph.platform.supportsWirelessDebugging) {
                    for (port in adb.findOpenLoopbackPorts()) {
                        result = adb.connectLoopback(port)
                        if (result is ConnectResult.Connected) break
                    }
                }
                when (val r = result) {
                    is ConnectResult.Connected -> {
                        graph.engines.onAdbConnected(r)
                        state.text = string(R.string.turbo_connected)
                    }
                    ConnectResult.PairingRequired -> {
                        state.text = string(R.string.pairing_required)
                        showPairing()
                    }
                    ConnectResult.Unauthorized -> state.text = string(R.string.connect_unauthorized)
                    is ConnectResult.Failed -> {
                        state.text = string(R.string.connect_failed)
                        if (graph.platform.supportsWirelessDebugging) showPairing()
                    }
                }
            } finally {
                busy = false
                render()
            }
        }
    }

    private fun showPairing() {
        pairingPanel.visibility = View.VISIBLE
        val srv = server ?: PairingServer { code -> runBlocking { pairWith(code) } }.also { server = it }
        val url = srv.start()
        if (url != null) {
            qr.setImageBitmap(QrCode.bitmap(url, ctx.dp(220)))
            pairingUrl.text = url
        } else {
            pairingUrl.text = string(R.string.no_network)
        }
        pairingPort.text = string(R.string.pairing_waiting_dialog)
        if (pairingScope != null) return
        pairingScope = MainScope().also { ps ->
            ps.launch {
                PairingDiscovery.endpoints(ctx).collect { ep ->
                    endpoint = ep
                    pairingPort.text = if (ep != null) string(R.string.pairing_port_found, ep.port)
                    else string(R.string.pairing_waiting_dialog)
                }
            }
        }
    }

    /** Вызывается и с телефона (поток веб-сервера), и с экрана ТВ. */
    private suspend fun pairWith(code: String): PairingServer.PairingReply {
        if (code.length != 6 || !code.all { it.isDigit() }) {
            return PairingServer.PairingReply(false, string(R.string.pairing_code_invalid))
        }
        val ep = endpoint ?: return PairingServer.PairingReply(false, string(R.string.pairing_open_dialog_first))
        val adb = graph.engines.adb
        val paired = adb.pair(ep.host, ep.port, code)
        if (paired.isFailure) {
            return PairingServer.PairingReply(false, string(R.string.pairing_failed, paired.exceptionOrNull()?.message ?: ""))
        }
        // После сопряжения adbd анонсирует _adb-tls-connect; даём ему пару секунд
        repeat(4) { attempt ->
            if (attempt > 0) delay(2_000)
            val r = adb.connectTls(10_000)
            if (r is ConnectResult.Connected) {
                graph.engines.onAdbConnected(r)
                return PairingServer.PairingReply(true, string(R.string.pairing_success))
            }
        }
        return PairingServer.PairingReply(false, string(R.string.pairing_paired_not_connected))
    }

    private fun useRoot() {
        scope.launch {
            state.text = string(R.string.root_requesting)
            val ok = graph.engines.requestRoot()
            state.text = string(if (ok) R.string.turbo_state_root else R.string.root_denied)
            render()
        }
    }

    private fun disableDebugging() {
        dialog(string(R.string.disable_debugging), string(R.string.disable_debugging_text),
            positive = R.string.disable, negative = android.R.string.cancel) {
            scope.launch {
                graph.engines.disableDebugging()
                render()
            }
        }
    }
}
