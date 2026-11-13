package app.glidetv.ui

import android.app.AlertDialog
import android.view.View
import app.glidetv.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * Экран без Fragment/AndroidX: свой View и свой CoroutineScope, который отменяется при уходе с экрана.
 */
abstract class Screen(protected val activity: MainActivity) {
    protected val graph: AppGraph get() = activity.graph
    protected val ctx get() = activity

    private var _scope: CoroutineScope? = null
    protected val scope: CoroutineScope
        get() = _scope ?: MainScope().also { _scope = it }

    private var _view: View? = null
    val view: View get() = _view ?: createView().also { _view = it }

    protected abstract fun createView(): View

    /** Первый элемент, который получает фокус при открытии экрана. */
    open fun defaultFocus(): View? = null

    open fun onShow() = Unit

    open fun onHide() {
        _scope?.cancel()
        _scope = null
    }

    /** Activity ушла в фон (например, пользователь открыл системные настройки). */
    open fun onPause() = onHide()

    /** Activity вернулась на передний план. */
    open fun onResume() = onShow()

    protected fun string(id: Int, vararg args: Any): String = activity.getString(id, *args)

    protected fun dialog(title: CharSequence, message: CharSequence, positive: Int = android.R.string.ok,
                         negative: Int? = null, onPositive: () -> Unit = {}) {
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onPositive() }
            .apply { if (negative != null) setNegativeButton(negative, null) }
            .show()
    }
}
