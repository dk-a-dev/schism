package ai.schism.split.sms.receipt.cloud

import ai.schism.split.core.settings.SettingsRepository
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReceiptEngineSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = SettingsRepository(context)

    @Before
    fun setUp() = runBlocking { repo.clear() } // DataStore is a JVM singleton; isolate from other tests

    @Test
    fun `defaults to on-device`() = runTest {
        assertEquals(ReceiptEngine.ON_DEVICE, repo.receiptEngine.first())
        assertEquals(ReceiptProvider.GEMINI, repo.receiptProvider.first())
        assertTrue(repo.receiptCloudConsents.first().isEmpty())
    }

    @Test
    fun `engine and provider persist`() = runTest {
        repo.setReceiptEngine(ReceiptEngine.OWN_KEY)
        repo.setReceiptProvider(ReceiptProvider.GROQ)

        assertEquals(ReceiptEngine.OWN_KEY, repo.receiptEngine.first())
        assertEquals(ReceiptProvider.GROQ, repo.receiptProvider.first())
        // A fresh repository over the same store reads the same choice back.
        assertEquals(ReceiptEngine.OWN_KEY, SettingsRepository(context).receiptEngine.first())
    }

    @Test
    fun `consent is per engine and never implied`() = runTest {
        repo.grantReceiptCloudConsent(ReceiptEngine.OWN_KEY)

        assertTrue(repo.receiptCloudConsents.first().contains(ReceiptEngine.OWN_KEY.name))
        // Consenting to one cloud engine must NOT consent to the other.
        assertFalse(repo.receiptCloudConsents.first().contains(ReceiptEngine.SCHISM_CLOUD.name))
    }

    @Test
    fun `api key never lands in the datastore file`() = runTest {
        val secret = "sk-test-do-not-leak-9f3a1"
        repo.setReceiptApiKey(secret)
        // Write some ordinary settings too, so the DataStore file is definitely flushed to disk.
        repo.setProfileName("Dev")
        repo.setReceiptEngine(ReceiptEngine.OWN_KEY)
        repo.profileName.first()

        assertEquals(secret, repo.receiptApiKey())
        assertTrue(repo.receiptApiKeyPresent.first())

        val leaked = File(context.filesDir, "datastore").walkTopDown()
            .filter { it.isFile }
            .filter { it.readBytes().toString(Charsets.ISO_8859_1).contains(secret) }
            .toList()
        assertEquals("API key must never be written to DataStore", emptyList<File>(), leaked)

        // Nor to the shared-prefs file in plaintext: it is stored AES-GCM encrypted.
        val prefs = File(context.filesDir.parentFile, "shared_prefs/secure_receipt_ai.xml")
        if (prefs.exists()) assertFalse(prefs.readText().contains(secret))
    }

    @Test
    fun `clearing the key removes it`() = runTest {
        repo.setReceiptApiKey("sk-test-clear-me")
        repo.clearReceiptApiKey()

        assertEquals("", repo.receiptApiKey())
        assertFalse(repo.receiptApiKeyPresent.first())
    }

    @Test
    fun `reset wipes the key with everything else`() = runTest {
        repo.setReceiptApiKey("sk-test-reset-me")
        repo.setReceiptEngine(ReceiptEngine.OWN_KEY)

        repo.clear()

        assertEquals("", repo.receiptApiKey())
        assertEquals(ReceiptEngine.ON_DEVICE, repo.receiptEngine.first())
    }
}
