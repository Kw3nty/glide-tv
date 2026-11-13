package app.glidetv.engine.adb

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.glidetv.GlideApp

/**
 * После перезагрузки ставит в очередь одну задачу переподключения «Турбо».
 * Постоянного фонового сервиса нет: на ТВ с 1 ГБ RAM он только мешал бы.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!GlideApp.graph(context).prefs.turboEnabled) return
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val job = JobInfo.Builder(ReconnectJobService.JOB_ID, ComponentName(context, ReconnectJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            // даём системе и Wi-Fi подняться
            .setMinimumLatency(30_000)
            .setOverrideDeadline(120_000)
            .build()
        js.schedule(job)
    }
}
