package ai.schism.split.sms.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SmsEnvelopeTest {
    @Test fun `fingerprint normalizes sender and buckets timestamp`() {
        val first = SmsEnvelope.create(" vm-icicib ", "Debited INR 100", 120_001L)
        val equivalent = SmsEnvelope.create("VMICICIB", "Debited INR 100", 179_999L)

        assertEquals(first.fingerprint, equivalent.fingerprint)
    }

    @Test fun `fingerprint retains exact body`() {
        val first = SmsEnvelope.create("VM-ICICIB", "Debited INR 100", 120_001L)
        val changed = SmsEnvelope.create("VM-ICICIB", "Debited  INR 100", 120_001L)

        assertNotEquals(first.fingerprint, changed.fingerprint)
    }
}
