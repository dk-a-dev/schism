package ai.schism.split.core.net

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @POST("v1/users")
    suspend fun registerUser(@Body body: UserRequest): UserDto

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
}
