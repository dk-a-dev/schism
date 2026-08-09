package ai.schism.split.groups.invite

import ai.schism.split.BuildConfig
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.GroupInvitePreviewDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The group's one shareable link — drop it in a chat and whoever holds it joins themselves. Unlike
 * [ParticipantInviteRepository] it binds to no participant: redeeming creates the redeemer's own.
 * Errors map exactly as the participant flow's do (see [mapInviteErrors]).
 */
@Singleton
class GroupInviteRepository @Inject constructor(
    private val api: ApiService,
) {
    // The organizer's own copy of the raw token they last minted, per group. The server stores only
    // hashes, so without this every visit to the invite screen would have to mint a fresh link and
    // silently kill the one already shared in a chat.
    // ponytail: process-scoped. A cold start mints a new link; persist this if that proves annoying.
    private val minted = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The link this device already minted for [groupId], if it is still the live one. */
    fun cached(groupId: String): String? = minted[groupId]

    /**
     * Organizer: mint the group's link. This revokes whichever link was live before it — the server
     * keeps only token hashes, so an existing link can never be re-displayed, only replaced.
     */
    suspend fun create(groupId: String): Result<String> =
        runCatching { api.createGroupInvite(groupId).token }
            .mapInviteErrors(onConflict = InviteError.AlreadyUsed)
            .onSuccess { minted[groupId] = it }

    /** Organizer: kill the live link without minting a replacement. */
    suspend fun revoke(groupId: String): Result<Unit> =
        runCatching { api.revokeGroupInvite(groupId) }
            .mapInviteErrors(onConflict = InviteError.AlreadyUsed)
            .onSuccess { minted.remove(groupId) }

    /** Recipient: group name and member count only. */
    suspend fun preview(token: String): Result<GroupInvitePreviewDto> =
        runCatching { api.previewGroupInvite(token) }
            .mapInviteErrors(onConflict = InviteError.AlreadyUsed)

    /** Recipient: join, creating their participant; idempotent, so a replay just returns the group. */
    suspend fun redeem(token: String): Result<String> =
        runCatching { api.redeemGroupInvite(token).groupId }
            .mapInviteErrors(onConflict = InviteError.AlreadyLinked)
}

/**
 * The https landing to share (`<backend>/i/g/<token>`), which bounces into
 * `schism://group-invite/<token>`. Like participant links, the token is the whole capability.
 */
fun groupInviteLink(token: String): String = BuildConfig.BACKEND_URL.trimEnd('/') + "/i/g/$token"

/**
 * Pull a group-link token out of anything pasted or scanned: the https landing, the
 * `schism://group-invite/<token>` deep link, or "" when the input is not one.
 */
fun parseGroupInviteToken(input: String): String {
    val trimmed = input.trim()
    val afterHost = when {
        trimmed.contains("/group-invite/") -> trimmed.substringAfterLast("/group-invite/")
        trimmed.contains("/i/g/") -> trimmed.substringAfterLast("/i/g/")
        else -> return ""
    }
    return afterHost.substringBefore('?').substringBefore('#').substringBefore('/').trim()
}
