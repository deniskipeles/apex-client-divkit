package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.enginehost.SduiEngineUiState
import com.example.enginehost.SduiEngineViewModel
import engine.icons.nativeIconVector
import engine.notifications.NotificationCenter
import engine.notifications.NotificationGroup
import engine.notifications.WorkspaceNotification

/**
 * Callbacks for the notifications tray. Grouping the actions into one object keeps
 * [NotificationTrayPanel] a dumb, state-driven composable that can be rendered in tests with
 * recording lambdas instead of a live [SduiEngineViewModel].
 */
data class NotificationTrayActions(
    val onMarkAllRead: () -> Unit = {},
    val onToggleSettings: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onShowReadChange: (Boolean) -> Unit = {},
    val onSimulateIncoming: () -> Unit = {},
    val onToggleGroup: (String) -> Unit = {},
    val onOpenWorkspace: (String) -> Unit = {},
    val onMarkWorkspaceRead: (NotificationGroup) -> Unit = {},
    val onMuteWorkspaceToggle: (NotificationGroup) -> Unit = {},
    val onClearWorkspace: (NotificationGroup) -> Unit = {},
    val onView: (WorkspaceNotification) -> Unit = {},
    val onMarkRead: (WorkspaceNotification) -> Unit = {},
    val onMute: (WorkspaceNotification) -> Unit = {},
    val onToggleExpand: (WorkspaceNotification) -> Unit = {},
    val onDismissNotification: (WorkspaceNotification) -> Unit = {}
)

/**
 * Wires [NotificationTrayActions] to [SduiEngineViewModel] so both the dropdown tray and the
 * full-page Notifications tab drive the same state. [onDismiss] runs after an action that
 * navigates away (open workspace / view notification).
 */
@Composable
internal fun rememberNotificationTrayActions(
    uiState: SduiEngineUiState,
    viewModel: SduiEngineViewModel,
    onDismiss: () -> Unit
): NotificationTrayActions = remember(viewModel, uiState) {
    NotificationTrayActions(
        onMarkAllRead = { viewModel.markAllNotificationsRead() },
        onToggleSettings = { viewModel.setNotificationSettingsOpen(!uiState.notificationSettingsOpen) },
        onClose = onDismiss,
        onShowReadChange = { viewModel.setNotificationShowRead(it) },
        onSimulateIncoming = { viewModel.simulateIncomingNotification() },
        onToggleGroup = { viewModel.toggleNotificationGroup(it) },
        onOpenWorkspace = { workspaceId ->
            uiState.workspaces.firstOrNull { it.tenantId == workspaceId }
                ?.let { viewModel.openTenantWorkspace(it) }
            onDismiss()
        },
        onMarkWorkspaceRead = { group ->
            viewModel.markWorkspaceNotificationsRead(group.workspaceId, group.workspaceName)
        },
        onMuteWorkspaceToggle = { group ->
            if (group.isMuted) {
                viewModel.unmuteNotificationWorkspace(group.workspaceId, group.workspaceName)
            } else {
                viewModel.muteNotificationWorkspace(group.workspaceId, group.workspaceName)
            }
        },
        onClearWorkspace = { group ->
            viewModel.dismissNotificationWorkspace(group.workspaceId, group.workspaceName)
        },
        onView = { notification ->
            viewModel.openNotificationTarget(notification)
            onDismiss()
        },
        onMarkRead = { viewModel.markNotificationRead(it.id) },
        onMute = { viewModel.muteNotification(it.id) },
        onToggleExpand = { viewModel.toggleNotificationExpanded(it.id) },
        onDismissNotification = { viewModel.dismissNotification(it.id) }
    )
}

/**
 * Advanced workspace notifications tray.
 *
 * Groups notifications by workspace (Reddit-style), expands each group into individual rows whose
 * workspace glyph is pinned to the far right (Google-style), and offers View / Mark read / Mute per
 * notification plus mark-all-read, mute, clear and open at the group level.
 *
 * The overlay renders a dismissible scrim plus an anchored panel; the panel is a fixed-width
 * dropdown on wide layouts and a near-full-width sheet on narrow ones. State is a plain immutable
 * snapshot owned by [SduiEngineViewModel], so expansion survives recomposition and actions update
 * unread counts immediately.
 */
