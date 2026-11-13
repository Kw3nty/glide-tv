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
import app.glidetv.ui.widgets.WRAP
import app.glidetv.ui.widgets.actionButton
import app.glidetv.ui.widgets.addSpaced
import app.glidetv.ui.widgets.card
import app.glidetv.ui.widgets.horizontal
import app.glidetv.ui.widgets.text
import app.glidetv.ui.widgets.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsScreen(activity: MainActivity) : Screen(activity) {
    private lateinit var firstButton: TextView
    private lateinit var status: TextView
    private lateinit var aboutText: TextView

    override fun createView(): View = ScrollView(ctx).apply {
        addView(ctx.vertical {
            addSpaced(ctx.text(string(R.string.settings_title), 30f, bold = true), 18)

            section(this, R.string.language) {
                firstButton = ctx.actionButton(string(R.string.lang_system)) { setLanguage("") }
                addSpaced(firstButton, 12, LinearLayout.LayoutParams(WRAP, WRAP))
                addSpaced(ctx.actionButton("Русский") { setLanguage("ru") }, 12, LinearLayout.LayoutParams(WRAP, WRAP))
                addSpaced(ctx.actionButton("English") { setLanguage("en") }, 0, LinearLayout.LayoutParams(WRAP, WRAP))
            }

            section(this, R.string.animations, R.string.animations_hint) {
                addSpaced(ctx.actionButton(string(R.string.anim_off)) { setAnim(AnimScale.OFF) }, 12, LinearLayout.LayoutParams(WRAP, WRAP))
                addSpaced(ctx.actionButton("0.5×") { setAnim(AnimScale.HALF) }, 12, LinearLayout.LayoutParams(WRAP, WRAP))
                addSpaced(ctx.actionButton("1× (" + string(R.string.default_value) + ")") { setAnim(AnimScale.NORMAL) },
                    0, LinearLayout.LayoutParams(WRAP, WRAP))
            }

            section(this, R.string.speedup_apps, R.string.speedup_apps_hint) {
                addSpaced(ctx.actionButton(string(R.string.speedup_run)) { compileApps(reset = false) }, 12,
                    LinearLayout.LayoutParams(WRAP, WRAP))
                addSpaced(ctx.actionButton(string(R.string.speedup_reset)) { compileApps(reset = true) }, 0,
                    LinearLayout.LayoutParams(WRAP, WRAP))
            }

            section(this, R.string.updates) {
                addSpaced(ctx.actionButton(string(R.string.check_updates)) { checkUpdates() }, 0,
                    LinearLayout.LayoutParams(WRAP, WRAP))
            }

            status = ctx.text("", 15f, Palette.TEXT_DIM).also { addSpaced(it, 18) }

            addSpaced(ctx.card {
                addView(ctx.text(string(R.string.about_title, BuildConfig.VERSION_NAME), 18f, bold = true))
                aboutText = ctx.text("", 14f, Palette.TEXT_DIM).also { addView(it) }
                addView(ctx.text(string(R.string.licenses), 13f, Palette.TEXT_DIM))
            }, 0)
        })
    }

    override fun defaultFocus(): View = firstButton

    override fun onShow() {
        scope.launch {
            val db = withContext(Dispatchers.Default) { graph.debloatDb }
            aboutText.text = string(R.string.about_text, db.profiles.size, db.revision)
        }
    }

    private fun section(parent: LinearLayout, title: Int, hint: Int? = null, buttons: LinearLayout.() -> Unit) {
        parent.addSpaced(ctx.text(string(title), 20f, bold = true), 4)
        if (hint != null) parent.addSpaced(ctx.text(string(hint), 14f, Palette.TEXT_DIM), 8)
        parent.addSpaced(ctx.horizontal(buttons), 22)
    }

    private fun setLanguage(lang: String) {
        graph.prefs.language = lang
        activity.recreateScreens()
    }

    private fun setAnim(scale: AnimScale) {
        val engine = graph.engines.current() ?: run { status.text = string(R.string.need_turbo_short); return }
        scope.launch {
            val ok = engine.run(ShellCmd.SetAnimations(scale)).ok
            status.text = string(if (ok) R.string.done else R.string.operation_failed)
        }
    }

    /**
     * AOT-компиляция приложений с иконкой (Netflix, YouTube, IPTV…): холодный старт быстрее.
     * Play Store при обновлении приложения сбрасывает компиляцию — тогда кнопку нужно нажать снова.
     */
    private fun compileApps(reset: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { status.text = string(R.string.speedup_unsupported); return }
        val engine = graph.engines.current() ?: run { status.text = string(R.string.need_turbo_short); return }
        val pm = ctx.packageManager
        val pkgs = graph.apps.installed()
            .filter { it.enabled && it.pkg != ctx.packageName && PackageName.isValid(it.pkg) }
            .filter { pm.getLeanbackLaunchIntentForPackage(it.pkg) != null || pm.getLaunchIntentForPackage(it.pkg) != null }
            .map { it.pkg }
        scope.launch {
            var ok = 0
            pkgs.forEachIndexed { i, pkg ->
                status.text = string(R.string.speedup_progress, i + 1, pkgs.size, pkg)
                val cmd = if (reset) ShellCmd.CompileReset(PackageName(pkg)) else ShellCmd.CompileSpeed(PackageName(pkg))
                if (engine.run(cmd).ok) ok++
            }
            status.text = string(R.string.speedup_done, ok, pkgs.size)
        }
    }

    private fun checkUpdates() {
        scope.launch {
            status.text = string(R.string.checking)
            val release = graph.updates.latest()
            graph.prefs.lastUpdateCheck = System.currentTimeMillis()
            when {
                release == null -> status.text = string(R.string.update_unavailable)
                !graph.updates.isNewer(release) -> status.text = string(R.string.update_latest, BuildConfig.VERSION_NAME)
                else -> {
                    status.text = ""
                    dialog(string(R.string.update_found, release.version), release.notes.take(1500),
                        positive = R.string.update_install, negative = android.R.string.cancel) { install(release) }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun install(release: app.glidetv.update.ReleaseInfo) {
        if (!graph.updates.canInstall()) {
            status.text = string(R.string.update_allow_unknown)
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
                status.text = string(R.string.update_failed, t.message ?: "")
            }
        }
    }
}
