package ai.schism.split.core.di

import ai.schism.split.core.db.CategoryDao
import ai.schism.split.core.db.ExpenseDao
import ai.schism.split.core.db.GroupDao
import ai.schism.split.core.db.SchismDb
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

    @Provides
    @Singleton
    fun provideDb(@ApplicationContext context: Context): SchismDb =
        Room.databaseBuilder(context, SchismDb::class.java, "schism.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideGroupDao(db: SchismDb): GroupDao = db.groupDao()

    @Provides
    fun provideExpenseDao(db: SchismDb): ExpenseDao = db.expenseDao()

    @Provides
    fun provideCategoryDao(db: SchismDb): CategoryDao = db.categoryDao()
}
