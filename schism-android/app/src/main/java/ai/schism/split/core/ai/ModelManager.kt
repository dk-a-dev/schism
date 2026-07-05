package ai.schism.split.core.ai

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the on-device LLM. The model (a MediaPipe `.task` file) is downloaded on demand to
 * app-private storage by a **foreground** WorkManager job ([ModelDownloadWorker]) so it survives the
 * app being closed. Download state is derived from the job's [WorkInfo] plus whether the file exists.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed interface State {
        data object Absent : State
        data class Downloading(val percent: Int) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    /** The downloaded model file (present only once a download completes). */
    val modelFile: File = File(dir, "expense-llm.task")

    fun isReady(): Boolean = modelFile.exists()

    fun sizeBytes(): Long? = modelFile.takeIf { it.exists() }?.length()

    /** Live download state: Ready when the file exists, else derived from the worker's WorkInfo. */
    val state: Flow<State> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.UNIQUE)
            .map { infos ->
                val info = infos.firstOrNull()
                when {
                    isReady() -> State.Ready
                    info == null -> State.Absent
                    info.state == WorkInfo.State.RUNNING ->
                        State.Downloading(info.progress.getInt(ModelDownloadWorker.KEY_PCT, 0))
                    info.state == WorkInfo.State.ENQUEUED -> State.Downloading(0)
                    info.state == WorkInfo.State.FAILED -> State.Failed("Download failed — try again")
                    else -> State.Absent
                }
            }

    /** Kick off (or restart) the foreground download of the model. */
    fun download() {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ModelDownloadWorker.UNIQUE, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(ModelDownloadWorker.UNIQUE)
    }

    fun delete() {
        cancel()
        modelFile.delete()
    }
}
