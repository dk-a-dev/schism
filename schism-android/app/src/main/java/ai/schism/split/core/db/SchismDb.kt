package ai.schism.split.core.db

import ai.schism.split.sms.data.TransactionDao
import ai.schism.split.sms.data.TransactionEntity
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        GroupEntity::class,
        ParticipantEntity::class,
        ExpenseEntity::class,
        PaidForEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class SchismDb : RoomDatabase() {
    abstract fun groupDao(): GroupDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
}
