package ai.schism.split.sms.itemized.claim

import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.ClaimSessionDto
import ai.schism.split.core.net.ClaimWeightDto
import ai.schism.split.core.net.CreateClaimSessionRequest
import ai.schism.split.core.net.FinalizeRequest
import ai.schism.split.core.net.FinalizeResponse
import ai.schism.split.core.net.PutClaimsRequest
import ai.schism.split.core.net.ResolutionDto
import ai.schism.split.core.net.VersionResponse
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/** Errors surfaced by [ClaimSessionRepository.putClaims], mapped from the 409 status + body code. */
sealed class ClaimError(message: String) : Exception(message) {
    /** 409 `LOCKED` — the creator has already finalized (or cancelled) this session. */
    data object Locked : ClaimError("This split was locked by the creator")

    /** 409 `VERSION_STALE` — the caller's `expectedVersion` is behind the server's; refetch and retry. */
    data object Stale : ClaimError("Someone else changed this split — refreshing")

    data class Other(val httpCode: Int, val body: String) : ClaimError("Couldn't save your claim (HTTP $httpCode)")
}

/**
 * Thin wrapper over [ApiService]'s claim-session endpoints, returning [Result] so callers don't have
 * to catch. [putClaims] is the one call that needs raw-response handling: the backend answers a
 * locked/stale write with HTTP 409 and a body code (`LOCKED` / `VERSION_STALE`), which this maps to
 * [ClaimError.Locked] / [ClaimError.Stale] instead of a generic failure.
 */
@Singleton
class ClaimSessionRepository @Inject constructor(
    private val api: ApiService,
) {
    suspend fun createSession(groupId: String, request: CreateClaimSessionRequest): Result<ClaimSessionDto> =
        runCatching { api.createClaimSession(groupId, request) }

    suspend fun getSession(sid: String): Result<ClaimSessionDto> =
        runCatching { api.getClaimSession(sid) }

    suspend fun putClaims(sid: String, expectedVersion: Int, weights: List<ClaimWeightDto>): Result<Unit> =
        runCatching {
            val response = api.putClaims(sid, PutClaimsRequest(expectedVersion, weights))
            if (!response.isSuccessful) throw errorFor(response)
        }

    suspend fun finalizeSession(
        sid: String,
        expectedVersion: Int,
        resolutions: List<ResolutionDto>,
    ): Result<FinalizeResponse> = runCatching { api.finalizeClaimSession(sid, FinalizeRequest(expectedVersion, resolutions)) }

    suspend fun cancelSession(sid: String): Result<Unit> = runCatching {
        val response = api.cancelClaimSession(sid)
        if (!response.isSuccessful) throw errorFor(response)
    }

    suspend fun editItems(sid: String, items: List<ai.schism.split.core.net.ClaimItemDto>): Result<VersionResponse> =
        runCatching { api.editClaimItems(sid, ai.schism.split.core.net.EditItemsRequest(items)) }

    private fun errorFor(response: Response<Unit>): ClaimError {
        val body = response.errorBody()?.string().orEmpty()
        return when {
            response.code() == 409 && body.contains("LOCKED") -> ClaimError.Locked
            response.code() == 409 && body.contains("VERSION_STALE") -> ClaimError.Stale
            else -> ClaimError.Other(response.code(), body)
        }
    }
}
