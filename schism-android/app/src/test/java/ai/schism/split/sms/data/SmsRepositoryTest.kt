package ai.schism.split.sms.data

import ai.schism.split.core.db.SchismDb
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmsRepositoryTest {
    private lateinit var db: SchismDb
    private lateinit var repo: SmsRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SchismDb::class.java,
        ).allowMainThreadQueries().build()
        repo = SmsRepository(db.transactionDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun ingestingSameSmsTwiceDedupesToOneRow() = runTest {
        repo.ingest(HDFC_SMS, "HDFCBK", 1_720_000_000_000L)
        repo.ingest(HDFC_SMS, "HDFCBK", 1_720_000_000_000L)

        val inbox = repo.observeInbox().first()
        assertEquals(1, inbox.size)
        assertEquals(45_000L, inbox[0].amountMinor)
        assertEquals("SWIGGY", inbox[0].merchant)
    }

    @Test
    fun keepPersonalRemovesFromInbox() = runTest {
        repo.ingest(HDFC_SMS, "HDFCBK", 1_720_000_000_000L)
        val id = repo.observeInbox().first().single().id

        repo.keepPersonal(id)

        assertEquals(0, repo.observeInbox().first().size)
        assertEquals(TransactionStatus.PERSONAL, db.transactionDao().getById(id)?.status)
    }

    @Test
    fun creditsAndUnknownSendersAreSkipped() = runTest {
        repo.ingest("You have received a promo offer!", "PROMO", 1L)
        assertEquals(0, repo.observeInbox().first().size)
    }

    @Test
    fun markPushedFlipsStatusAndRecordsRemoteIds() = runTest {
        repo.ingest(HDFC_SMS, "HDFCBK", 1_720_000_000_000L)
        val id = repo.observeInbox().first().single().id

        repo.markPushed(id, "g1", "e1")

        assertEquals(0, repo.observeInbox().first().size)
        val row = db.transactionDao().getById(id)!!
        assertEquals(TransactionStatus.PUSHED, row.status)
        assertEquals("g1", row.remoteGroupId)
        assertEquals("e1", row.remoteExpenseId)
    }

    companion object {
        const val HDFC_SMS =
            "Sent Rs.450.00 From HDFC Bank A/C x1234 To SWIGGY On 05-07-26 Ref 501234567890 " +
                "Not You? Call 18002586161/SMS BLOCK UPI to 7308080808"
    }
}
