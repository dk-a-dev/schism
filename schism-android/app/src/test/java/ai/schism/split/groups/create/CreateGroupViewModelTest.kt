package ai.schism.split.groups.create

import ai.schism.split.core.db.GroupDao
import ai.schism.split.core.db.SchismDb
import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.groups.data.GroupRepository
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CreateGroupViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var db: SchismDb
    private lateinit var dao: GroupDao
    private lateinit var api: ApiService
    private lateinit var settings: SettingsRepository
    private lateinit var repo: GroupRepository
    private lateinit var vm: CreateGroupViewModel

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
        vm = CreateGroupViewModel(repo, settings)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        db.close()
    }

    @Test
    fun shortNameFailsValidationWithoutCallingApi() = runTest(dispatcher) {
        vm.onNameChange("A")
        vm.onParticipantChange(0, "Dev")

        var called = false
        vm.submit { called = true }

        assertNotNull("short name should set a field error", vm.state.value.nameError)
        assertTrue("valid submit must not be reached", !called)
        assertEquals("no API call on invalid form", 0, server.requestCount)
    }

    @Test
    fun duplicateParticipantsFailValidationWithoutCallingApi() = runTest(dispatcher) {
        vm.onNameChange("Goa Trip")
        vm.onParticipantChange(0, "Dev")
        vm.addParticipant()
        vm.onParticipantChange(1, "  dev ") // same name, different case/spacing

        vm.submit { }

        assertNotNull(vm.state.value.participantsError)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun validFormCreatesGroupAndRegistersKnownId() = runTest(dispatcher) {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"groupId":"g1"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"id":"g1","name":"Goa Trip","currency":"₹","currencyCode":"INR",
                    "participants":[{"id":"p1","groupId":"g1","name":"Dev"}]}""",
            ),
        )

        vm.onNameChange("Goa Trip")
        vm.onParticipantChange(0, "Dev")

        val created = CompletableDeferred<String>()
        vm.submit { created.complete(it) }
        val id = created.await()

        assertEquals("g1", id)
        assertEquals("/v1/groups", server.takeRequest().path)
        assertTrue(settings.knownGroupIds.first().contains("g1"))
    }
}
