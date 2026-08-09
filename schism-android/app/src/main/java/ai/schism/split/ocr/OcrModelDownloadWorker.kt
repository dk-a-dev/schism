package ai.schism.split.ocr

import ai.schism.split.BuildConfig
import ai.schism.split.core.model.ArtifactDownloadException
import ai.schism.split.core.model.OcrModelStore
import ai.schism.split.core.net.ApiService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

@HiltWorker
class OcrModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: ApiService,
    private val store: OcrModelStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        setForeground(foregroundInfo(0, 0))
        return try {
            val manifest = api.ocrModelManifest()
            if (manifest.minimumAppVersionCode > BuildConfig.VERSION_CODE) {
                return failure(OcrFailure.IncompatibleApp)
            }
            store.install(manifest) { downloaded, total ->
                val progress = workDataOf(
                    KEY_DOWNLOADED to downloaded,
                    KEY_TOTAL to total,
                    KEY_STAGE to STAGE_MODELS,
                )
                setProgressAsync(progress)
                setForegroundAsync(foregroundInfo(downloaded, total))
            }
            Result.success()
        } catch (error: ArtifactDownloadException.InsufficientStorage) {
            failure(OcrFailure.NoSpace)
        } catch (error: ArtifactDownloadException.Integrity) {
            failure(OcrFailure.Integrity)
        } catch (error: ArtifactDownloadException.Network) {
            retryNetwork()
        } catch (error: HttpException) {
            retryNetwork()
        } catch (error: IOException) {
            retryNetwork()
        } catch (error: IllegalArgumentException) {
            failure(OcrFailure.Integrity)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failure(OcrFailure.Unknown)
        }
    }

    private fun retryNetwork(): Result = if (runAttemptCount < MAX_NETWORK_ATTEMPTS - 1) {
        Result.retry()
    } else {
        failure(OcrFailure.Network)
    }

    private fun failure(reason: OcrFailure) = Result.failure(workDataOf(KEY_FAILURE to reason.name))

    private fun foregroundInfo(downloaded: Long, total: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Receipt scanner download", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val determinate = total > 0L
        val percent = if (determinate) (downloaded * 100L / total).toInt().coerceIn(0, 100) else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("Preparing on-device receipt scanning")
            .setContentText(if (determinate) "$percent%" else "Starting download…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, !determinate)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val UNIQUE_WORK = "ocr_model_download"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_STAGE = "stage"
        const val KEY_FAILURE = "failure"
        const val STAGE_MODELS = "models"
        private const val CHANNEL = "ocr_model_download"
        private const val NOTIFICATION_ID = 4343
        private const val MAX_NETWORK_ATTEMPTS = 4
    }
}