@Composable
fun NotificationTrayOverlay(
    uiState: SduiEngineUiState,
    viewModel: SduiEngineViewModel,
    onDismiss: () -> Unit
) {
    val actions = rememberNotificationTrayActions(uiState, viewModel, onDismiss)

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim: tapping outside the tray closes it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Dismiss notifications",
                    onClick = onDismiss
                )
                .testTag("notification_tray_scrim")
        )
        NotificationTrayPanel(
            uiState = uiState,
            actions = actions,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
    }
}

@Composable
internal fun NotificationTrayPanel(
    uiState: SduiEngineUiState,
    actions: NotificationTrayActions,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val configuration = LocalConfiguration.current
    val compact = configuration.screenWidthDp < 600
    val now = remember(uiState.notifications) { engine.crypto.currentTimeMillis() }

    val visible = remember(uiState.notifications, uiState.notificationShowRead) {
        if (uiState.notificationShowRead) uiState.notifications
        else uiState.notifications.filter { it.isUnread }
    }
    val groups = remember(visible) { NotificationCenter.group(visible) }

    Surface(
        color = colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, colorScheme.outline),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        modifier = modifier
            .then(if (compact) Modifier.fillMaxWidth() else Modifier.width(440.dp))
            .heightIn(max = 640.dp)
            .testTag("notification_tray_panel")
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TrayHeader(
                totalUnread = uiState.totalUnreadNotifications,
                showRead = uiState.notificationShowRead,
                settingsOpen = uiState.notificationSettingsOpen,
                onMarkAllRead = actions.onMarkAllRead,
                onToggleSettings = actions.onToggleSettings,
                onClose = actions.onClose
            )
            HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f))

            if (uiState.notificationSettingsOpen) {
                TraySettingsBar(
                    showRead = uiState.notificationShowRead,
                    onShowReadChange = actions.onShowReadChange,
                    onSimulateIncoming = actions.onSimulateIncoming
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f))
            }

            when {
                uiState.notificationsLoading && groups.isEmpty() -> NotificationSkeletonList()
                groups.isEmpty() -> NotificationEmptyState()
                else -> NotificationFeed(
                    groups = groups,
                    expandedGroups = uiState.expandedNotificationGroups,
                    now = now,
                    compact = compact,
                    actions = actions,
                    modifier = Modifier.testTag("notification_tray_list")
                )
            }
        }
    }
}

/**
 * Grouped, expandable notification list shared by the dropdown tray and the Notifications tab.
 *
 * [modifier] is applied to the scrolling container, so the tab can let the list fill the page
 * while the tray caps its height.
 */
@Composable
internal fun NotificationFeed(
    groups: List<NotificationGroup>,
    expandedGroups: Set<String>,
    now: Long,
    compact: Boolean,
    actions: NotificationTrayActions,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        groups.forEachIndexed { index, group ->
            val expanded = group.workspaceId in expandedGroups
            item(key = "group_${group.workspaceId}") {
                Column {
                    WorkspaceGroupHeader(
                        group = group,
                        expanded = expanded,
                        now = now,
                        onToggle = { actions.onToggleGroup(group.workspaceId) },
                        onOpenWorkspace = { actions.onOpenWorkspace(group.workspaceId) },
                        onMarkAllRead = { actions.onMarkWorkspaceRead(group) },
                        onMuteToggle = { actions.onMuteWorkspaceToggle(group) },
                        onClear = { actions.onClearWorkspace(group) }
                    )
                    if (index != groups.lastIndex) {
                        HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.35f))
                    }
                }
            }

            if (expanded) {
                items(group.notifications, key = { "ntf_${it.id}" }) { notification ->
                    NotificationRow(
                        notification = notification,
                        now = now,
                        compact = compact,
                        onView = { actions.onView(notification) },
                        onMarkRead = { actions.onMarkRead(notification) },
                        onMute = { actions.onMute(notification) },
                        onToggleExpand = { actions.onToggleExpand(notification) },
                        onDismiss = { actions.onDismissNotification(notification) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrayHeader(
    totalUnread: Int,
    showRead: Boolean,
    settingsOpen: Boolean,
    onMarkAllRead: () -> Unit,
    onToggleSettings: () -> Unit,
    onClose: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Notifications,
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Notifications",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = colorScheme.onSurface
            )
            Text(
                text = if (totalUnread > 0) "$totalUnread unread" else "You're all caught up",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant
            )
        }

        // Mark all read
        IconButton(
            onClick = onMarkAllRead,
            enabled = totalUnread > 0,
            modifier = Modifier.testTag("notification_mark_all_read")
        ) {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Mark all notifications read",
                tint = if (totalUnread > 0) colorScheme.onSurface else colorScheme.onSurfaceVariant
            )
        }

        // Settings toggle
        IconButton(
            onClick = onToggleSettings,
            modifier = Modifier.testTag("notification_settings_button")
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Notification settings",
                tint = if (settingsOpen) colorScheme.primary else colorScheme.onSurface
            )
        }

        // Close
        IconButton(
            onClick = onClose,
            modifier = Modifier.testTag("notification_close_button")
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close notifications",
                tint = colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TraySettingsBar(
    showRead: Boolean,
    onShowReadChange: (Boolean) -> Unit,
    onSimulateIncoming: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
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
                modifier = Modifier.testTag("notification_show_read_switch")
            )
        }
        TextButton(
            onClick = onSimulateIncoming,
            modifier = Modifier.testTag("notification_simulate_incoming")
        ) {
            Icon(Icons.Default.AddAlert, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Simulate incoming notification", fontSize = 13.sp)
        }
    }
}

