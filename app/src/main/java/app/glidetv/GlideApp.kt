package app.glidetv

import android.app.Application
import android.content.Context
import android.os.Build
import app.glidetv.util.Locales
import org.lsposed.hiddenapibypass.HiddenApiBypass

class GlideApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Locales.wrap(base))
        // libadb использует системный Conscrypt (com.android.org.conscrypt) для TLS-сопряжения.
        // На Android 9+ это скрытый API, поэтому снимаем ограничение только для нашего процесса.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as GlideApp).graph
    }
}
