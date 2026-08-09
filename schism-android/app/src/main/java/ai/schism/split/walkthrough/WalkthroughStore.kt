package ai.schism.split.walkthrough

import ai.schism.split.core.settings.SettingsRepository
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

private val Context.walkthroughDataStore by preferencesDataStore("walkthrough")

/** The signed-in backend user id, so walkthrough progress is per account. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WalkthroughUserId

/**
 * Per-account walkthrough progress in its own DataStore file. Only tour status, step, and dismissed
 * hint ids are written — never group, expense, or identity data.
 */
@Singleton
class DataStoreWalkthroughRepository @Inject constructor(
    @ApplicationContext context: Context,
) : WalkthroughRepository {
    private val ds = context.walkthroughDataStore

    override fun observe(userId: String): Flow<WalkthroughState?> =
        ds.data.map { prefs -> prefs[key(userId)]?.let(::decodeWalkthroughState) }

    override suspend fun save(userId: String, state: WalkthroughState) {
        ds.edit { it[key(userId)] = encodeWalkthroughState(state) }
    }

    override suspend fun clear(userId: String) {
        ds.edit { it.remove(key(userId)) }
    }

    private fun key(userId: String) =
        stringPreferencesKey("state_" + userId.ifBlank { ANONYMOUS_ACCOUNT })

    companion object {
        /** Progress recorded before the device registers an account. */
        const val ANONYMOUS_ACCOUNT = "anonymous"
    }
}

/** `version|status|step|hint,hint` — small enough that a serializer would be more code than this. */
internal fun encodeWalkthroughState(state: WalkthroughState): String = listOf(
    state.version.toString(),
    state.status.name,
    state.currentStep?.name.orEmpty(),
    state.dismissedHintIds.sorted().joinToString(","),
).joinToString("|")

/** Returns null for anything unreadable so a corrupt record simply re-offers the tour. */
internal fun decodeWalkthroughState(raw: String): WalkthroughState? {
    val parts = raw.split("|")
    if (parts.size != 4) return null
    val version = parts[0].toIntOrNull() ?: return null
    val status = WalkthroughStatus.entries.firstOrNull { it.name == parts[1] } ?: return null
    val step = parts[2].takeIf { it.isNotEmpty() }
        ?.let { name -> WalkthroughStep.entries.firstOrNull { it.name == name } ?: return null }
    return WalkthroughState(
        version = version,
        status = status,
        currentStep = step,
        dismissedHintIds = parts[3].split(",").filter { it.isNotBlank() }.toSet(),
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WalkthroughModule {
    @Binds
    @Singleton
    abstract fun bindWalkthroughRepository(impl: DataStoreWalkthroughRepository): WalkthroughRepository

    companion object {
        @Provides
        @WalkthroughUserId
        fun userId(settings: SettingsRepository): Flow<String> = settings.userId
    }
}
