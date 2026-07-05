package ai.schism.split.sms.itemized

import ai.schism.split.sms.receipt.ReceiptDraft
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A transient, in-memory hand-off of a just-scanned receipt from the Inbox scan to the itemised
 * split screen. Kept as a process-scoped singleton so we don't have to serialize a list of line
 * items through navigation arguments; it's read once and can be cleared by the consumer.
 */
@Singleton
class PendingReceipt @Inject constructor() {
    var draft: ReceiptDraft? = null
}
