package ai.schism.split.core.nav

object Routes {
    const val GROUPS = "groups"
    const val DASHBOARD = "dashboard"
    const val SPENDING = "spending"
    const val INBOX = "inbox"
    const val SETTINGS = "settings"
    const val CREATE_GROUP = "groups/create"
    const val JOIN_GROUP = "groups/join"

    const val PUSH_SPLIT = "sms/split/{transactionId}"
    fun pushSplit(transactionId: String) = "sms/split/$transactionId"

    const val RECEIPT_ITEMIZED = "sms/itemized"

    const val OPEN_GROUP = "open_group/{groupId}"
    const val GROUP_DETAIL = "groups/detail/{groupId}"
    const val GROUP_DASHBOARD = "groups/detail/{groupId}/dashboard"
    const val EXPENSE_EDIT = "groups/detail/{groupId}/expense?expenseId={expenseId}"
    const val INVITE = "groups/detail/{groupId}/invite"
    const val EDIT_GROUP = "groups/detail/{groupId}/edit"

    fun groupDetail(groupId: String) = "groups/detail/$groupId"
    fun groupDashboard(groupId: String) = "groups/detail/$groupId/dashboard"
    fun addExpense(groupId: String) = "groups/detail/$groupId/expense"
    fun editExpense(groupId: String, expenseId: String) = "groups/detail/$groupId/expense?expenseId=$expenseId"
    fun invite(groupId: String) = "groups/detail/$groupId/invite"
    fun editGroup(groupId: String) = "groups/detail/$groupId/edit"
}
