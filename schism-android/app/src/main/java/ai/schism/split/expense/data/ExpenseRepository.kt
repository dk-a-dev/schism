package ai.schism.split.expense.data

import ai.schism.split.core.db.ExpenseDao
import ai.schism.split.core.db.ExpenseEntity
import ai.schism.split.core.db.ExpenseWithPaidFor
import ai.schism.split.core.db.OutboxDao
import ai.schism.split.core.db.OutboxEntity
import ai.schism.split.core.db.PaidForEntity
import ai.schism.split.core.db.paidForEntities
import ai.schism.split.core.db.toDomain
import ai.schism.split.core.db.toEntity
import ai.schism.split.core.db.toWithPaidFor
import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.ExpenseDto
import ai.schism.split.core.net.ExpenseRequest
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
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
    private val outboxDao: OutboxDao,
    @ApplicationContext private val context: Context,
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
        try {
            cache(api.createExpense(groupId, request, idempotencyKey))
        } catch (io: IOException) {
            // Offline: save it locally (shown immediately) and queue it to sync on reconnect.
            queueOffline(groupId, request, idempotencyKey)
        }
    }

    /** Optimistically record an expense in Room and enqueue it in the outbox for later sync. */
    private suspend fun queueOffline(
        groupId: String,
        request: ExpenseRequest,
        idempotencyKey: String?,
    ): Expense {
        val tempId = "local-" + UUID.randomUUID().toString()
        val key = idempotencyKey ?: UUID.randomUUID().toString()
        val entity = ExpenseEntity(
            id = tempId,
            groupId = groupId,
            title = request.title,
            amount = request.amount,
            categoryId = request.categoryId,
            expenseDate = request.expenseDate ?: LocalDate.now().toString(),
            paidById = request.paidById,
            splitMode = request.splitMode,
            isReimbursement = request.isReimbursement,
            notes = request.notes,
            createdAt = Instant.now().toString(),
            addedBy = request.addedBy ?: "",
        )
        val paidFor = request.paidFor.map { PaidForEntity(tempId, it.participantId, it.shares) }
        expenseDao.upsertExpenseWithPaidFor(entity, paidFor)
        outboxDao.insert(
            OutboxEntity(
                groupId = groupId,
                idempotencyKey = key,
                optimisticExpenseId = tempId,
                payload = ApiClient.json.encodeToString(request),
                createdAt = System.currentTimeMillis(),
            ),
        )
        OutboxSyncWorker.enqueue(context)
        return ExpenseWithPaidFor(entity, paidFor).toDomain()
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
