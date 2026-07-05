package ai.schism.split.core.nav

import ai.schism.split.dashboard.group.GroupDashboardScreen
import ai.schism.split.dashboard.personal.PersonalDashboardScreen
import ai.schism.split.groups.create.CreateGroupScreen
import ai.schism.split.expense.edit.ExpenseEditScreen
import ai.schism.split.groups.detail.GroupDetailScreen
import ai.schism.split.groups.join.JoinGroupScreen
import ai.schism.split.groups.list.GroupsListScreen
import ai.schism.split.settings.SettingsScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Groups
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
    Destination(Routes.DASHBOARD, "Dashboard", Icons.AutoMirrored.Filled.ReceiptLong),
    Destination(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
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
                        label = { Text(dest.label) },
                    )
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
                Routes.GROUP_DETAIL,
                arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
            ) {
                GroupDetailScreen(
                    onBack = { navController.popBackStack() },
                    onAddExpense = { id -> navController.navigate(Routes.addExpense(id)) },
                    onEditExpense = { id, expenseId -> navController.navigate(Routes.editExpense(id, expenseId)) },
                    onOpenDashboard = { id -> navController.navigate(Routes.groupDashboard(id)) },
                )
            }
            composable(
                Routes.GROUP_DASHBOARD,
                arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
            ) {
                GroupDashboardScreen(onBack = { navController.popBackStack() })
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
            composable(Routes.DASHBOARD) { PersonalDashboardScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
        }
    }
}
