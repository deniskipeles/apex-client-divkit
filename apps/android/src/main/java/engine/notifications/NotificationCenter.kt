package engine.notifications

/**
 * Source/kind of a workspace notification. Each type maps to a bundled native icon so the
 * tray can render a left-hand type glyph without shipping assets.
 */
enum class NotificationType(val label: String, val icon: String) {
    MENTION("Mention", "chat"),
    COMMENT("Comment", "comment"),
    TASK("Task", "task_alt"),
    ASSIGNMENT("Assignment", "assignment"),
    FILE("File", "description"),
    SYSTEM("System", "info");

    companion object {
        fun fromWire(raw: String?): NotificationType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
    }
}

/** Deep-link destination for a notification's View action. */
data class NotificationTarget(
    val type: String,
    val id: String,
    val url: String? = null
)

/** Which per-notification actions are offered. */
data class NotificationActions(
    val canView: Boolean = true,
    val canMarkRead: Boolean = true,
    val canMute: Boolean = true
)

/**
 * One workspace notification. [workspaceIcon] is a bundled icon reference (a canonical
 * `icons://native/<name>` name such as `storefront`) resolved through
 * [engine.icons.NativeIconCatalog] so the tray can show the workspace glyph.
 */
data class WorkspaceNotification(
    val id: String,
    val workspaceId: String,
    val workspaceName: String,
    val workspaceIcon: String,
    val title: String,
    val message: String? = null,
    val createdAt: Long,
    val type: NotificationType = NotificationType.SYSTEM,
    val isRead: Boolean = false,
    val isMuted: Boolean = false,
    val isExpanded: Boolean = false,
    val target: NotificationTarget? = null,
    val actions: NotificationActions = NotificationActions()
) {
    /** True when this notification should raise an unread indicator. */
    val isUnread: Boolean get() = !isRead
}

/**
 * A workspace's notifications collapsed into one group, mirroring the app-grouped layout of
 * the reference tray (Reddit/Google style). [latestAt] orders groups newest-first.
 */
data class NotificationGroup(
    val workspaceId: String,
    val workspaceName: String,
    val workspaceIcon: String,
    val notifications: List<WorkspaceNotification>,
    val unreadCount: Int,
    val isMuted: Boolean,
    val latestAt: Long
) {
    /** Newest notification, used for the collapsed preview line. */
    val latest: WorkspaceNotification? get() = notifications.firstOrNull()

    /** One-line preview shown while collapsed. */
    val preview: String? get() = latest?.let { it.message ?: it.title }

    val size: Int get() = notifications.size
}

/**
 * Pure, Android/Compose-free notification store operations. Every function returns a new list
 * (no mutation) so the tray state is a plain immutable snapshot that is trivially unit-testable
 * on the JVM and cheap to diff inside Compose.
 */
object NotificationCenter {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY

    /** Total unread across all notifications. */
    fun unreadCount(notifications: List<WorkspaceNotification>): Int =
        notifications.count { it.isUnread }

    /** Unread count scoped to one workspace. */
    fun unreadCount(notifications: List<WorkspaceNotification>, workspaceId: String): Int =
        notifications.count { it.workspaceId == workspaceId && it.isUnread }

    /**
     * Groups notifications by workspace, newest group first, newest notification first within
     * a group. Read notifications remain visible, matching the reference tray.
     */
    fun group(notifications: List<WorkspaceNotification>): List<NotificationGroup> =
        notifications
            .groupBy { it.workspaceId }
            .map { (workspaceId, items) ->
                val ordered = items.sortedByDescending { it.createdAt }
                val head = ordered.first()
                NotificationGroup(
                    workspaceId = workspaceId,
                    workspaceName = head.workspaceName,
                    workspaceIcon = head.workspaceIcon,
                    notifications = ordered,
                    unreadCount = ordered.count { it.isUnread },
                    isMuted = ordered.all { it.isMuted },
                    latestAt = head.createdAt
                )
            }
            .sortedByDescending { it.latestAt }

    /** Marks one notification read. */
    fun markRead(notifications: List<WorkspaceNotification>, id: String): List<WorkspaceNotification> =
        notifications.map { if (it.id == id) it.copy(isRead = true) else it }

    /** Marks every notification read. */
    fun markAllRead(notifications: List<WorkspaceNotification>): List<WorkspaceNotification> =
        notifications.map { it.copy(isRead = true) }

    /** Marks every notification in one workspace read. */
    fun markWorkspaceRead(
        notifications: List<WorkspaceNotification>,
        workspaceId: String
    ): List<WorkspaceNotification> =
        notifications.map { if (it.workspaceId == workspaceId) it.copy(isRead = true) else it }

    /**
     * Mutes the selected notification and everything else in the same workspace, reflecting the
     * product rule that muting suppresses future notifications for the notification's workspace.
     * Muted notifications are also marked read so their unread dot clears immediately.
     */
    fun mute(notifications: List<WorkspaceNotification>, id: String): List<WorkspaceNotification> {
        val workspaceId = notifications.firstOrNull { it.id == id }?.workspaceId ?: return notifications
        return muteWorkspace(notifications, workspaceId)
    }

    /** Mutes an entire workspace group. */
    fun muteWorkspace(
        notifications: List<WorkspaceNotification>,
        workspaceId: String
    ): List<WorkspaceNotification> =
        notifications.map {
            if (it.workspaceId == workspaceId) it.copy(isMuted = true, isRead = true) else it
        }

    /** Un-mutes a workspace group so it resumes surfacing notifications. */
    fun unmuteWorkspace(
        notifications: List<WorkspaceNotification>,
        workspaceId: String
    ): List<WorkspaceNotification> =
        notifications.map {
            if (it.workspaceId == workspaceId) it.copy(isMuted = false) else it
        }

    /** Removes one notification from the tray. */
    fun dismiss(notifications: List<WorkspaceNotification>, id: String): List<WorkspaceNotification> =
        notifications.filterNot { it.id == id }

    /** Removes every notification belonging to a workspace. */
    fun dismissWorkspace(
        notifications: List<WorkspaceNotification>,
        workspaceId: String
    ): List<WorkspaceNotification> =
        notifications.filterNot { it.workspaceId == workspaceId }

    /** Flips one notification's expanded state. */
    fun toggleExpanded(notifications: List<WorkspaceNotification>, id: String): List<WorkspaceNotification> =
        notifications.map { if (it.id == id) it.copy(isExpanded = !it.isExpanded) else it }

    /**
     * Prepends [notification] unless its workspace is muted, in which case it is dropped so a
     * muted workspace stops surfacing new items (the muted-indicator rule).
     */
    fun add(
        notifications: List<WorkspaceNotification>,
        notification: WorkspaceNotification,
        mutedWorkspaces: Set<String> = emptySet()
    ): List<WorkspaceNotification> =
        if (notification.workspaceId in mutedWorkspaces) notifications
        else listOf(notification) + notifications

    /**
     * Compact relative age ("now", "8m", "3h", "2d", "1w"). [nowMillis] is injected so the
     * formatting is deterministic under test.
     */
    fun relativeTime(createdAt: Long, nowMillis: Long): String {
        val delta = (nowMillis - createdAt).coerceAtLeast(0L)
        return when {
            delta < MINUTE -> "now"
            delta < HOUR -> "${delta / MINUTE}m"
            delta < DAY -> "${delta / HOUR}h"
            delta < WEEK -> "${delta / DAY}d"
            else -> "${delta / WEEK}w"
        }
    }
}
