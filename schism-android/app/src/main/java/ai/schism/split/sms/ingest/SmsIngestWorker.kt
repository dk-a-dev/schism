package ai.schism.split.sms.ingest

import ai.schism.split.sms.data.SmsRepository
import ai.schism.split.sms.settings.SmsImportPreference
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Ingests a single (possibly multi-part, already concatenated) SMS into the local ledger off the
 * main thread. Enqueued by [SmsReceiver] so parsing never blocks the broadcast.
 */
@HiltWorker
class SmsIngestWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val smsRepository: SmsRepository,
    private val smsImportPreference: SmsImportPreference,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!smsImportPreference.isEnabled) return Result.success()
        val body = inputData.getString(KEY_BODY) ?: return Result.success()
        if (body.toByteArray().size > MAX_BODY_BYTES) return Result.failure()
        val sender = inputData.getString(KEY_SENDER) ?: return Result.success()
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
        if (!smsImportPreference.isEnabled) return Result.success()
        return runCatching {
            smsRepository.ingest(body, sender, timestamp)
        }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        const val KEY_BODY = "body"
        const val KEY_SENDER = "sender"
        const val KEY_TIMESTAMP = "timestamp"
        const val WORK_TAG = "sms_import"
        const val MAX_BODY_BYTES = 8 * 1024

        fun inputData(body: String, sender: String, timestamp: Long): Data =
            Data.Builder()
                .putString(KEY_BODY, body)
                .putString(KEY_SENDER, sender)
                .putLong(KEY_TIMESTAMP, timestamp)
                .build()
    }
}
