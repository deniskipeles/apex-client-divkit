package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.enginehost.SduiEngineUiState
import com.example.enginehost.SduiEngineViewModel
import engine.icons.nativeIconVector
import engine.notifications.NotificationCenter
import engine.notifications.NotificationGroup

/**
 * Full-page workspace notifications tab.
 *
 * The dropdown tray ([NotificationTrayOverlay]) is a glance surface; this tab is the same feed
 * with room to breathe: a summary header, per-workspace filter chips that make multi-workspace
 * traffic legible, and the grouped/expandable list filling the remaining height.
 *
 * All mutations go through [SduiEngineViewModel], so read state, mutes and expansion stay in sync
 * with the tray and the unread badges elsewhere in the app.
 */
@Composable
fun NotificationsTab(
    uiState: SduiEngineUiState,
    viewModel: SduiEngineViewModel,
    showHeader: Boolean = true
) {
    val actions = rememberNotificationTrayActions(uiState, viewModel, onDismiss = {})
    NotificationsTabContent(uiState = uiState, actions = actions, showHeader = showHeader)
}

/**
 * State-driven body of [NotificationsTab]. Rendering it directly with recording
 * [NotificationTrayActions] keeps the layout testable without a live view model.
 */
@Composable
internal fun NotificationsTabContent(
    uiState: SduiEngineUiState,
    actions: NotificationTrayActions,
    showHeader: Boolean = true
) {
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val now = remember(uiState.notifications) { engine.crypto.currentTimeMillis() }

    val visible = remember(uiState.notifications, uiState.notificationShowRead) {
        if (uiState.notificationShowRead) uiState.notifications
        else uiState.notifications.filter { it.isUnread }
    }
    val allGroups = remember(visible) { NotificationCenter.group(visible) }

    // Filter is keyed by workspace id and reset when that workspace stops having notifications,
    // so the chip row never leaves the page filtered to an empty group.
    var workspaceFilter by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(allGroups) {
        if (workspaceFilter != null && allGroups.none { it.workspaceId == workspaceFilter }) {
            workspaceFilter = null
        }
    }
    val groups = remember(allGroups, workspaceFilter) {
        workspaceFilter?.let { id -> allGroups.filter { it.workspaceId == id } } ?: allGroups
    }

    Column(modifier = Modifier.fillMaxSize()) {
        NotificationsTabHeader(
            totalUnread = uiState.totalUnreadNotifications,
            workspaceCount = allGroups.size,
            settingsOpen = uiState.notificationSettingsOpen,
            showTitle = showHeader,
            onMarkAllRead = actions.onMarkAllRead,
            onToggleSettings = actions.onToggleSettings
        )

        if (uiState.notificationSettingsOpen) {
            NotificationTabSettings(
                showRead = uiState.notificationShowRead,
                onShowReadChange = actions.onShowReadChange,
                onSimulateIncoming = actions.onSimulateIncoming
            )
        }

        if (allGroups.isNotEmpty()) {
            WorkspaceFilterRow(
                groups = allGroups,
                selectedWorkspaceId = workspaceFilter,
                totalCount = visible.size,
                onSelect = { workspaceFilter = it }
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

        when {
            uiState.notificationsLoading && groups.isEmpty() -> NotificationSkeletonList()
            groups.isEmpty() -> NotificationEmptyState()
            else -> NotificationFeed(
                groups = groups,
                expandedGroups = uiState.expandedNotificationGroups,
                now = now,
                compact = compact,
                actions = actions,
                modifier = Modifier
                    .weight(1f)
                    .testTag("notifications_tab_list")
            )
        }
    }
}

@Composable
private fun NotificationsTabHeader(
    totalUnread: Int,
    workspaceCount: Int,
    settingsOpen: Boolean,
    showTitle: Boolean,
    onMarkAllRead: () -> Unit,
    onToggleSettings: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = colorScheme.primary.copy(alpha = 0.14f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (showTitle) {
                Text(
                    text = "Notifications",
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    color = colorScheme.onSurface
                )
            }
            Text(
                text = when {
                    totalUnread > 0 && workspaceCount > 0 ->
                        "$totalUnread unread across $workspaceCount ${if (workspaceCount == 1) "workspace" else "workspaces"}"
                    totalUnread > 0 -> "$totalUnread unread"
                    else -> "You're all caught up"
                },
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = onMarkAllRead,
            enabled = totalUnread > 0,
            modifier = Modifier.testTag("notifications_tab_mark_all_read")
        ) {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Mark all notifications read",
                tint = if (totalUnread > 0) colorScheme.onSurface else colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = onToggleSettings,
            modifier = Modifier.testTag("notifications_tab_settings_button")
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Notification settings",
                tint = if (settingsOpen) colorScheme.primary else colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun NotificationTabSettings(
    showRead: Boolean,
    onShowReadChange: (Boolean) -> Unit,
    onSimulateIncoming: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        color = colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("notifications_tab_settings")
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Show read notifications",
                    fontSize = 13.sp,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = showRead,
                    onCheckedChange = onShowReadChange,
                    modifier = Modifier.testTag("notifications_tab_show_read_switch")
                )
            }
            TextButton(
                onClick = onSimulateIncoming,
                modifier = Modifier.testTag("notifications_tab_simulate_incoming")
            ) {
                Icon(Icons.Default.AddAlert, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Simulate incoming notification", fontSize = 13.sp)
            }
        }
    }
}

/** Horizontal workspace filter chips: "All" plus one per workspace carrying unread counts. */
@Composable
private fun WorkspaceFilterRow(
    groups: List<NotificationGroup>,
    selectedWorkspaceId: String?,
    totalCount: Int,
    onSelect: (String?) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("notifications_tab_filters"),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "filter_all") {
            WorkspaceFilterChip(
                label = "All",
                count = totalCount,
                selected = selectedWorkspaceId == null,
                icon = null,
                testTag = "notifications_tab_filter_all",
                onClick = { onSelect(null) }
            )
        }
        items(groups, key = { "filter_${it.workspaceId}" }) { group ->
            WorkspaceFilterChip(
                label = group.workspaceName,
                count = group.unreadCount,
                selected = selectedWorkspaceId == group.workspaceId,
                icon = group.workspaceIcon,
                testTag = "notifications_tab_filter_${group.workspaceId}",
                onClick = { onSelect(group.workspaceId) }
            )
        }
    }
}

@Composable
private fun WorkspaceFilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    icon: String?,
    testTag: String,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val vector = remember(icon) { icon?.let { nativeIconVector(it) } }
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (selected) colorScheme.primary else colorScheme.outline.copy(alpha = 0.6f)
        ),
        leadingIcon = {
            if (vector != null) {
                Icon(
                    imageVector = vector,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (count > 0) {
                    Spacer(Modifier.width(6.dp))
                    Surface(color = colorScheme.primary, shape = CircleShape) {
                        Text(
                            text = "$count",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    )
}
