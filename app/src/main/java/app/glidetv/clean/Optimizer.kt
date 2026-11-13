package app.glidetv.clean

import android.content.Context
import android.os.Build
import app.glidetv.engine.EngineManager
import app.glidetv.engine.ShellCmd
import app.glidetv.system.MemoryRepository
import app.glidetv.system.StorageRepository
import app.glidetv.util.Logx
import kotlinx.coroutines.delay

enum class StepStatus { DONE, SKIPPED_NEEDS_TURBO, FAILED }

data class StepResult(val step: OptimizeStep, val status: StepStatus)

enum class OptimizeStep { CACHES, RAM }

/** Честный отчёт: только реально измеренные цифры, без «ускорено на 300 %». */
data class OptimizeReport(
    val storageFreedBytes: Long,
    val ramFreedBytes: Long,
    val steps: List<StepResult>,
) {
    val needsTurbo: Boolean get() = steps.any { it.status == StepStatus.SKIPPED_NEEDS_TURBO }
}

/** Сценарий кнопки «Оптимизировать» на главном экране. */
class Optimizer(
    private val context: Context,
    private val memory: MemoryRepository,
    private val storage: StorageRepository,
    private val ramCleaner: RamCleaner,
    private val engines: EngineManager,
) {
    suspend fun run(killBackground: Boolean, onStep: (OptimizeStep) -> Unit = {}): OptimizeReport {
        val ramBefore = memory.snapshot().availBytes
        val freeBefore = storage.internal().freeBytes
        val steps = mutableListOf<StepResult>()
        val engine = engines.current()

        onStep(OptimizeStep.CACHES)
        steps += StepResult(OptimizeStep.CACHES, when {
            engine != null -> if (engine.run(ShellCmd.TrimCaches).ok) StepStatus.DONE else StepStatus.FAILED
            LegacyCacheCleaner.isSupported -> if (LegacyCacheCleaner.clearAll(context)) StepStatus.DONE else StepStatus.FAILED
            else -> StepStatus.SKIPPED_NEEDS_TURBO
        })

        if (killBackground) {
            onStep(OptimizeStep.RAM)
            steps += StepResult(OptimizeStep.RAM, when {
                engine != null && Build.VERSION.SDK_INT >= 34 ->
                    if (engine.run(ShellCmd.KillAll).ok) StepStatus.DONE else StepStatus.FAILED
                ramCleaner.isSupported -> { ramCleaner.killBackground(); StepStatus.DONE }
                else -> StepStatus.SKIPPED_NEEDS_TURBO
            })
            // ядру нужно мгновение, чтобы вернуть освобождённые страницы
            delay(1_500)
        }

        val report = OptimizeReport(
            storageFreedBytes = (storage.internal().freeBytes - freeBefore).coerceAtLeast(0),
            ramFreedBytes = (memory.snapshot().availBytes - ramBefore).coerceAtLeast(0),
            steps = steps,
        )
        Logx.i("Оптимизация: $report")
        return report
    }
}
