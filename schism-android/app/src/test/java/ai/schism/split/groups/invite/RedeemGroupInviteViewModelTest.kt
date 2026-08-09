package ai.schism.split.groups.invite

import ai.schism.split.core.db.SchismDb
import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.groups.data.GroupRepository
import androidx.lifecycle.SavedStateHandle
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RedeemGroupInviteViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var db: SchismDb
    private lateinit var settings: SettingsRepository
    private lateinit var invites: GroupInviteRepository
    private lateinit var groups: GroupRepository

    private val previewBody = """{"groupName":"Goa Trip","memberCount":3}"""

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        server.start()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SchismDb::class.java,
        ).allowMainThreadQueries().build()
        val api = ApiClient.create(server.url("/").toString())
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        runBlocking { settings.clear() } // DataStore is a JVM singleton; isolate from other tests
        invites = GroupInviteRepository(api)
        groups = GroupRepository(api, db.groupDao(), settings)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        db.close()
    }

    private fun viewModel(token: String = "tok-1") =
        RedeemGroupInviteViewModel(invites, groups, settings, SavedStateHandle(mapOf("token" to token)))

    private suspend inline fun <reified T : RedeemState> RedeemGroupInviteViewModel.await(): T =
        state.first { it is T } as T

    @Test
    fun deepLinkWaitsForSignInThenPreviews() = runTest(dispatcher) {
        val vm = viewModel()

        // Signed out: nothing is requested, the link just waits.
        assertEquals(RedeemState.WaitingForSignIn, vm.state.value)
        assertEquals(0, server.requestCount)

        server.enqueue(MockResponse().setBody(previewBody))
        settings.setIdentity("u1", "session-token")

        val preview = vm.await<RedeemState.GroupPreview>()
        assertEquals("Goa Trip", preview.groupName)
        assertEquals(3, preview.memberCount)
        assertEquals("/v1/group-invites/tok-1", server.takeRequest().path)
    }

    @Test
    fun confirmJoinsAndStoresTheGroup() = runTest(dispatcher) {
        settings.setIdentity("u1", "session-token")
        server.enqueue(MockResponse().setBody(previewBody))
        val vm = viewModel()
        vm.await<RedeemState.GroupPreview>()

        server.enqueue(MockResponse().setBody("""{"groupId":"g1"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"id":"g1","name":"Goa Trip","currency":"₹","currencyCode":"INR",
                    "participants":[{"id":"p9","groupId":"g1","name":"Mira","userId":"u1"}]}""",
            ),
        )
        val joined = CompletableDeferred<String>()
        vm.confirm { joined.complete(it) }

        assertEquals("g1", joined.await())
        server.takeRequest() // preview
        assertEquals("/v1/group-invites/tok-1/redeem", server.takeRequest().path)
        assertTrue(settings.knownGroupIds.first().contains("g1"))
        assertEquals("Goa Trip", groups.observeGroup("g1").first()!!.name)
    }

    @Test
    fun revokedAndUnknownLinksFailExplicitlyAndJoinNothing() = runTest(dispatcher) {
        settings.setIdentity("u1", "session-token")

        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"invite revoked"}"""))
        val revoked = viewModel("revoked").await<RedeemState.Failed>()

        server.enqueue(MockResponse().setResponseCode(404))
        val unknown = viewModel("nope").await<RedeemState.Failed>()

        assertEquals(ai.schism.split.R.string.invite_error_expired, revoked.messageRes)
        assertEquals(ai.schism.split.R.string.invite_error_not_found, unknown.messageRes)
        assertFalse(settings.knownGroupIds.first().contains("g1"))
        assertTrue(groups.observeGroups().first().isEmpty())
    }
}
