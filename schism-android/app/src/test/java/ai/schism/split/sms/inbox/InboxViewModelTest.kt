package ai.schism.split.sms.inbox

import ai.schism.split.core.db.SchismDb
import ai.schism.split.core.ui.UiState
import ai.schism.split.sms.data.SmsRepository
import ai.schism.split.sms.data.Transaction
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
class InboxViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: SchismDb
    private lateinit var repo: SmsRepository
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(context, SchismDb::class.java)
            .allowMainThreadQueries().build()
        repo = SmsRepository(db.transactionDao())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun inboxEmitsUnassignedThenDropsKeptPersonal() = runTest(dispatcher) {
        repo.ingest(SmsRepositoryFixtures.HDFC_SMS, "HDFCBK", 1_720_000_000_000L)

        val vm = InboxViewModel(repo, ai.schism.split.sms.receipt.ReceiptScanner(), context)
        // Room emits on its own executor, so await the Data emission rather than snapshotting.
        val txns = (vm.state.first { it is UiState.Data<*> } as UiState.Data<List<Transaction>>).value
        assertEquals(1, txns.size)
        assertEquals("SWIGGY", txns[0].merchant)

        vm.keepPersonal(txns[0].id)

        assertTrue(vm.state.first { it is UiState.Empty } is UiState.Empty)
        assertEquals(0, repo.observeInbox().first().size)
    }
}

private object SmsRepositoryFixtures {
    const val HDFC_SMS =
        "Sent Rs.450.00 From HDFC Bank A/C x1234 To SWIGGY On 05-07-26 Ref 501234567890 " +
            "Not You? Call 18002586161/SMS BLOCK UPI to 7308080808"
}
