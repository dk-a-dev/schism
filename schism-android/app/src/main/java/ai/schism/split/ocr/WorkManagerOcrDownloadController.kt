package ai.schism.split.ocr

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class WorkManagerOcrDownloadController @Inject constructor(
    @ApplicationContext context: Context,
) : OcrDownloadController {
    private val workManager = WorkManager.getInstance(context)
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun observe(): Flow<OcrDownloadState> =
        workManager.getWorkInfosForUniqueWorkFlow(OcrModelDownloadWorker.UNIQUE_WORK).map { infos ->
            infos.maxByOrNull { it.runAttemptCount }?.toDownloadState() ?: OcrDownloadState.Idle
        }

    override fun enqueue(allowCellular: Boolean) {
        preferences.edit().putBoolean(KEY_WIFI_ONLY, !allowCellular).apply()
        val network = if (allowCellular) NetworkType.CONNECTED else NetworkType.UNMETERED
        val request = OneTimeWorkRequestBuilder<OcrModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            OcrModelDownloadWorker.UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override fun cancel() {
        workManager.cancelUniqueWork(OcrModelDownloadWorker.UNIQUE_WORK)
    }

    private fun WorkInfo.toDownloadState(): OcrDownloadState = when (state) {
        WorkInfo.State.BLOCKED -> OcrDownloadState.WaitingForWifi
        WorkInfo.State.ENQUEUED -> if (preferences.getBoolean(KEY_WIFI_ONLY, true)) {
            OcrDownloadState.WaitingForWifi
        } else {
            OcrDownloadState.Queued
        }
        WorkInfo.State.RUNNING -> OcrDownloadState.Running(
            progress.getLong(OcrModelDownloadWorker.KEY_DOWNLOADED, 0L),
            progress.getLong(OcrModelDownloadWorker.KEY_TOTAL, OcrCoordinator.MODEL_BYTES),
            progress.getString(OcrModelDownloadWorker.KEY_STAGE) ?: OcrModelDownloadWorker.STAGE_MODELS,
        )
        WorkInfo.State.SUCCEEDED -> OcrDownloadState.Complete
        WorkInfo.State.FAILED -> {
            val reason = outputData.getString(OcrModelDownloadWorker.KEY_FAILURE)
                ?.let { runCatching { OcrFailure.valueOf(it) }.getOrNull() }
                ?: OcrFailure.Unknown
            OcrDownloadState.Failed(reason, canRetry = reason != OcrFailure.NoSpace)
        }
        WorkInfo.State.CANCELLED -> OcrDownloadState.Idle
    }

    private companion object {
        const val PREFERENCES = "ocr_download"
        const val KEY_WIFI_ONLY = "wifi_only"
    }
}
