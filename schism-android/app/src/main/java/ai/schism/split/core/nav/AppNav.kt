package ai.schism.split.core.nav

import ai.schism.split.dashboard.group.GroupDashboardScreen
import ai.schism.split.dashboard.personal.PersonalDashboardScreen
import ai.schism.split.finance.SpendingScreen
import ai.schism.split.groups.create.CreateGroupScreen
import ai.schism.split.expense.edit.ExpenseEditScreen
import ai.schism.split.groups.detail.GroupDetailScreen
import ai.schism.split.groups.edit.EditGroupScreen
import ai.schism.split.groups.join.JoinGroupScreen
import ai.schism.split.groups.join.OpenGroupScreen
import ai.schism.split.groups.list.GroupsListScreen
import ai.schism.split.groups.qr.InviteQrScreen
import ai.schism.split.settings.SettingsScreen
import ai.schism.split.sms.inbox.InboxScreen
import ai.schism.split.sms.itemized.ItemizedSplitScreen
import ai.schism.split.sms.split.PushToSplitScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination(Routes.GROUPS, "Groups", Icons.Filled.Groups),
    Destination(Routes.INBOX, "Inbox", Icons.Filled.Inbox),
    Destination(Routes.DASHBOARD, "Dashboard", Icons.AutoMirrored.Filled.ReceiptLong),
    Destination(Routes.SPENDING, "Spending", Icons.Filled.PieChart),
    Destination(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // The bottom bar belongs to the three top-level tabs only, not detail/create/edit screens.
    val showBottomBar = currentRoute in destinations.map { it.route }

    Scaffold(
        // Each screen owns its own Scaffold + top bar, which consumes the status-bar inset. Zero the
        // outer insets so the top spacing isn't counted twice; the returned padding is just the nav bar.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    destinations.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label, maxLines = 1, softWrap = false) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.GROUPS,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.GROUPS) {
                GroupsListScreen(
                    onOpenGroup = { id -> navController.navigate(Routes.groupDetail(id)) },
                    onCreateGroup = { navController.navigate(Routes.CREATE_GROUP) },
                    onJoinGroup = { navController.navigate(Routes.JOIN_GROUP) },
                    onScanBill = { navController.navigate(Routes.RECEIPT_ITEMIZED) },
                )
            }
            composable(Routes.CREATE_GROUP) {
                CreateGroupScreen(
                    onBack = { navController.popBackStack() },
                    onCreated = { navController.popBackStack() },
                )
            }
            composable(Routes.JOIN_GROUP) {
                JoinGroupScreen(
                    onBack = { navController.popBackStack() },
                    onJoined = { navController.popBackStack() },
                )
            }
            composable(
                Routes.OPEN_GROUP,
                arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
                deepLinks = listOf(navDeepLink { uriPattern = "schism://group/{groupId}" }),
            ) { entry ->
                val gid = entry.arguments?.getString("groupId").orEmpty()
                OpenGroupScreen(
                    groupId = gid,
                    onOpened = { id ->
                        navController.navigate(Routes.groupDetail(id)) {
                            popUpTo(Routes.OPEN_GROUP) { inclusive = true }
                        }
                    },
                    onFailed = {
                        navController.navigate(Routes.GROUPS) {
                            popUpTo(Routes.OPEN_GROUP) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                Routes.GROUP_DETAIL,
                arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
            ) {
                GroupDetailScreen(
                    onBack = { navController.popBackStack() },
                    onAddExpense = { id -> navController.navigate(Routes.addExpense(id)) },
                    onEditExpense = { id, expenseId -> navController.navigate(Routes.editExpense(id, expenseId)) },
                    onOpenDashboard = { id -> navController.navigate(Routes.groupDashboard(id)) },
                    onInvite = { id -> navController.navigate(Routes.invite(id)) },
                    onEditGroup = { id -> navController.navigate(Routes.editGroup(id)) },
                )
            }
            composable(
                Routes.GROUP_DASHBOARD,
                arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
            ) {
                GroupDashboardScreen(onBack = { navController.popBackStack() })
            }
            composable(
                Routes.INVITE,
                arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
            ) {
                InviteQrScreen(onBack = { navController.popBackStack() })
            }
            composable(
                Routes.EDIT_GROUP,
                arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
            ) {
                EditGroupScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }
            composable(
                Routes.EXPENSE_EDIT,
                arguments = listOf(
                    navArgument("groupId") { type = NavType.StringType },
                    navArgument("expenseId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                ExpenseEditScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }
            composable(Routes.INBOX) {
                InboxScreen(
                    onSplit = { txnId -> navController.navigate(Routes.pushSplit(txnId)) },
                    onScanItemized = { navController.navigate(Routes.RECEIPT_ITEMIZED) },
                )
            }
            composable(Routes.RECEIPT_ITEMIZED) {
                ItemizedSplitScreen(
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() },
                )
            }
            composable(
                Routes.PUSH_SPLIT,
                arguments = listOf(navArgument("transactionId") { type = NavType.StringType }),
            ) {
                PushToSplitScreen(
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Routes.DASHBOARD) { PersonalDashboardScreen() }
            composable(Routes.SPENDING) { SpendingScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
        }
    }
}
