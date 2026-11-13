package app.glidetv.engine.adb

import android.app.job.JobParameters
import android.app.job.JobService
import app.glidetv.GlideApp
import app.glidetv.engine.EngineKind
import app.glidetv.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Тихо восстанавливает «Турбо» после загрузки (включает Wireless debugging и переподключается). */
class ReconnectJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            val kind = runCatching { GlideApp.graph(this@ReconnectJobService).engines.restore() }
                .getOrDefault(EngineKind.NONE)
            Logx.i("Переподключение после загрузки: $kind")
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.cancel()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val JOB_ID = 4201
    }
}
