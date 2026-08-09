package ai.schism.split.groups.invite

import ai.schism.split.BuildConfig
import ai.schism.split.R
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.InvitePreviewDto
import androidx.annotation.StringRes
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every outcome the invite endpoints can produce, each with its own explicit message. The backend
 * (see `internal/api/invites.go`) answers `404` not found, `410` expired, `409` "cannot be redeemed",
 * `403` not a member. `409` is deliberately mapped per call site: a preview only ever conflicts on a
 * replayed (already redeemed) token, while a redeem that got past a fresh preview conflicts because
 * the participant — or the caller — is already linked to an account.
 */
sealed class InviteError(@StringRes val messageRes: Int) : Exception() {
    data object NotFound : InviteError(R.string.invite_error_not_found)
    data object Expired : InviteError(R.string.invite_error_expired)
    data object AlreadyUsed : InviteError(R.string.invite_error_used)
    data object AlreadyLinked : InviteError(R.string.invite_error_linked)
    data object NotMember : InviteError(R.string.invite_error_not_member)

    /** 401 — the session gate ([ai.schism.split.core.net.SessionExpiredInterceptor]) takes over. */
    data object SignInRequired : InviteError(R.string.invite_error_sign_in)
}

/** The message to show for any invite failure, including non-[InviteError] (network/parse) ones. */
@StringRes
fun inviteMessageRes(error: Throwable): Int =
    (error as? InviteError)?.messageRes ?: R.string.invite_error_generic

/** Thin [Result]-returning wrapper over the three invite endpoints. */
@Singleton
class ParticipantInviteRepository @Inject constructor(
    private val api: ApiService,
) {
    /** Organizer: mint a fresh one-time token for one unlinked participant. */
    suspend fun create(groupId: String, participantId: String): Result<String> =
        runCatching { api.createParticipantInvite(groupId, participantId).token }
            .mapInviteErrors(onConflict = InviteError.AlreadyLinked)

    /** Recipient: names-only peek before committing. */
    suspend fun preview(token: String): Result<InvitePreviewDto> =
        runCatching { api.previewInvite(token) }
            .mapInviteErrors(onConflict = InviteError.AlreadyUsed)

    /** Recipient: link this account to the invite's participant; returns the group id. */
    suspend fun redeem(token: String): Result<String> =
        runCatching { api.redeemInvite(token).groupId }
            .mapInviteErrors(onConflict = InviteError.AlreadyLinked)

}

/** Shared by both invite repositories — the backend uses one error mapping for both flows. */
internal fun <T> Result<T>.mapInviteErrors(onConflict: InviteError): Result<T> =
    recoverCatching { e ->
        throw when ((e as? HttpException)?.code()) {
            401 -> InviteError.SignInRequired
            403 -> InviteError.NotMember
            404 -> InviteError.NotFound
            409 -> onConflict
            410 -> InviteError.Expired
            else -> e
        }
    }

/**
 * The https landing to share (`<backend>/i/<token>`) so messengers linkify it; that page bounces
 * into `schism://invite/<token>`. Tokens are capabilities — never share a group id.
 */
fun inviteLink(token: String): String = BuildConfig.BACKEND_URL.trimEnd('/') + "/i/$token"

/**
 * Pull the invite token out of anything a user can paste or scan: the https landing, the
 * `schism://invite/<token>` deep link, or a bare token. Returns "" when there is none.
 */
fun parseInviteToken(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || isLegacyGroupLink(trimmed)) return ""
    val afterHost = when {
        trimmed.contains("/invite/") -> trimmed.substringAfterLast("/invite/")
        trimmed.contains("/i/") -> trimmed.substringAfterLast("/i/")
        trimmed.contains("://") -> return "" // some other link entirely
        else -> trimmed
    }
    return afterHost.substringBefore('?').substringBefore('#').substringBefore('/').trim()
}

/**
 * A v1.2 group link (`schism://group/<id>`, `<backend>/g/<id>`). Those ids are identifiers, not
 * capabilities: we recognise them only to explain that a new invite is needed, and never fetch or
 * cache the group behind one.
 */
fun isLegacyGroupLink(input: String): Boolean {
    val trimmed = input.trim()
    return trimmed.contains("/group/") || trimmed.contains("/g/")
}
