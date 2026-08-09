package ai.schism.split.core.db

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of [ai.schism.split.core.db.DatabaseEncryption] that needs the SQLCipher native library:
 * a real Room database written in plaintext, migrated, then reopened through the SQLCipher factory.
 * If `sqlcipher_export()` dropped rows, lost `user_version`, or derived a different key from the
 * same passphrase, this is what catches it.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "encryption-migration-test.db"

    // A passphrase in the exact shape DatabaseEncryption mints, without touching the app's own
    // stored one — the Keystore round trip is covered by SecureTokenStoreTest.
    private val passphrase = Base64
        .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), Base64.NO_WRAP)
        .toByteArray(Charsets.UTF_8)

    @Before @After
    fun clean() {
        context.deleteDatabase(name)
    }

    private fun group(id: String) = GroupEntity(id, "Trip", "", "₹", "INR", "2026-07-05T00:00:00Z")

    /**
     * The fresh-install path, with the passphrase coming from the real Keystore-backed store rather
     * than the ad-hoc one above — everything `DbModule` does, minus the production database name.
     */
    @Test
    fun freshDatabaseIsCreatedEncrypted() = runBlocking {
        val file = context.getDatabasePath(name)
        val db = Room.databaseBuilder(context, SchismDb::class.java, name)
            .openHelperFactory(
                DatabaseEncryption.openHelperFactory(DatabaseEncryption.passphrase(context, file)),
            )
            .build()
        try {
            db.groupDao().upsertGroupWithParticipants(group("g1"), listOf(ParticipantEntity("p1", "g1", "A")))
            assertEquals("Trip", db.groupDao().observeGroup("g1").first()!!.group.name)
        } finally {
            db.close()
        }
        assertFalse("a new database must never be written in plaintext", DatabaseEncryption.isPlaintext(file))
    }

    @Test
    fun plaintextDatabaseSurvivesEncryption() = runBlocking {
        val plaintext = Room.databaseBuilder(context, SchismDb::class.java, name).build()
        plaintext.groupDao().upsertGroupWithParticipants(
            group("g1"),
            listOf(ParticipantEntity("p1", "g1", "A"), ParticipantEntity("p2", "g1", "B")),
        )
        plaintext.groupDao().setFavorite("g1", true)
        plaintext.close()

        val file = context.getDatabasePath(name)
        assertTrue("precondition: Room wrote a plaintext file", DatabaseEncryption.isPlaintext(file))

        DatabaseEncryption.encryptPlaintextDatabase(file, passphrase)

        assertFalse("database is still readable as plaintext", DatabaseEncryption.isPlaintext(file))
        val encrypted = Room.databaseBuilder(context, SchismDb::class.java, name)
            .openHelperFactory(DatabaseEncryption.openHelperFactory(passphrase))
            .build()
        try {
            val groups = encrypted.groupDao().observeGroups().first()
            assertEquals(1, groups.size)
            assertEquals("Trip", groups[0].group.name)
            assertEquals(2, groups[0].participants.size)
            assertTrue(groups[0].group.isFavorite)
        } finally {
            encrypted.close()
        }
    }
}
