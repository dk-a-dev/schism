package ai.schism.split.sms.ingest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Receives incoming bank SMS and hands each message off to [SmsIngestWorker] for on-device parsing.
 * Multi-part (concatenated) SMS are joined by sender first, so a split bank alert is parsed as one
 * message. Nothing is parsed on the broadcast thread; the SMS body never leaves the device.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // Concatenate multi-part bodies keyed by originating address, preserving arrival order.
        val bySender = LinkedHashMap<String, StringBuilder>()
        var timestamp = System.currentTimeMillis()
        for (msg in messages) {
            val sender = msg.originatingAddress ?: continue
            bySender.getOrPut(sender) { StringBuilder() }.append(msg.messageBody ?: "")
            timestamp = msg.timestampMillis
        }

        val workManager = WorkManager.getInstance(context)
        for ((sender, body) in bySender) {
            val request = OneTimeWorkRequestBuilder<SmsIngestWorker>()
                .setInputData(SmsIngestWorker.inputData(body.toString(), sender, timestamp))
                .build()
            workManager.enqueue(request)
        }
    }
}
