package ai.schism.split.core.db

import ai.schism.split.core.security.SecureTokenStore
import android.content.Context
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Keeps `schism.db` — parsed bank transactions, expenses, groups and the sync outbox — encrypted at
 * rest with SQLCipher, and converts the plaintext databases that shipped in earlier sideloaded
 * builds.
 *
 * There is deliberately no plaintext fallback anywhere in here: every failure throws, because an
 * app that will not start is recoverable and a financial ledger silently left readable is not.
 */
object DatabaseEncryption {

    /** `sqlcipher_export` writes into an attached database; this is the temporary attach target. */
    private const val IN_PROGRESS_SUFFIX = ".encrypting"

    /** SQLite writes these next to the main file. They belong to the file they are named after. */
    private val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

    /** The 16-byte magic every plaintext SQLite file opens with, NUL terminator included. */
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * The passphrase for [databaseFile]: 32 `SecureRandom` bytes minted on first run and kept in the
     * same Keystore-backed AES-256-GCM store as the auth token.
     *
     * It is Base64'd rather than used raw, which keeps 256 bits of entropy while making the
     * passphrase printable ASCII. That matters for [encryptPlaintextDatabase]: `ATTACH ... KEY ?`
     * reads its argument as text, so a printable passphrase is guaranteed to reach SQLCipher's key
     * derivation as the exact same bytes that [openHelperFactory] hands it.
     */
    fun passphrase(context: Context, databaseFile: File): ByteArray {
        val store = SecureTokenStore(context, fileName = "secure_db", entryKey = "passphrase_v1")
        store.read().takeIf { it.isNotEmpty() }?.let { return it.toByteArray(Charsets.UTF_8) }

        // Reaching here with an already-encrypted database means the Keystore key is gone, so the
        // data is unreadable either way. Minting a second passphrase over the top would overwrite
        // the only thing that could ever have opened it, so refuse instead.
        check(!databaseFile.isFile || isPlaintext(databaseFile)) {
            "No database passphrase is stored but ${databaseFile.name} is encrypted; refusing to " +
                "mint a new one over an unreadable database."
        }

        val random = ByteArray(32)
        SecureRandom().nextBytes(random)
        val generated = Base64.encodeToString(random, Base64.NO_WRAP)
        random.fill(0)
        // Throws if it cannot be persisted; a passphrase we cannot read back is data loss.
        store.write(generated)
        check(store.read() == generated) { "Database passphrase did not survive being stored." }
        return generated.toByteArray(Charsets.UTF_8)
    }

    /**
     * Room's open helper, keyed with [passphrase].
     *
     * The factory keeps the array for the process lifetime — every pooled connection is keyed from
     * it — so unlike the transient buffers above it cannot be zeroed after use.
     */
    fun openHelperFactory(passphrase: ByteArray): SupportOpenHelperFactory {
        loadNativeLibrary()
        return SupportOpenHelperFactory(passphrase)
    }

    /** True when [file] is an unencrypted SQLite database. An encrypted one starts with salt. */
    fun isPlaintext(file: File): Boolean {
        if (!file.isFile) return false
        val header = ByteArray(SQLITE_HEADER.size)
        return runCatching {
            file.inputStream().use { it.readFully(header) } && header.contentEquals(SQLITE_HEADER)
        }.getOrDefault(false)
    }

    /**
     * Converts a plaintext [databaseFile] to an encrypted one in place, and does nothing at all if
     * it is missing (fresh install) or already encrypted.
     *
     * The export runs into a sibling `.encrypting` file, so the plaintext database is only ever read
     * and the original is replaced by a single atomic `rename(2)` once a complete encrypted copy
     * exists. Any failure deletes the partial copy and rethrows, leaving the plaintext database
     * exactly as it was — a caller that sees an exception has lost nothing and can retry.
     *
     * [export] is injectable purely so the file handling above can be tested off-device; production
     * always uses SQLCipher's own `sqlcipher_export()`.
     */
    fun encryptPlaintextDatabase(
        databaseFile: File,
        passphrase: ByteArray,
        export: (source: File, target: File, passphrase: ByteArray) -> Unit = ::sqlcipherExport,
    ) {
        if (!isPlaintext(databaseFile)) return

        val target = File(databaseFile.parentFile, databaseFile.name + IN_PROGRESS_SUFFIX)
        deleteWithSidecars(target) // leftovers from an attempt that was killed mid-flight
        try {
            export(databaseFile, target, passphrase)
            check(target.isFile && !isPlaintext(target)) {
                "Encrypted export of ${databaseFile.name} is missing or not encrypted."
            }
        } catch (failure: Throwable) {
            deleteWithSidecars(target)
            throw IllegalStateException(
                "Could not encrypt ${databaseFile.name}; the plaintext database was left untouched.",
                failure,
            )
        }

        // The plaintext WAL was checkpointed into the database that was just exported, so its
        // contents are already inside `target`. It has to go before the rename, not after: an
        // encrypted database sitting next to a plaintext WAL is a corrupt database, whereas a
        // crash between these two steps just leaves the untouched plaintext database behind.
        SIDECAR_SUFFIXES.forEach { suffix ->
            File(databaseFile.path + suffix).delete()
            File(target.path + suffix).delete()
        }
        check(target.renameTo(databaseFile)) {
            "Could not move the encrypted database over ${databaseFile.name}."
        }
    }

    /**
     * SQLCipher's supported plaintext migration: attach an empty keyed database and copy the schema
     * and every row into it.
     */
    private fun sqlcipherExport(source: File, target: File, passphrase: ByteArray) {
        loadNativeLibrary()
        // Binding the passphrase as text is why it is Base64 and not raw bytes: these are the exact
        // bytes SupportOpenHelperFactory will later key the database with.
        val key = String(passphrase, Charsets.UTF_8)
        val version: Int

        // No password argument, which is the point: `source` is the plaintext database. It already
        // exists, but ATTACH inherits the main connection's open flags, so without CREATE_IF_NECESSARY
        // it cannot create the export target and fails with SQLITE_CANTOPEN.
        val plaintext = SQLiteDatabase.openDatabase(
            source.path,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
        )
        try {
            version = plaintext.version
            // execSQL rather than rawExecSQL for the ATTACH: only that path recognises the statement
            // and collapses the WAL connection pool down to one connection. Without it the export
            // below can be handed a different connection, which has no `encrypted` schema attached.
            plaintext.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf(target.path, key))
            plaintext.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            plaintext.execSQL("DETACH DATABASE encrypted")
        } finally {
            plaintext.close()
        }

        // sqlcipher_export() copies tables, not `user_version`, and Room will not open a database
        // whose version it cannot read. Reopening to set it also proves the passphrase really does
        // open what was just written — before the original is replaced, while it still exists.
        val encrypted = SQLiteDatabase.openDatabase(target.path, key, null, SQLiteDatabase.OPEN_READWRITE, null)
        try {
            encrypted.version = version
        } finally {
            encrypted.close()
        }
    }

    private fun deleteWithSidecars(file: File) {
        file.delete()
        SIDECAR_SUFFIXES.forEach { File(file.path + it).delete() }
    }

    private fun loadNativeLibrary() = System.loadLibrary("sqlcipher")

    private fun InputStream.readFully(into: ByteArray): Boolean {
        var read = 0
        while (read < into.size) {
            val n = read(into, read, into.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }
}
