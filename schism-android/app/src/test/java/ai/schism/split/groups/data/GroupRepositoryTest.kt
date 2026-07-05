package ai.schism.split.groups.data

import ai.schism.split.core.db.GroupDao
import ai.schism.split.core.db.SchismDb
import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.CreateGroupRequest
import ai.schism.split.core.net.ParticipantRequest
import ai.schism.split.core.settings.SettingsRepository
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var db: SchismDb
    private lateinit var dao: GroupDao
    private lateinit var api: ApiService
    private lateinit var settings: SettingsRepository
    private lateinit var repo: GroupRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SchismDb::class.java,
        ).allowMainThreadQueries().build()
        dao = db.groupDao()
        api = ApiClient.create(server.url("/").toString())
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        runBlocking { settings.clear() } // DataStore is a JVM singleton; isolate from other tests
        repo = GroupRepository(api, dao, settings)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun groupJson(id: String, name: String) =
        """{"id":"$id","name":"$name","information":"","currency":"₹","currencyCode":"INR",
            "createdAt":"2026-07-05T00:00:00Z",
            "participants":[{"id":"p1","groupId":"$id","name":"A"},
                            {"id":"p2","groupId":"$id","name":"B"}]}"""

    private val sampleRequest = CreateGroupRequest(
        name = "Trip",
        currency = "$",
        participants = listOf(ParticipantRequest(name = "A"), ParticipantRequest(name = "B")),
    )

    @Test
    fun createGroupPostsThenCachesAndRegistersKnownGroup() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"groupId":"g1"}"""))
        server.enqueue(MockResponse().setBody(groupJson("g1", "Trip")))

        val id = repo.createGroup(sampleRequest).getOrThrow()
        assertEquals("g1", id)

        // POST then GET, in order
        assertEquals("/v1/groups", server.takeRequest().path)
        assertEquals("/v1/groups/g1", server.takeRequest().path)

        // now observable from the cache, with participants
        val cached = repo.observeGroup("g1").first()!!
        assertEquals("Trip", cached.name)
        assertEquals(2, cached.participants.size)

        // and remembered so future launches can refresh it
        assertTrue(settings.knownGroupIds.first().contains("g1"))
    }

    @Test
    fun refreshGroupCachesServerCopy() = runTest {
        server.enqueue(MockResponse().setBody(groupJson("g2", "Ski")))

        repo.refreshGroup("g2").getOrThrow()

        assertEquals("/v1/groups/g2", server.takeRequest().path)
        assertEquals("Ski", repo.observeGroup("g2").first()!!.name)
    }

    @Test
    fun refreshGroupsWithEmptyListMakesNoCallAndSucceeds() = runTest {
        val result = repo.refreshGroups(emptyList())
        assertTrue(result.isSuccess)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun failedCreateSurfacesErrorAndCachesNothing() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repo.createGroup(sampleRequest)

        assertTrue(result.isFailure)
        assertTrue(repo.observeGroups().first().isEmpty())
        assertFalse(settings.knownGroupIds.first().contains("g1"))
    }

    @Test
    fun setFavoriteAndActiveParticipantPersistThroughRefresh() = runTest {
        server.enqueue(MockResponse().setBody(groupJson("g3", "Cabin")))
        repo.refreshGroup("g3").getOrThrow()

        repo.setFavorite("g3", true)
        repo.setActiveParticipant("g3", "p1")

        // a later refresh replaces the server-owned columns but keeps local flags
        server.enqueue(MockResponse().setBody(groupJson("g3", "Cabin Renamed")))
        repo.refreshGroup("g3").getOrThrow()

        val g = repo.observeGroup("g3").first()!!
        assertEquals("Cabin Renamed", g.name)
        assertTrue(g.isFavorite)
        assertEquals("p1", g.activeParticipantId)
    }
}
