package ai.schism.split.groups.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QrCodeTest {
    @Test
    fun `qrBitmap encodes a group link into a square bitmap`() {
        val bitmap = qrBitmap("schism://group/abc", 128)

        assertNotNull(bitmap)
        assertEquals(128, bitmap.width)
        assertEquals(128, bitmap.height)
    }
}
