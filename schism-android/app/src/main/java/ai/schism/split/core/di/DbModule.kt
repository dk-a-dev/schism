package ai.schism.split.core.di

import ai.schism.split.core.db.CategoryDao
import ai.schism.split.core.db.DatabaseEncryption
import ai.schism.split.core.db.ExpenseDao
import ai.schism.split.core.db.GroupDao
import ai.schism.split.core.db.SchismDb
import ai.schism.split.sms.data.TransactionDao
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DbModule {

    private const val DB_NAME = "schism.db"

    /**
     * The database holds the user's whole financial picture, so it is SQLCipher-encrypted on disk.
     * Installs that predate this carry a plaintext file and are converted before Room opens it;
     * anything that goes wrong throws here rather than falling back to an unencrypted database.
     *
     * ponytail: the one-time conversion runs inline on whichever thread first injects the database,
     * the same way a Room migration blocks the first query. Move it behind a splash/worker if a real
     * ledger ever grows big enough for that copy to be felt.
     */
    @Provides
    @Singleton
    fun provideDb(@ApplicationContext context: Context): SchismDb {
        val file = context.getDatabasePath(DB_NAME)
        val passphrase = DatabaseEncryption.passphrase(context, file)
        DatabaseEncryption.encryptPlaintextDatabase(file, passphrase)
        return Room.databaseBuilder(context, SchismDb::class.java, DB_NAME)
            .openHelperFactory(DatabaseEncryption.openHelperFactory(passphrase))
            .build()
    }

    @Provides
    fun provideGroupDao(db: SchismDb): GroupDao = db.groupDao()

    @Provides
    fun provideExpenseDao(db: SchismDb): ExpenseDao = db.expenseDao()

    @Provides
    fun provideCategoryDao(db: SchismDb): CategoryDao = db.categoryDao()

    @Provides
    fun provideTransactionDao(db: SchismDb): TransactionDao = db.transactionDao()

    @Provides
    fun provideOutboxDao(db: SchismDb): ai.schism.split.core.db.OutboxDao = db.outboxDao()
}
