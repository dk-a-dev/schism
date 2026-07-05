package ai.schism.split.sms.ingest

import ai.schism.split.sms.data.SmsRepository
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
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val body = inputData.getString(KEY_BODY) ?: return Result.success()
        val sender = inputData.getString(KEY_SENDER) ?: return Result.success()
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
        return runCatching {
            smsRepository.ingest(body, sender, timestamp)
        }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        const val KEY_BODY = "body"
        const val KEY_SENDER = "sender"
        const val KEY_TIMESTAMP = "timestamp"

        fun inputData(body: String, sender: String, timestamp: Long): Data =
            Data.Builder()
                .putString(KEY_BODY, body)
                .putString(KEY_SENDER, sender)
                .putLong(KEY_TIMESTAMP, timestamp)
                .build()
    }
}
