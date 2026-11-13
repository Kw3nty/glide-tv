package app.glidetv.ui

import android.app.ProgressDialog
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.glidetv.BuildConfig
import app.glidetv.R
import app.glidetv.engine.AnimScale
import app.glidetv.engine.PackageName
import app.glidetv.engine.ShellCmd
import app.glidetv.ui.widgets.Palette
import app.glidetv.ui.widgets.StatusView
import app.glidetv.ui.widgets.Type
import app.glidetv.ui.widgets.actionButton
import app.glidetv.ui.widgets.addButtons
import app.glidetv.ui.widgets.addSpaced
import app.glidetv.ui.widgets.card
import app.glidetv.ui.widgets.dp
import app.glidetv.ui.widgets.focusBackground
import app.glidetv.ui.widgets.horizontal
import app.glidetv.ui.widgets.iconView
import app.glidetv.ui.widgets.lp
import app.glidetv.ui.widgets.onFocusColors
import app.glidetv.ui.widgets.text
import app.glidetv.ui.widgets.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsScreen(activity: MainActivity) : Screen(activity) {
    private lateinit var firstButton: TextView
    private lateinit var status: StatusView
    private lateinit var aboutText: TextView

    override fun createView(): View = ScrollView(ctx).apply {
        addView(ctx.vertical {
            addSpaced(ctx.text(string(R.string.settings_title), Type.TITLE, bold = true), 24)

            section(this, R.drawable.ic_globe, R.string.language) {
                val current = graph.prefs.language
                firstButton = langButton(string(R.string.lang_system), "", current)
                addButtons(firstButton, langButton("Русский", "ru", current), langButton("English", "en", current))
            }

            section(this, R.drawable.ic_gauge, R.string.animations, R.string.animations_hint) {
                addButtons(
                    ctx.actionButton(string(R.string.anim_off)) { setAnim(AnimScale.OFF) },
                    ctx.actionButton("0.5×") { setAnim(AnimScale.HALF) },
                    ctx.actionButton("1× (" + string(R.string.default_value) + ")") { setAnim(AnimScale.NORMAL) },
                )
            }

            section(this, R.drawable.ic_fast, R.string.speedup_apps, R.string.speedup_apps_hint) {
                addButtons(
                    ctx.actionButton(string(R.string.speedup_run), primary = true, iconRes = R.drawable.ic_fast) { compileApps(reset = false) },
                    ctx.actionButton(string(R.string.speedup_reset), iconRes = R.drawable.ic_restore) { compileApps(reset = true) },
                )
            }

            section(this, R.drawable.ic_download, R.string.updates) {
                addButtons(ctx.actionButton(string(R.string.check_updates), iconRes = R.drawable.ic_download) { checkUpdates() })
            }

            status = StatusView(ctx).also { addSpaced(it, 24) }

            addSpaced(ctx.card {
                addSpaced(ctx.text(string(R.string.about_title, BuildConfig.VERSION_NAME), Type.BODY, bold = true), 8)
                aboutText = ctx.text("", Type.CAPTION, Palette.TEXT_DIM).also { addSpaced(it, 8) }
                addView(ctx.text(string(R.string.licenses), Type.CAPTION, Palette.TEXT_DIM))
            }, 0)
        })
    }

    /** Текущий язык отмечен: подложка + циан + галочка (не только цветом). */
    private fun langButton(label: String, lang: String, current: String): TextView {
        val selected = lang == current
        return ctx.actionButton(label, iconRes = if (selected) R.drawable.ic_check else null) { setLanguage(lang) }.apply {
            isSelected = selected
            if (selected) {
                background = focusBackground(ctx, Palette.BG_DEEP)
                setTextColor(onFocusColors(Palette.ACCENT))
            }
        }
    }

    override fun defaultFocus(): View = firstButton

    override fun onShow() {
        scope.launch {
            val db = withContext(Dispatchers.Default) { graph.debloatDb }
            aboutText.text = string(R.string.about_text, db.profiles.size, db.revision)
        }
    }

    /** Раздел настроек — карточка: иконка + заголовок, пояснение, кнопки. */
    private fun section(parent: LinearLayout, iconRes: Int, title: Int, hint: Int? = null, buttons: LinearLayout.() -> Unit) {
        parent.addSpaced(ctx.card {
            addView(ctx.horizontal {
                addSpaced(ctx.iconView(iconRes, Palette.ACCENT), 12)
                addView(ctx.text(string(title), Type.SECTION, bold = true))
            }, lp().apply { bottomMargin = ctx.dp(if (hint != null) 8 else 16) })
            if (hint != null) addSpaced(ctx.text(string(hint), Type.SECONDARY, Palette.TEXT_DIM), 16)
            addView(ctx.horizontal(buttons))
        }, 16)
    }

    private fun setLanguage(lang: String) {
        graph.prefs.language = lang
        activity.recreateScreens()
    }

    private fun setAnim(scale: AnimScale) {
        val engine = graph.engines.current() ?: run { status.warn(string(R.string.need_turbo_short)); return }
        scope.launch {
            val ok = engine.run(ShellCmd.SetAnimations(scale)).ok
            if (ok) status.success(string(R.string.done)) else status.error(string(R.string.operation_failed))
        }
    }

    /**
     * AOT-компиляция приложений с иконкой (Netflix, YouTube, IPTV…): холодный старт быстрее.
     * Play Store при обновлении приложения сбрасывает компиляцию — тогда кнопку нужно нажать снова.
     */
    private fun compileApps(reset: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { status.warn(string(R.string.speedup_unsupported)); return }
        val engine = graph.engines.current() ?: run { status.warn(string(R.string.need_turbo_short)); return }
        val pm = ctx.packageManager
        val pkgs = graph.apps.installed()
            .filter { it.enabled && it.pkg != ctx.packageName && PackageName.isValid(it.pkg) }
            .filter { pm.getLeanbackLaunchIntentForPackage(it.pkg) != null || pm.getLaunchIntentForPackage(it.pkg) != null }
            .map { it.pkg }
        scope.launch {
            var ok = 0
            pkgs.forEachIndexed { i, pkg ->
                status.progress(string(R.string.speedup_progress, i + 1, pkgs.size, pkg))
                val cmd = if (reset) ShellCmd.CompileReset(PackageName(pkg)) else ShellCmd.CompileSpeed(PackageName(pkg))
                if (engine.run(cmd).ok) ok++
            }
            status.success(string(R.string.speedup_done, ok, pkgs.size))
        }
    }

    private fun checkUpdates() {
        scope.launch {
            status.progress(string(R.string.checking))
            val release = graph.updates.latest()
            graph.prefs.lastUpdateCheck = System.currentTimeMillis()
            when {
                release == null -> status.warn(string(R.string.update_unavailable))
                !graph.updates.isNewer(release) -> status.success(string(R.string.update_latest, BuildConfig.VERSION_NAME))
                else -> {
                    status.clear()
                    dialog(string(R.string.update_found, release.version), release.notes.take(1500),
                        positive = R.string.update_install, negative = android.R.string.cancel) { install(release) }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun install(release: app.glidetv.update.ReleaseInfo) {
        if (!graph.updates.canInstall()) {
            status.warn(string(R.string.update_allow_unknown))
            graph.updates.openInstallPermissionSettings()
            return
        }
        val progress = ProgressDialog(activity).apply {
            setMessage(string(R.string.downloading))
            isIndeterminate = false
            max = 100
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            show()
        }
        scope.launch {
            try {
                val file = graph.updates.download(release) { p -> progress.progress = p }
                progress.dismiss()
                graph.updates.install(file)
            } catch (t: Throwable) {
                progress.dismiss()
                status.error(string(R.string.update_failed, t.message ?: ""))
            }
        }
    }
}
