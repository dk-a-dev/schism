package ai.schism.split.groups.join

import ai.schism.split.core.db.GroupDao
import ai.schism.split.core.db.SchismDb
import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.groups.data.GroupRepository
import ai.schism.split.groups.join.JoinGroupViewModel.Companion.parseGroupId
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JoinGroupViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var db: SchismDb
    private lateinit var dao: GroupDao
    private lateinit var api: ApiService
    private lateinit var settings: SettingsRepository
    private lateinit var repo: GroupRepository
    private lateinit var vm: JoinGroupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
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
        vm = JoinGroupViewModel(repo, settings)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        db.close()
    }

    @Test
    fun parsesLinksRawIdsAndUrls() {
        assertEquals("abc", parseGroupId("schism://group/abc"))
        assertEquals("abc", parseGroupId("abc"))
        assertEquals("abc", parseGroupId("  abc  "))
        assertEquals("abc", parseGroupId("https://schism.ai/group/abc?ref=x"))
    }

    @Test
    fun joiningValidGroupFetchesRegistersAndReportsId() = runTest(dispatcher) {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"g1","name":"Goa Trip","currency":"₹","currencyCode":"INR",
                    "participants":[{"id":"p1","groupId":"g1","name":"Dev"}]}""",
            ),
        )

        val joined = CompletableDeferred<String>()
        vm.join("schism://group/g1") { joined.complete(it) }

        assertEquals("g1", joined.await())
        assertEquals("/v1/groups/g1", server.takeRequest().path)
        assertTrue(settings.knownGroupIds.first().contains("g1"))
    }

    @Test
    fun joiningUnknownGroupYieldsErrorState() = runTest(dispatcher) {
        server.enqueue(MockResponse().setResponseCode(404))

        val error = async { vm.state.filterIsInstance<JoinState.Error>().first() }
        vm.join("nope") { /* should not be called */ }

        assertTrue(error.await().message.isNotBlank())
        assertTrue("unknown group must not be remembered", !settings.knownGroupIds.first().contains("nope"))
    }
}
