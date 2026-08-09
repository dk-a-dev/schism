package ai.schism.split.sms.ingest

import java.security.MessageDigest
import java.util.Locale

data class SmsEnvelope(
    val sender: String,
    val body: String,
    val timestamp: Long,
    val fingerprint: String,
) {
    companion object {
        fun create(sender: String, body: String, timestamp: Long): SmsEnvelope {
            val normalizedSender = sender.uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)
            val bucket = timestamp / TIMESTAMP_BUCKET_MS
            val fingerprint = "$normalizedSender\u0000$body\u0000$bucket".toByteArray()
                .let { MessageDigest.getInstance("SHA-256").digest(it) }
                .joinToString("") { "%02x".format(it) }
            return SmsEnvelope(sender.trim(), body, timestamp, fingerprint)
        }

        private const val TIMESTAMP_BUCKET_MS = 60_000L
    }
}
