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
import javax.inject.Inject
import javax.inject.Singleton

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
        cache(api.getGroup(id))
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
