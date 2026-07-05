package ai.schism.split.core.nav

object Routes {
    const val GROUPS = "groups"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val CREATE_GROUP = "groups/create"
    const val JOIN_GROUP = "groups/join"

    const val OPEN_GROUP = "open_group/{groupId}"
    const val GROUP_DETAIL = "groups/detail/{groupId}"
    const val GROUP_DASHBOARD = "groups/detail/{groupId}/dashboard"
    const val EXPENSE_EDIT = "groups/detail/{groupId}/expense?expenseId={expenseId}"

    fun groupDetail(groupId: String) = "groups/detail/$groupId"
    fun groupDashboard(groupId: String) = "groups/detail/$groupId/dashboard"
    fun addExpense(groupId: String) = "groups/detail/$groupId/expense"
    fun editExpense(groupId: String, expenseId: String) = "groups/detail/$groupId/expense?expenseId=$expenseId"
}
