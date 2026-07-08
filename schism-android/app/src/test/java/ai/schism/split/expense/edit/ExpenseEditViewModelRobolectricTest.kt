package ai.schism.split.expense.edit

import ai.schism.split.core.ai.LlmExpenseParser
import ai.schism.split.core.ai.ModelManager
import ai.schism.split.core.db.GroupEntity
import ai.schism.split.core.db.ParticipantEntity
import ai.schism.split.core.db.SchismDb
import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.expense.data.ExpenseRepository
import ai.schism.split.groups.data.GroupRepository
import ai.schism.split.sms.data.SmsRepository
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-backed tests that exercise [ExpenseEditViewModel] end to end against a real (in-memory)
 * Room DB, a real [ExpenseRepository]/[GroupRepository]/[SmsRepository]/[LlmExpenseParser] (the LLM
 * parser degrades to null since no model file is downloaded in tests, so parsing always falls back to
 * the deterministic regex parser) and a scripted [MockWebServer]. Complements the pure-function tests
 * in [ExpenseEditViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExpenseEditViewModelRobolectricTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var db: SchismDb
    private lateinit var settings: SettingsRepository
    private lateinit var groupRepo: GroupRepository
    private lateinit var expenseRepo: ExpenseRepository
    private lateinit var smsRepo: SmsRepository
    private lateinit var llmParser: LlmExpenseParser
    private lateinit var api: ai.schism.split.core.net.ApiService

    /** Set by a test to script the `getExpense` response for edit-mode prefill. */
    private var expenseDtoJson: String? = null

    /** Flips true once the mock server sees a DELETE on an expense path. */
    private var deleteRequested = false

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("/categories") -> MockResponse().setBody("[]")
                    request.method == "DELETE" && path.contains("/expenses/") -> {
                        deleteRequested = true
                        MockResponse().setResponseCode(200)
                    }
                    path.contains("/expenses/") && expenseDtoJson != null ->
                        MockResponse().setBody(expenseDtoJson!!)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SchismDb::class.java,
        ).allowMainThreadQueries().build()
        api = ApiClient.create(server.url("/").toString())
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        runBlocking { settings.clear() } // DataStore is a JVM singleton; isolate from other tests
        groupRepo = GroupRepository(api, db.groupDao(), settings)
        expenseRepo = ExpenseRepository(api, db.expenseDao(), db.outboxDao(), ApplicationProvider.getApplicationContext())
        smsRepo = SmsRepository(db.transactionDao())
        val modelManager = ModelManager(ApplicationProvider.getApplicationContext())
        llmParser = LlmExpenseParser(ApplicationProvider.getApplicationContext(), modelManager, settings)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        db.close()
    }

    private fun seedGroup(activeParticipantId: String? = "p1") {
        runBlocking {
            db.groupDao().upsertGroupWithParticipants(
                GroupEntity("g1", "Trip", "", "₹", "INR", "2026-07-05T00:00:00Z", activeParticipantId = activeParticipantId),
                listOf(ParticipantEntity("p1", "g1", "Dev"), ParticipantEntity("p2", "g1", "Sam")),
            )
        }
    }

    private fun vm(expenseId: String? = null, transactionId: String? = null) = ExpenseEditViewModel(
        groupRepo,
        expenseRepo,
        api,
        llmParser,
        smsRepo,
        SavedStateHandle(
            mapOf(
                "groupId" to "g1",
                "expenseId" to expenseId,
                "transactionId" to transactionId,
            ),
        ),
    )

    // ---- Task 3: editable expense date ----

    @Test
    fun onDateChangeUpdatesExpenseDate() = runTest(dispatcher) {
        seedGroup()
        val vm = vm()
        vm.state.first { it.participants.isNotEmpty() }

        vm.onDateChange("2026-08-01")

        assertEquals("2026-08-01", vm.state.value.expenseDate)
    }

    // ---- Task 4: delete expense, creator-only ----

    @Test
    fun deleteInEditModeCallsRepoAndInvokesCallback() = runTest(dispatcher) {
        seedGroup()
        expenseDtoJson = """{"id":"e1","groupId":"g1","title":"Dinner","amount":4200,"paidById":"p1",
            "splitMode":"EVENLY","paidFor":[{"participantId":"p1","shares":1},{"participantId":"p2","shares":1}]}"""
        val vm = vm(expenseId = "e1")
        vm.state.first { it.participants.isNotEmpty() }

        // delete() hits real Room + MockWebServer threads via viewModelScope (a real, non-test
        // dispatcher), so the callback fires asynchronously; await it with a deferred rather than
        // polling `submitting` (which starts false and would satisfy a naive check trivially).
        val deletedSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
        vm.delete { deletedSignal.complete(Unit) }
        deletedSignal.await()

        assertTrue(deleteRequested)
        assertFalse(vm.state.value.submitting)
    }

    @Test
    fun deleteInAddModeIsNoOp() = runTest(dispatcher) {
        seedGroup()
        val vm = vm() // no expenseId: add mode, delete() has nothing to delete
        vm.state.first { it.participants.isNotEmpty() }

        var deleted = false
        vm.delete { deleted = true }

        assertFalse(deleted)
        assertFalse(deleteRequested)
    }
}
