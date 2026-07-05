package ai.schism.split.sms.ingest

import ai.schism.split.sms.data.SmsRepository
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.pennywiseai.parser.core.bank.BankParserFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-shot backfill: reads the device SMS inbox via [android.content.ContentResolver] and ingests
 * messages from known bank senders. Run once after the user grants READ_SMS so past transactions
 * appear in the Inbox. Existing rows dedup on their stable id, so re-scanning is safe.
 */
@HiltWorker
class SmsScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val smsRepository: SmsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        val resolver = applicationContext.contentResolver
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )
        resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext()) {
                val sender = cursor.getString(addressIdx) ?: continue
                if (!BankParserFactory.isKnownBankSender(sender)) continue
                val body = cursor.getString(bodyIdx) ?: continue
                val timestamp = cursor.getLong(dateIdx)
                smsRepository.ingest(body, sender, timestamp)
            }
        }
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        const val WORK_NAME = "sms_scan"

        /** Kicks off a single inbox backfill; replaces any in-flight scan. */
        fun enqueue(context: Context) {
            val request: WorkRequest = OneTimeWorkRequestBuilder<SmsScanWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