@Composable
private fun WorkspaceGroupHeader(
    group: NotificationGroup,
    expanded: Boolean,
    now: Long,
    onToggle: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onMarkAllRead: () -> Unit,
    onMuteToggle: () -> Unit,
    onClear: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val headerColor = when {
        focused -> colorScheme.primary.copy(alpha = 0.14f)
        hovered -> colorScheme.onSurface.copy(alpha = 0.06f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(headerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = if (expanded) "Collapse ${group.workspaceName}" else "Expand ${group.workspaceName}",
                onClick = onToggle
            )
            .focusable(interactionSource = interactionSource)
            .semantics(mergeDescendants = true) {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)
            .testTag("notification_group_${group.workspaceId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WorkspaceGlyph(
            icon = group.workspaceIcon,
            name = group.workspaceName,
            size = 34.dp,
            testTag = "notification_group_glyph_${group.workspaceId}"
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.workspaceName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (group.isMuted) {
                    Spacer(Modifier.width(6.dp))
                    MutedBadge()
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (group.unreadCount > 0) {
                    UnreadCountBadge(count = group.unreadCount)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = group.preview ?: "No notifications",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.width(6.dp))

        Text(
            text = NotificationCenter.relativeTime(group.latestAt, now),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariant
        )

        // Group overflow menu (mark all read / mute / open / clear).
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.testTag("notification_group_menu_${group.workspaceId}")
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "${group.workspaceName} group actions",
                    tint = colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Open workspace") },
                    leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                    onClick = { menuOpen = false; onOpenWorkspace() },
                    modifier = Modifier.testTag("notification_group_open_${group.workspaceId}")
                )
                DropdownMenuItem(
                    text = { Text("Mark all read") },
                    leadingIcon = { Icon(Icons.Default.DoneAll, contentDescription = null) },
                    onClick = { menuOpen = false; onMarkAllRead() },
                    modifier = Modifier.testTag("notification_group_mark_read_${group.workspaceId}")
                )
                DropdownMenuItem(
                    text = { Text(if (group.isMuted) "Unmute workspace" else "Mute workspace") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (group.isMuted) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = null
                        )
                    },
                    onClick = { menuOpen = false; onMuteToggle() },
                    modifier = Modifier.testTag("notification_group_mute_${group.workspaceId}")
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Clear notifications") },
                    leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                    onClick = { menuOpen = false; onClear() },
                    modifier = Modifier.testTag("notification_group_clear_${group.workspaceId}")
                )
            }
        }

        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun NotificationRow(
    notification: WorkspaceNotification,
    now: Long,
    compact: Boolean,
    onView: () -> Unit,
    onMarkRead: () -> Unit,
    onMute: () -> Unit,
    onToggleExpand: () -> Unit,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val showActions = hovered || focused || notification.isExpanded || compact

    val background = when {
        focused -> colorScheme.primary.copy(alpha = 0.12f)
        hovered -> colorScheme.onSurface.copy(alpha = 0.05f)
        notification.isUnread -> colorScheme.primary.copy(alpha = 0.06f)
        else -> Color.Transparent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 8.dp)
            .testTag("notification_row_${notification.id}")
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // Left: type glyph + unread dot
            Box(modifier = Modifier.width(22.dp), contentAlignment = Alignment.TopStart) {
                if (notification.isUnread) {
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colorScheme.primary)
                            .semantics { contentDescription = "Unread" }
                    )
                } else {
                    Icon(
                        imageVector = notificationIcon(notification),
                        contentDescription = notification.type.label,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // Middle: title + message + meta. weight(1f) keeps the glyph pinned right even
            // when the message wraps to many lines.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClickLabel = "Open notification",
                        onClick = onToggleExpand
                    )
                    .focusable(interactionSource = interactionSource)
                    .semantics(mergeDescendants = true) {
                        stateDescription = if (notification.isUnread) "Unread" else "Read"
                    }
            ) {
                Text(
                    text = notification.title,
                    fontWeight = if (notification.isUnread) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp,
                    color = colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (notification.message != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = notification.message,
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = if (notification.isExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = NotificationCenter.relativeTime(notification.createdAt, now),
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(10.dp))

            // Far right: workspace glyph, vertically centered against the (possibly wrapped) text.
            WorkspaceGlyph(
                icon = notification.workspaceIcon,
                name = notification.workspaceName,
                size = 34.dp,
                testTag = "notification_row_glyph_${notification.id}"
            )
        }

        if (showActions) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (notification.actions.canView) {
                    NotificationAction(
                        label = "View",
                        icon = Icons.Default.OpenInNew,
                        onClick = onView,
                        testTag = "notification_view_${notification.id}"
                    )
                }
                if (notification.actions.canMarkRead && notification.isUnread) {
                    NotificationAction(
                        label = "Mark read",
                        icon = Icons.Default.MarkEmailRead,
                        onClick = onMarkRead,
                        testTag = "notification_mark_read_${notification.id}"
                    )
                }
                if (notification.actions.canMute && !notification.isMuted) {
                    NotificationAction(
                        label = "Mute",
                        icon = Icons.Default.VolumeOff,
                        onClick = onMute,
                        testTag = "notification_mute_${notification.id}"
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("notification_dismiss_${notification.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss notification",
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.testTag(testTag)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Workspace glyph shown on the far right of a notification row and in the group header. */
@Composable
private fun WorkspaceGlyph(icon: String, name: String, size: Dp, testTag: String) {
    val colorScheme = MaterialTheme.colorScheme
    val vector = remember(icon) { nativeIconVector(icon) }
    Surface(
        color = colorScheme.primary.copy(alpha = 0.14f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .size(size)
            .testTag(testTag)
            .semantics { contentDescription = "$name workspace" }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = vector ?: Icons.Default.Storefront,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(size * 0.55f)
            )
        }
    }
}

@Composable
private fun UnreadCountBadge(count: Int) {
    Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
        Text(
            text = "$count",
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun MutedBadge() {
    val colorScheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.VolumeOff,
            contentDescription = "Muted",
            tint = colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text("Muted", fontSize = 10.sp, color = colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun NotificationEmptyState() {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp)
            .testTag("notification_empty_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.NotificationsOff,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp)
        )
        Text(
            text = "You're all caught up.",
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = colorScheme.onSurface
        )
        Text(
            text = "No new workspace notifications.",
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun NotificationSkeletonList() {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("notification_skeleton"),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        repeat(4) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colorScheme.onSurface.copy(alpha = 0.10f))
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colorScheme.onSurface.copy(alpha = 0.10f))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(colorScheme.onSurface.copy(alpha = 0.07f))
                    )
                }
            }
        }
    }
}

/** Type glyph for a notification row, resolved from the bundled native icon catalog. */
private fun notificationIcon(notification: WorkspaceNotification): ImageVector =
    nativeIconVector(notification.type.icon) ?: Icons.Default.Notifications
