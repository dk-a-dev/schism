package ai.schism.split.core.ai

import ai.schism.split.BuildConfig
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads the on-device AI model as a **foreground** WorkManager job, so it keeps running (with a
 * progress notification) even if the user leaves or closes the app — no "connection abort" when the
 * process would otherwise be backed-out. The source URL is resolved internally from the build config;
 * it is never shown in the UI.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val url = BuildConfig.BACKEND_URL.trimEnd('/') + "/model"
        val dir = File(applicationContext.filesDir, "models").apply { mkdirs() }
        val target = File(dir, "expense-llm.task")
        val part = File(dir, "expense-llm.task.part")

        setForeground(foregroundInfo(0))
        val client = OkHttpClient.Builder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return Result.retry()
                val body = resp.body ?: return Result.retry()
                val total = body.contentLength()
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var downloaded = 0L
                        var lastPct = -1
                        var n: Int
                        while (input.read(buf).also { n = it } >= 0) {
                            if (isStopped) { part.delete(); return Result.failure() }
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    setProgress(workDataOf(KEY_PCT to pct))
                                    setForeground(foregroundInfo(pct))
                                }
                            }
                        }
                    }
                }
            }
            target.delete()
            if (part.renameTo(target)) Result.success() else { part.delete(); Result.retry() }
        } catch (t: Throwable) {
            part.delete()
            Result.retry()
        }
    }

    private fun foregroundInfo(pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "AI model download", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("Downloading Schism AI model")
            .setContentText(if (pct > 0) "$pct%" else "Starting…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, pct.coerceIn(0, 100), pct <= 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    companion object {
        const val UNIQUE = "model_download"
        const val KEY_PCT = "pct"
        private const val CHANNEL = "model_download"
        private const val NOTIF_ID = 4242
    }
}
