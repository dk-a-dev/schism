package ai.schism.split.groups.data

import ai.schism.split.core.db.GroupDao
import ai.schism.split.core.db.participantEntities
import ai.schism.split.core.db.toDomain
import ai.schism.split.core.db.toEntity
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.CreateGroupRequest
import ai.schism.split.core.net.GroupDto
import ai.schism.split.core.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/** The server refused a group we thought we belonged to (`403`); the cached copy has been dropped. */
class NotAMemberException : Exception("not a member of this group")

/**
 * Single source of truth for groups. Reads observe the Room cache (offline-viewable); writes hit
 * the API and then refresh the cache. Retrofit and Room suspend calls are main-safe, so no explicit
 * dispatcher hop is needed.
 */
@Singleton
class GroupRepository @Inject constructor(
    private val api: ApiService,
    private val groupDao: GroupDao,
    private val settings: SettingsRepository,
) {
    fun observeGroups(): Flow<List<Group>> =
        groupDao.observeGroups().map { list -> list.map { it.toDomain() } }

    fun observeGroup(id: String): Flow<Group?> =
        groupDao.observeGroup(id).map { it?.toDomain() }

    suspend fun refreshGroups(ids: List<String>): Result<Unit> = runCatching {
        if (ids.isEmpty()) return@runCatching
        api.listGroups(ids.joinToString(",")).forEach { cache(it) }
    }

    suspend fun refreshGroup(id: String): Result<Unit> = runCatching {
        try {
            cache(api.getGroup(id))
        } catch (e: HttpException) {
            // A group id is an identifier, not a capability: the server saying "you're not a member"
            // must also erase whatever this device still has cached for it.
            if (e.code() == 403) {
                forget(id)
                throw NotAMemberException()
            }
            throw e
        }
    }

    /**
     * Ask the server which groups this account actually belongs to and adopt any this device has
     * never seen.
     *
     * Without this the device only ever refetches the ids it happened to learn locally, so a group
     * created on another phone — or one someone else added you to — stays invisible until the next
     * sign-in. That silently broke the promise that an account syncs groups across devices.
     *
     * Membership is only ever added here, never removed: leaving a group is an explicit action, and
     * a transient server hiccup must not wipe a device's list.
     */
    suspend fun syncMembership(): Result<List<String>> = runCatching {
        val ids = api.myGroups().groupIds
        ids.forEach { settings.addKnownGroup(it) }
        ids
    }

    /** Forget a group on this device: drop the cached copy and stop refreshing it. */
    suspend fun forget(id: String) {
        groupDao.deleteGroup(id)
        settings.removeKnownGroup(id)
    }

    suspend fun createGroup(request: CreateGroupRequest): Result<String> = runCatching {
        val id = api.createGroup(request).groupId
        settings.addKnownGroup(id)
        cache(api.getGroup(id))
        id
    }

    suspend fun updateGroup(id: String, request: CreateGroupRequest): Result<Unit> = runCatching {
        cache(api.updateGroup(id, request))
    }

    suspend fun setFavorite(id: String, favorite: Boolean) = groupDao.setFavorite(id, favorite)

    suspend fun setActiveParticipant(id: String, participantId: String?) =
        groupDao.setActiveParticipant(id, participantId)

    private suspend fun cache(dto: GroupDto) =
        groupDao.upsertGroupWithParticipants(dto.toEntity(), dto.participantEntities())
}
