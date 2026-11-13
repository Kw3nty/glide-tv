package app.glidetv

import android.content.Context
import app.glidetv.clean.JunkScanner
import app.glidetv.clean.Optimizer
import app.glidetv.clean.RamCleaner
import app.glidetv.debloat.DebloatDb
import app.glidetv.debloat.DebloatExecutor
import app.glidetv.debloat.DebloatJournal
import app.glidetv.debloat.DebloatPlanner
import app.glidetv.engine.EngineManager
import app.glidetv.system.AppsRepository
import app.glidetv.system.MemoryRepository
import app.glidetv.system.PlatformInfo
import app.glidetv.system.StorageRepository
import app.glidetv.update.UpdateChecker
import app.glidetv.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * Ручной DI: приложение маленькое, а Hilt/Dagger потянули бы зависимости
 * с minSdk выше 21 и лишний вес на ТВ с 1 ГБ RAM.
 */
class AppGraph(context: Context) {
    val app: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs by lazy { Prefs(app) }
    val platform by lazy { PlatformInfo.detect(app) }
    val memory by lazy { MemoryRepository(app) }
    val storage by lazy { StorageRepository(app) }
    val apps by lazy { AppsRepository(app) }

    val engines by lazy { EngineManager(app, prefs, appScope) }

    val ramCleaner by lazy { RamCleaner(app) }
    val junkScanner by lazy { JunkScanner(app) }
    val optimizer by lazy { Optimizer(app, memory, storage, ramCleaner, engines) }

    val debloatDb by lazy { DebloatDb.load(app) }
    val debloatJournal by lazy { DebloatJournal(app) }
    val debloatPlanner by lazy { DebloatPlanner(app, platform, { debloatDb }, engines) }
    val debloatExecutor by lazy { DebloatExecutor(app, platform, engines, debloatJournal) }

    val updates by lazy { UpdateChecker(app) }
}
