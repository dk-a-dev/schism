package ai.schism.split.sms.ingest

import ai.schism.split.sms.settings.SmsImportPreference
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.pennywiseai.parser.core.bank.BankParserFactory

/**
 * Receives incoming bank SMS and hands each message off to [SmsIngestWorker] for on-device parsing.
 * Multi-part (concatenated) SMS are joined by sender first, so a split bank alert is parsed as one
 * message. Nothing is parsed on the broadcast thread; the SMS body never leaves the device.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!SmsImportPreference(context.applicationContext).isEnabled) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // Concatenate multi-part bodies keyed by originating address, preserving arrival order.
        val bySender = LinkedHashMap<String, Pair<StringBuilder, Long>>()
        for (msg in messages) {
            val sender = msg.originatingAddress ?: continue
            val current = bySender[sender]
            val body = current?.first ?: StringBuilder()
            body.append(msg.messageBody ?: "")
            bySender[sender] = body to (current?.second ?: msg.timestampMillis)
        }

        val workManager = WorkManager.getInstance(context)
        for ((sender, value) in bySender) {
            // Drop anything that is not a recognised bank sender here, before the body is handed to
            // anything else. SmsScanWorker has always filtered on this; the live path did not, so a
            // personal message was read, written into WorkManager's database as job input, parsed,
            // and only then discarded for producing no transaction. The stored outcome was the same,
            // but the message had already been through storage to reach it.
            //
            // This is also what makes the Play SMS declaration literally true: access really is
            // restricted to financial senders, on both paths, not just in effect.
            if (!BankParserFactory.isKnownBankSender(sender)) continue
            val envelope = SmsEnvelope.create(sender, value.first.toString(), value.second)
            if (envelope.body.toByteArray().size > SmsIngestWorker.MAX_BODY_BYTES) continue
            val request = OneTimeWorkRequestBuilder<SmsIngestWorker>()
                .setInputData(SmsIngestWorker.inputData(envelope.body, envelope.sender, envelope.timestamp))
                .addTag(SmsIngestWorker.WORK_TAG)
                .build()
            workManager.enqueueUniqueWork(
                "sms_ingest_${envelope.fingerprint}",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
