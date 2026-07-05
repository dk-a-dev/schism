package ai.schism.split.groups.list

import ai.schism.split.core.db.GroupDao
import ai.schism.split.core.db.GroupEntity
import ai.schism.split.core.db.ParticipantEntity
import ai.schism.split.core.db.SchismDb
import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.core.ui.UiState
import ai.schism.split.groups.data.GroupRepository
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
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
class GroupsListViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var db: SchismDb
    private lateinit var dao: GroupDao
    private lateinit var api: ApiService
    private lateinit var settings: SettingsRepository
    private lateinit var repo: GroupRepository

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
        repo = GroupRepository(api, dao, settings)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        db.close()
    }

    private fun group(id: String, name: String) =
        GroupEntity(id, name, "", "$", "USD", "2026-07-05T00:00:00Z")

    private suspend fun data(vm: GroupsListViewModel) =
        vm.state.filterIsInstance<UiState.Data<List<GroupSummary>>>().first().value

    @Test
    fun favoritesPinnedFirstAndMembersCounted() = runTest(dispatcher) {
        dao.upsertGroupWithParticipants(group("g1", "Alpha"), listOf(ParticipantEntity("p1", "g1", "A")))
        dao.upsertGroupWithParticipants(
            group("g2", "Beta"),
            listOf(ParticipantEntity("p2", "g2", "B"), ParticipantEntity("p3", "g2", "C")),
        )
        dao.setFavorite("g2", true) // favorite must sort ahead of the alphabetically-earlier "Alpha"

        val vm = GroupsListViewModel(repo, settings)

        val summaries = data(vm)
        assertEquals(listOf("g2", "g1"), summaries.map { it.id })
        assertEquals(2, summaries[0].memberCount)
        assertEquals(1, summaries[1].memberCount)
    }

    @Test
    fun refreshFailureRetainsCachedDataAndEmitsError() = runTest(dispatcher) {
        dao.upsertGroupWithParticipants(group("g1", "Alpha"), listOf(ParticipantEntity("p1", "g1", "A")))

        val vm = GroupsListViewModel(repo, settings) // init refresh is a no-op (no known groups yet)
        // Subscribe before triggering; DataStore/OkHttp run on real dispatchers, so await the
        // emission rather than polling virtual time.
        val firstError = async { vm.errors.first() }

        settings.addKnownGroup("g1") // so refresh() actually calls the API
        server.enqueue(MockResponse().setResponseCode(500))
        vm.refresh()

        val message = firstError.await()
        assertTrue("a refresh error should be surfaced", message.isNotEmpty())
        // cache (and therefore state) is untouched by the failed refresh
        assertEquals(listOf("g1"), data(vm).map { it.id })
    }
}
