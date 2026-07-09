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
import ai.schism.split.sms.itemized.claim.ClaimScreen
import ai.schism.split.sms.split.PushToSplitScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

    val connectivity: ai.schism.split.core.net.ConnectivityViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val online by connectivity.isOnline.collectAsState()
    val pending by connectivity.pendingSync.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(online, pending) {
        if (online && pending > 0) ai.schism.split.expense.data.OutboxSyncWorker.enqueue(ctx)
    }

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
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(padding).windowInsetsPadding(WindowInsets.statusBars),
        ) {
            CloudStatusBanner(online = online, pending = pending)
            val updateVm: ai.schism.split.core.update.UpdateBannerViewModel =
                androidx.hilt.navigation.compose.hiltViewModel()
            val updateAvailable by updateVm.available.collectAsState()
            updateAvailable?.let { release ->
                UpdateAvailableBanner(
                    versionName = release.versionName,
                    onDownload = {
                        val url = release.apkUrl ?: release.releaseUrl
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    onDismiss = updateVm::dismiss,
                )
            }
            NavHost(
                navController = navController,
                startDestination = Routes.GROUPS,
                modifier = Modifier.weight(1f),
            ) {
            composable(Routes.GROUPS) {
                GroupsListScreen(
                    onOpenGroup = { id -> navController.navigate(Routes.groupDetail(id)) },
                    onCreateGroup = { navController.navigate(Routes.CREATE_GROUP) },
                    onJoinGroup = { navController.navigate(Routes.JOIN_GROUP) },
                    onScanBill = { navController.navigate(Routes.receiptItemized()) },
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
                    navArgument("transactionId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val groupId = entry.arguments?.getString("groupId")
                ExpenseEditScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                    onScanItemized = { navController.navigate(Routes.receiptItemized(groupId)) },
                )
            }
            composable(Routes.INBOX) {
                InboxScreen(
                    onSplit = { txnId -> navController.navigate(Routes.pushSplit(txnId)) },
                    onScanItemized = { navController.navigate(Routes.receiptItemized()) },
                )
            }
            composable(
                Routes.RECEIPT_ITEMIZED,
                arguments = listOf(
                    navArgument("groupId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val groupId = entry.arguments?.getString("groupId")
                ItemizedSplitScreen(
                    onBack = { navController.popBackStack() },
                    onDone = {
                        if (groupId != null) {
                            navController.popBackStack(Routes.groupDetail(groupId), inclusive = false)
                        } else {
                            navController.popBackStack()
                        }
                    },
                    onClaimSessionCreated = { sid -> navController.navigate(Routes.claim(sid)) },
                )
            }
            composable(
                Routes.PUSH_SPLIT,
                arguments = listOf(navArgument("transactionId") { type = NavType.StringType }),
            ) { entry ->
                val txnId = entry.arguments?.getString("transactionId").orEmpty()
                PushToSplitScreen(
                    onBack = { navController.popBackStack() },
                    onContinue = { groupId ->
                        navController.navigate(Routes.splitTransaction(groupId, txnId)) {
                            popUpTo(Routes.PUSH_SPLIT) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                Routes.CLAIM,
                arguments = listOf(navArgument("sid") { type = NavType.StringType }),
                deepLinks = listOf(navDeepLink { uriPattern = "schism://claim/{sid}" }),
            ) {
                ClaimScreen(
                    onBack = { navController.popBackStack() },
                    onFinalized = { navController.popBackStack() },
                )
            }
            composable(Routes.DASHBOARD) { PersonalDashboardScreen() }
            composable(Routes.SPENDING) { SpendingScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            }
        }
    }
}

/**
 * A slim cloud connection status strip: cloud-off when offline, a syncing cloud when there are
 * queued writes to push, hidden when everything is up to date and online.
 */
@Composable
private fun CloudStatusBanner(online: Boolean, pending: Int) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val offline = !online
    val syncing = online && pending > 0
    androidx.compose.animation.AnimatedVisibility(visible = offline || syncing) {
        val bg = if (offline) scheme.tertiaryContainer else scheme.secondaryContainer
        val fg = if (offline) scheme.onTertiaryContainer else scheme.onSecondaryContainer
        val icon = if (offline) androidx.compose.material.icons.Icons.Filled.CloudOff
        else androidx.compose.material.icons.Icons.Filled.CloudUpload
        val text = when {
            offline && pending > 0 -> "Offline — $pending change(s) saved, will sync when you're back."
            offline -> "You're offline — changes are saved and will sync when you're back."
            else -> "Syncing $pending change(s)…"
        }
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Text(text, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = fg)
        }
    }
}

/** A slim "Update available" strip shown at launch when GitHub has a newer release than this build. */
@Composable
private fun UpdateAvailableBanner(versionName: String, onDownload: () -> Unit, onDismiss: () -> Unit) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.primaryContainer)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            androidx.compose.material.icons.Icons.Filled.CloudUpload,
            contentDescription = null,
            tint = scheme.onPrimaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "Update available — v$versionName",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = scheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.TextButton(onClick = onDownload) { Text("Download") }
        IconButton(onClick = onDismiss) {
            Icon(
                androidx.compose.material.icons.Icons.Filled.Close,
                contentDescription = "Dismiss",
                tint = scheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
