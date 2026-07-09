package ai.schism.split.claim

import ai.schism.split.core.db.GroupEntity
import ai.schism.split.core.db.ParticipantEntity
import ai.schism.split.core.db.SchismDb
import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.groups.data.GroupRepository
import ai.schism.split.sms.itemized.claim.ClaimSessionRepository
import ai.schism.split.sms.itemized.claim.ClaimSessionViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
class ClaimSessionViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var db: SchismDb
    private lateinit var settings: SettingsRepository
    private lateinit var groupRepo: GroupRepository
    private lateinit var claimRepo: ClaimSessionRepository

    private var putClaimsShouldLock = false
    private var putClaimsShouldStaleOnce = false
    private var putRequestCount = 0
    private var getRequestCount = 0

    private var readyRequestCount = 0
    // Mirrors the server: once someone marks ready, GET polls keep reporting them ready (the flag is
    // persisted), so the poll doesn't clobber the optimistic ready state.
    private var serverReady = false

    private val sessionBody = """
        {"id":"s1","groupId":"g1","creatorParticipantId":"p1","title":"Dinner","currency":"₹",
         "status":"open","items":[{"idx":0,"name":"Dish","qty":1,"amountMinor":10000}],
         "taxMinor":0,"feesMinor":0,"discountMinor":0,"roundoffMinor":0,"version":1,
         "claims":[],"owesPreview":{}}
    """.trimIndent()

    // Response to PUT /ready — the server echoes the refreshed session with the caller now listed ready.
    private val readySessionBody = """
        {"id":"s1","groupId":"g1","creatorParticipantId":"p1","title":"Dinner","currency":"₹",
         "status":"open","items":[{"idx":0,"name":"Dish","qty":1,"amountMinor":10000}],
         "taxMinor":0,"feesMinor":0,"discountMinor":0,"roundoffMinor":0,"version":1,
         "claims":[],"owesPreview":{},"readyParticipantIds":["p1"]}
    """.trimIndent()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    request.method == "PUT" && path.contains("/ready") -> {
                        readyRequestCount++
                        serverReady = request.body.readUtf8().contains("true")
                        MockResponse().setBody(if (serverReady) readySessionBody else sessionBody)
                    }
                    request.method == "PUT" && path.contains("/claims") -> {
                        putRequestCount++
                        when {
                            putClaimsShouldLock -> MockResponse().setResponseCode(409).setBody("""{"error":"LOCKED"}""")
                            putClaimsShouldStaleOnce && putRequestCount == 1 ->
                                MockResponse().setResponseCode(409).setBody("""{"error":"VERSION_STALE"}""")
                            else -> MockResponse().setResponseCode(200)
                        }
                    }
                    path.endsWith("/v1/claim-sessions/s1") -> {
                        getRequestCount++
                        MockResponse().setBody(if (serverReady) readySessionBody else sessionBody)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SchismDb::class.java,
        ).allowMainThreadQueries().build()
        val api = ApiClient.create(server.url("/").toString())
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        runBlocking { settings.clear() } // DataStore is a JVM singleton; isolate from other tests
        groupRepo = GroupRepository(api, db.groupDao(), settings)
        claimRepo = ClaimSessionRepository(api)
        runBlocking {
            db.groupDao().upsertGroupWithParticipants(
                GroupEntity("g1", "Trip", "", "₹", "INR", "2026-07-05T00:00:00Z"),
                listOf(ParticipantEntity("p1", "g1", "Dev"), ParticipantEntity("p2", "g1", "Ru")),
            )
            db.groupDao().setActiveParticipant("g1", "p1")
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        db.close()
    }

    private fun vm() = ClaimSessionViewModel(
        claimRepo, groupRepo, settings, SavedStateHandle(mapOf("sid" to "s1")),
    )

    @Test
    fun setWeightUpdatesMyOwesImmediately(): TestResult = runTest(dispatcher) {
        val vm = vm()
        vm.state.first { it.session != null && it.myParticipantId.isNotBlank() }

        vm.setWeight(0, 1.0)

        val owes = vm.state.first { it.myOwes > 0L }
        assertEquals("p1", owes.myParticipantId)
        assertEquals(10000L, owes.myOwes)
    }

    @Test
    fun toggleReadyPostsAndReflectsReadyParticipantIds(): TestResult = runTest(dispatcher) {
        val vm = vm()
        vm.state.first { it.session != null && it.myParticipantId.isNotBlank() }

        vm.toggleReady()

        // Optimistic update reflects immediately.
        val ready = vm.state.first { it.myReady }
        assertTrue("caller should be listed ready", ready.session!!.readyParticipantIds.contains("p1"))
        // The PUT lands on a MockWebServer thread; await it in real time (Default, not the virtual test clock).
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(2_000) { while (readyRequestCount < 1) kotlinx.coroutines.delay(10) }
        }
        assertEquals(1, readyRequestCount)
    }

    @Test
    fun lockedPutClaimsFinalizesAndStopsPolling(): TestResult = runTest(dispatcher) {
        putClaimsShouldLock = true
        val vm = vm()
        vm.state.first { it.session != null && it.myParticipantId.isNotBlank() }

        vm.setWeight(0, 1.0)
        // Fire the debounced PUT (registered as a virtual-time delay on the shared test scheduler)
        // without waiting 400 real ms.
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()

        val finalized = vm.state.first { it.status == "finalized" }
        assertEquals("finalized", finalized.status)

        // A locked write must stop the poller — advancing well past a poll interval must not produce
        // any further GET/PUT traffic that could resurrect the (now-locked) local state.
        val putsAfterLock = putRequestCount
        val getsAfterLock = getRequestCount
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        assertEquals(putsAfterLock, putRequestCount)
        assertEquals(getsAfterLock, getRequestCount)
    }

    @Test
    fun staleVersionRefetchesAndResubmitsTheWrite(): TestResult = runTest(dispatcher) {
        putClaimsShouldStaleOnce = true
        val vm = vm()
        vm.state.first { it.session != null && it.myParticipantId.isNotBlank() }

        vm.setWeight(0, 1.0)
        // Fire the debounced PUT without waiting 400 real ms.
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()

        // First PUT hits 409 VERSION_STALE; the VM must refetch AND re-issue the write (not just
        // silently keep the local edit unsent) so a second PUT lands and clears myWeights (only
        // `submitWeights`'s success path does that — if the stale write were never resubmitted,
        // myWeights would stay populated forever and putRequestCount would stay at 1).
        vm.state.first { it.myWeights.isEmpty() }
        assertEquals(2, putRequestCount)
        assertTrue("expected the stale-triggered refresh plus the initial load", getRequestCount >= 2)
    }
}
