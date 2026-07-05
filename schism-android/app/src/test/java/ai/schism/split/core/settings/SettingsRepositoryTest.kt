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
    fun defaultsThenPersists() = runTest {
        assertEquals(SettingsRepository.DEFAULT_BACKEND_URL, repo.backendUrl.first())
        assertEquals("", repo.profileName.first())

        repo.setProfileName("  Dev  ")
        repo.setBackendUrl("http://192.168.1.5:8080")

        assertEquals("Dev", repo.profileName.first())
        assertEquals("http://192.168.1.5:8080", repo.backendUrl.first())
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
