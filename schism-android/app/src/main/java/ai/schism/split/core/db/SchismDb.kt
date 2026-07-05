package ai.schism.split.core.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        GroupEntity::class,
        ParticipantEntity::class,
        ExpenseEntity::class,
        PaidForEntity::class,
        CategoryEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class SchismDb : RoomDatabase() {
    abstract fun groupDao(): GroupDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
}
