package ai.schism.split.walkthrough

import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for account-scoped progress. Implementations may use DataStore, but must key
 * every operation by the authenticated user id and persist only [WalkthroughState].
 */
interface WalkthroughRepository {
    fun observe(userId: String): Flow<WalkthroughState?>

    suspend fun save(userId: String, state: WalkthroughState)

    suspend fun clear(userId: String)
}
