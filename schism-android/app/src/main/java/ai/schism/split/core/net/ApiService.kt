package ai.schism.split.core.net

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @POST("v1/users")
    suspend fun registerUser(@Body body: UserRequest): UserDto

    @retrofit2.http.DELETE("v1/users/me")
    suspend fun deleteAccount(): retrofit2.Response<Unit>

    @POST("v1/auth/register")
    suspend fun authRegister(@Body body: AuthRequest): AuthResponse

    @POST("v1/auth/login")
    suspend fun authLogin(@Body body: AuthRequest): AuthResponse

    @GET("v1/users/me/groups")
    suspend fun myGroups(): MyGroupsDto

    @GET("v1/categories")
    suspend fun listCategories(): List<CategoryDto>

    @GET("v1/groups")
    suspend fun listGroups(@Query("ids") ids: String): List<GroupDto>

    @POST("v1/groups")
    suspend fun createGroup(@Body body: CreateGroupRequest): CreateGroupResponse

    @GET("v1/groups/{id}")
    suspend fun getGroup(@Path("id") id: String): GroupDto

    @PUT("v1/groups/{id}")
    suspend fun updateGroup(@Path("id") id: String, @Body body: CreateGroupRequest): GroupDto

    @GET("v1/groups/{id}/expenses")
    suspend fun listExpenses(@Path("id") groupId: String): List<ExpenseDto>

    @GET("v1/groups/{id}/expenses/{expenseId}")
    suspend fun getExpense(
        @Path("id") groupId: String,
        @Path("expenseId") expenseId: String,
    ): ExpenseDto

    @POST("v1/groups/{id}/expenses")
    suspend fun createExpense(
        @Path("id") groupId: String,
        @Body body: ExpenseRequest,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
    ): ExpenseDto

    @PUT("v1/groups/{id}/expenses/{expenseId}")
    suspend fun updateExpense(
        @Path("id") groupId: String,
        @Path("expenseId") expenseId: String,
        @Body body: ExpenseRequest,
    ): ExpenseDto

    @DELETE("v1/groups/{id}/expenses/{expenseId}")
    suspend fun deleteExpense(
        @Path("id") groupId: String,
        @Path("expenseId") expenseId: String,
    ): Response<Unit>

    @GET("v1/groups/{id}/balances")
    suspend fun getBalances(@Path("id") groupId: String): BalancesResponseDto

    @GET("v1/groups/{id}/activities")
    suspend fun listActivities(@Path("id") groupId: String): List<ActivityDto>

    @GET("v1/groups/{id}/dashboard")
    suspend fun groupDashboard(
        @Path("id") groupId: String,
        @Query("participant") participant: String? = null,
    ): GroupDashboardDto

    @GET("v1/dashboard")
    suspend fun personalDashboard(
        @Query("participant") participant: String,
        @Query("groupIds") groupIds: String,
    ): PersonalDashboardDto

    // ---- claim links (alpha) ----

    @POST("v1/groups/{id}/claim-sessions")
    suspend fun createClaimSession(
        @Path("id") groupId: String,
        @Body body: CreateClaimSessionRequest,
    ): ClaimSessionDto

    @GET("v1/claim-sessions/{sid}")
    suspend fun getClaimSession(@Path("sid") sid: String): ClaimSessionDto

    @PUT("v1/claim-sessions/{sid}/claims")
    suspend fun putClaims(
        @Path("sid") sid: String,
        @Body body: PutClaimsRequest,
    ): Response<Unit>

    @POST("v1/claim-sessions/{sid}/finalize")
    suspend fun finalizeClaimSession(
        @Path("sid") sid: String,
        @Body body: FinalizeRequest,
    ): Response<FinalizeResponse>

    @POST("v1/claim-sessions/{sid}/cancel")
    suspend fun cancelClaimSession(@Path("sid") sid: String): Response<Unit>

    @PUT("v1/claim-sessions/{sid}/ready")
    suspend fun setReady(
        @Path("sid") sid: String,
        @Body body: SetReadyRequest,
    ): Response<ClaimSessionDto>

    @PATCH("v1/claim-sessions/{sid}/items")
    suspend fun editClaimItems(
        @Path("sid") sid: String,
        @Body body: EditItemsRequest,
    ): VersionResponse
}
