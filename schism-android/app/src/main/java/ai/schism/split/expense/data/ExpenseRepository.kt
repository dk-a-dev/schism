package ai.schism.split.expense.data

import ai.schism.split.core.db.ExpenseDao
import ai.schism.split.core.db.paidForEntities
import ai.schism.split.core.db.toDomain
import ai.schism.split.core.db.toEntity
import ai.schism.split.core.db.toWithPaidFor
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.ExpenseDto
import ai.schism.split.core.net.ExpenseRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for expenses within a group. Reads observe the Room cache
 * (offline-viewable); writes hit the API and then refresh the cache. Balances and activities are
 * server-computed and are fetched live, never cached. Retrofit and Room suspend calls are main-safe,
 * so no explicit dispatcher hop is needed.
 */
@Singleton
class ExpenseRepository @Inject constructor(
    private val api: ApiService,
    private val expenseDao: ExpenseDao,
) {
    fun observeExpenses(groupId: String): Flow<List<Expense>> =
        expenseDao.observeExpenses(groupId).map { list -> list.map { it.toDomain() } }

    suspend fun refreshExpenses(groupId: String): Result<Unit> = runCatching {
        val remote = api.listExpenses(groupId).map { it.toWithPaidFor() }
        expenseDao.replaceGroupExpenses(groupId, remote)
    }

    suspend fun createExpense(
        groupId: String,
        request: ExpenseRequest,
        idempotencyKey: String? = null,
    ): Result<Expense> = runCatching {
        cache(api.createExpense(groupId, request, idempotencyKey))
    }

    suspend fun updateExpense(
        groupId: String,
        expenseId: String,
        request: ExpenseRequest,
    ): Result<Expense> = runCatching {
        cache(api.updateExpense(groupId, expenseId, request))
    }

    suspend fun deleteExpense(groupId: String, expenseId: String): Result<Unit> = runCatching {
        // Response<Unit> does not throw on non-2xx, so guard before evicting the cache.
        val response = api.deleteExpense(groupId, expenseId)
        check(response.isSuccessful) { "Delete failed: HTTP ${response.code()}" }
        expenseDao.deleteExpense(expenseId)
    }

    suspend fun getBalances(groupId: String): Result<Balances> = runCatching {
        api.getBalances(groupId).toDomain()
    }

    suspend fun getActivities(groupId: String): Result<List<Activity>> = runCatching {
        api.listActivities(groupId).map { it.toDomain() }
    }

    private suspend fun cache(dto: ExpenseDto): Expense {
        expenseDao.upsertExpenseWithPaidFor(dto.toEntity(), dto.paidForEntities())
        return dto.toWithPaidFor().toDomain()
    }
}
