package ai.schism.split.core.settings

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsRepositoryTest {
    private val repo = SettingsRepository(ApplicationProvider.getApplicationContext())

    @Before
    fun setUp() = runBlocking { repo.clear() } // DataStore is a JVM singleton; isolate from other tests

    @Test
    fun profileAndCurrencyDefaultsThenPersist() = runTest {
        assertEquals("", repo.profileName.first())
        assertEquals(SettingsRepository.DEFAULT_CURRENCY_SYMBOL, repo.currencySymbol.first())
        assertEquals(SettingsRepository.DEFAULT_CURRENCY_CODE, repo.currencyCode.first())

        repo.setProfileName("  Dev  ")
        repo.setDefaultCurrency("€", "EUR")

        assertEquals("Dev", repo.profileName.first())
        assertEquals("€", repo.currencySymbol.first())
        assertEquals("EUR", repo.currencyCode.first())
    }

    @Test
    fun knownGroupsAddAndRemove() = runTest {
        repo.addKnownGroup("g1")
        repo.addKnownGroup("g2")
        assertTrue(repo.knownGroupIds.first().containsAll(setOf("g1", "g2")))

        repo.removeKnownGroup("g1")
        val ids = repo.knownGroupIds.first()
        assertTrue(ids.contains("g2"))
        assertTrue(!ids.contains("g1"))
    }
}
