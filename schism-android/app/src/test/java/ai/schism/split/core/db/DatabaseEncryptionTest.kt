package ai.schism.split.core.db

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real `sqlcipher_export()` needs the SQLCipher native library, which does not exist on the JVM,
 * so these cover the passphrase and the file handling around the export — the parts that decide
 * whether a user keeps their ledger. `DatabaseEncryptionInstrumentedTest` covers the export itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseEncryptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File

    /** Stands in for an encrypted database: same bytes, prefixed so it fails the plaintext check. */
    private val fakeExport: (File, File, ByteArray) -> Unit = { source, target, passphrase ->
        target.writeBytes("ENC:".toByteArray() + passphrase + source.readBytes())
    }

    @Before
    fun setUp() {
        context.getSharedPreferences("secure_db", Context.MODE_PRIVATE).edit().clear().commit()
        dir = File(context.cacheDir, "db-encryption-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun plaintextDb(contents: String = "rows"): File =
        File(dir, "schism.db").apply { writeBytes("SQLite format 3\u0000$contents".toByteArray()) }

    @Test
    fun `passphrase is 32 random bytes and is stable across calls`() {
        val file = File(dir, "schism.db")

        val first = DatabaseEncryption.passphrase(context, file)
        val second = DatabaseEncryption.passphrase(context, file)

        assertArrayEquals(first, second)
        assertEquals(32, Base64.decode(String(first, Charsets.UTF_8), Base64.NO_WRAP).size)
    }

    @Test
    fun `two installs do not share a passphrase`() {
        val file = File(dir, "schism.db")
        val first = DatabaseEncryption.passphrase(context, file)

        context.getSharedPreferences("secure_db", Context.MODE_PRIVATE).edit().clear().commit()

        assertFalse(first.contentEquals(DatabaseEncryption.passphrase(context, file)))
    }

    @Test
    fun `a lost passphrase never gets replaced over an encrypted database`() {
        val encrypted = File(dir, "schism.db").apply { writeBytes(ByteArray(64) { 7 }) }

        assertThrows(IllegalStateException::class.java) {
            DatabaseEncryption.passphrase(context, encrypted)
        }
        assertEquals(
            "the unreadable database must be left for a future recovery",
            64,
            encrypted.length().toInt(),
        )
    }

    @Test
    fun `a plaintext database is replaced by its encrypted copy`() {
        val file = plaintextDb("the whole ledger")
        val wal = File(file.path + "-wal").apply { writeText("pending") }
        val shm = File(file.path + "-shm").apply { writeText("index") }
        val passphrase = "key".toByteArray()

        DatabaseEncryption.encryptPlaintextDatabase(file, passphrase, fakeExport)

        assertFalse("must no longer be readable as plaintext", DatabaseEncryption.isPlaintext(file))
        assertEquals(
            "ENC:key" + "SQLite format 3\u0000the whole ledger",
            file.readText(),
        )
        assertFalse("a plaintext WAL beside an encrypted database is corruption", wal.exists())
        assertFalse(shm.exists())
        assertFalse(File(file.path + ".encrypting").exists())
    }

    @Test
    fun `a failed export leaves the plaintext database untouched`() {
        val file = plaintextDb()
        val before = file.readBytes()

        assertThrows(IllegalStateException::class.java) {
            DatabaseEncryption.encryptPlaintextDatabase(file, "key".toByteArray()) { _, target, _ ->
                target.writeBytes("half".toByteArray())
                throw IOException("disk full")
            }
        }

        assertArrayEquals("the user's data must survive a failed migration", before, file.readBytes())
        assertTrue(DatabaseEncryption.isPlaintext(file))
        assertFalse("no half-written database may be left behind", File(file.path + ".encrypting").exists())
    }

    @Test
    fun `an export that did not actually encrypt is rejected`() {
        val file = plaintextDb()
        val before = file.readBytes()

        assertThrows(IllegalStateException::class.java) {
            DatabaseEncryption.encryptPlaintextDatabase(file, "key".toByteArray()) { source, target, _ ->
                target.writeBytes(source.readBytes())
            }
        }

        assertArrayEquals(before, file.readBytes())
        assertFalse(File(file.path + ".encrypting").exists())
    }

    @Test
    fun `stale output from an interrupted attempt is discarded`() {
        val file = plaintextDb("ledger")
        File(file.path + ".encrypting").writeBytes("garbage from last time".toByteArray())

        DatabaseEncryption.encryptPlaintextDatabase(file, "key".toByteArray(), fakeExport)

        assertEquals("ENC:key" + "SQLite format 3\u0000ledger", file.readText())
    }

    @Test
    fun `a fresh install and an already encrypted database are left alone`() {
        val explode: (File, File, ByteArray) -> Unit = { _, _, _ -> throw AssertionError("exported") }
        val missing = File(dir, "schism.db")

        DatabaseEncryption.encryptPlaintextDatabase(missing, "key".toByteArray(), explode)
        assertFalse(missing.exists())

        val encrypted = missing.apply { writeBytes(ByteArray(64) { 7 }) }
        DatabaseEncryption.encryptPlaintextDatabase(encrypted, "key".toByteArray(), explode)
        assertArrayEquals(ByteArray(64) { 7 }, encrypted.readBytes())
    }
}
