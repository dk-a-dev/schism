package ai.schism.split.expense.data

import ai.schism.split.core.db.ExpenseDao
import ai.schism.split.core.db.OutboxDao
import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.ExpenseRequest
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.decodeFromString
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Replays queued offline writes (see [OutboxEntity]) to the server, newest connectivity permitting.
 * Each write carries an idempotency key so a retry never double-posts. After a group's queue drains,
 * its expenses are refreshed so the temporary optimistic rows are replaced by server truth.
 */
@HiltWorker
class OutboxSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val outboxDao: OutboxDao,
    private val expenseDao: ExpenseDao,
    private val expenseRepo: ExpenseRepository,
    private val api: ApiService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entries = outboxDao.all()
        if (entries.isEmpty()) return Result.success()

        var stillOffline = false
        for ((groupId, list) in entries.groupBy { it.groupId }) {
            var reachedServer = true
            for (e in list) {
                val request = runCatching {
                    ApiClient.json.decodeFromString<ExpenseRequest>(e.payload)
                }.getOrNull()
                if (request == null) {
                    outboxDao.delete(e.localId)
                    continue
                }
                try {
                    api.createExpense(groupId, request, e.idempotencyKey)
                    outboxDao.delete(e.localId)
                } catch (io: IOException) {
                    reachedServer = false
                    stillOffline = true
                    break
                } catch (t: Throwable) {
                    // Permanent error (e.g. validation): drop the queued write + its optimistic row.
                    outboxDao.delete(e.localId)
                    expenseDao.deleteExpense(e.optimisticExpenseId)
                }
            }
            if (reachedServer) expenseRepo.refreshExpenses(groupId)
        }
        return if (stillOffline) Result.retry() else Result.success()
    }

    companion object {
        /** Enqueue a network-gated, backing-off sync of the outbox (collapses duplicate requests). */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "outbox_sync", ExistingWorkPolicy.KEEP, request,
            )
        }
    }
}
