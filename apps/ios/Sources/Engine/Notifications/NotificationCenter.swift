import Foundation

/// Source/kind of a workspace notification. Each type maps to a bundled native icon so the
/// tray can render a left-hand type glyph without shipping assets. Mirrors
/// `engine.notifications.NotificationType`.
public enum NotificationType: String, CaseIterable, Sendable {
    case mention = "MENTION"
    case comment = "COMMENT"
    case task = "TASK"
    case assignment = "ASSIGNMENT"
    case file = "FILE"
    case system = "SYSTEM"

    public var label: String {
        switch self {
        case .mention: return "Mention"
        case .comment: return "Comment"
        case .task: return "Task"
        case .assignment: return "Assignment"
        case .file: return "File"
        case .system: return "System"
        }
    }

    /// Bundled icon name resolved through `NativeIconCatalog`.
    public var icon: String {
        switch self {
        case .mention: return "chat"
        case .comment: return "comment"
        case .task: return "task_alt"
        case .assignment: return "assignment"
        case .file: return "description"
        case .system: return "info"
        }
    }

    public static func fromWire(_ raw: String?) -> NotificationType {
        guard let raw else { return .system }
        return NotificationType.allCases.first { $0.rawValue.caseInsensitiveCompare(raw) == .orderedSame } ?? .system
    }
}

/// Deep-link destination for a notification's View action.
public struct NotificationTarget: Sendable, Equatable {
    public let type: String
    public let id: String
    public let url: String?

    public init(type: String, id: String, url: String? = nil) {
        self.type = type
        self.id = id
        self.url = url
    }
}

/// Which per-notification actions are offered.
public struct NotificationActions: Sendable, Equatable {
    public let canView: Bool
    public let canMarkRead: Bool
    public let canMute: Bool

    public init(canView: Bool = true, canMarkRead: Bool = true, canMute: Bool = true) {
        self.canView = canView
        self.canMarkRead = canMarkRead
        self.canMute = canMute
    }
}

/// One workspace notification. `workspaceIcon` is a bundled icon reference resolved through
/// `NativeIconCatalog` so the tray can show the workspace glyph. Mirrors
/// `engine.notifications.WorkspaceNotification`.
public struct WorkspaceNotification: Sendable, Equatable, Identifiable {
    public let id: String
    public let workspaceId: String
    public let workspaceName: String
    public let workspaceIcon: String
    public let title: String
    public let message: String?
    public let createdAt: Int64
    public let type: NotificationType
    public let isRead: Bool
    public let isMuted: Bool
    public let isExpanded: Bool
    public let target: NotificationTarget?
    public let actions: NotificationActions

    public init(
        id: String,
        workspaceId: String,
        workspaceName: String,
        workspaceIcon: String,
        title: String,
        message: String? = nil,
        createdAt: Int64,
        type: NotificationType = .system,
        isRead: Bool = false,
        isMuted: Bool = false,
        isExpanded: Bool = false,
        target: NotificationTarget? = nil,
        actions: NotificationActions = NotificationActions()
    ) {
        self.id = id
        self.workspaceId = workspaceId
        self.workspaceName = workspaceName
        self.workspaceIcon = workspaceIcon
        self.title = title
        self.message = message
        self.createdAt = createdAt
        self.type = type
        self.isRead = isRead
        self.isMuted = isMuted
        self.isExpanded = isExpanded
        self.target = target
        self.actions = actions
    }

    /// True when this notification should raise an unread indicator.
    public var isUnread: Bool { !isRead }

    /// Copy-on-write helper mirroring Kotlin's `data class` `copy(...)`.
    public func copy(
        isRead: Bool? = nil,
        isMuted: Bool? = nil,
        isExpanded: Bool? = nil
    ) -> WorkspaceNotification {
        WorkspaceNotification(
            id: id,
            workspaceId: workspaceId,
            workspaceName: workspaceName,
            workspaceIcon: workspaceIcon,
            title: title,
            message: message,
            createdAt: createdAt,
            type: type,
            isRead: isRead ?? self.isRead,
            isMuted: isMuted ?? self.isMuted,
            isExpanded: isExpanded ?? self.isExpanded,
            target: target,
            actions: actions
        )
    }
}

/// A workspace's notifications collapsed into one group, newest-first. Mirrors
/// `engine.notifications.NotificationGroup`.
public struct NotificationGroup: Sendable, Equatable, Identifiable {
    public let workspaceId: String
    public let workspaceName: String
    public let workspaceIcon: String
    public let notifications: [WorkspaceNotification]
    public let unreadCount: Int
    public let isMuted: Bool
    public let latestAt: Int64

    public var id: String { workspaceId }

    /// Newest notification, used for the collapsed preview line.
    public var latest: WorkspaceNotification? { notifications.first }

    /// One-line preview shown while collapsed.
    public var preview: String? { latest.map { $0.message ?? $0.title } }

    public var size: Int { notifications.count }
}

/// Pure, platform-free notification store operations. Every function returns a new list (no
/// mutation), mirroring `engine.notifications.NotificationCenter`.
public enum NotificationCenter {

    private static let minute: Int64 = 60_000
    private static let hour: Int64 = 60 * minute
    private static let day: Int64 = 24 * hour
    private static let week: Int64 = 7 * day

    /// Total unread across all notifications.
    public static func unreadCount(_ notifications: [WorkspaceNotification]) -> Int {
        notifications.filter { $0.isUnread }.count
    }

    /// Unread count scoped to one workspace.
    public static func unreadCount(_ notifications: [WorkspaceNotification], workspaceId: String) -> Int {
        notifications.filter { $0.workspaceId == workspaceId && $0.isUnread }.count
    }

    /// Groups notifications by workspace, newest group first, newest notification first within
    /// a group. Read notifications remain visible, matching the reference tray.
    public static func group(_ notifications: [WorkspaceNotification]) -> [NotificationGroup] {
        var order: [String] = []
        var buckets: [String: [WorkspaceNotification]] = [:]
        for n in notifications {
            if buckets[n.workspaceId] == nil { order.append(n.workspaceId) }
            buckets[n.workspaceId, default: []].append(n)
        }
        return order.compactMap { workspaceId -> NotificationGroup? in
            guard let items = buckets[workspaceId] else { return nil }
            let ordered = items.sorted { $0.createdAt > $1.createdAt }
            guard let head = ordered.first else { return nil }
            return NotificationGroup(
                workspaceId: workspaceId,
                workspaceName: head.workspaceName,
                workspaceIcon: head.workspaceIcon,
                notifications: ordered,
                unreadCount: ordered.filter { $0.isUnread }.count,
                isMuted: ordered.allSatisfy { $0.isMuted },
                latestAt: head.createdAt
            )
        }
        .sorted { $0.latestAt > $1.latestAt }
    }

    /// Marks one notification read.
    public static func markRead(_ notifications: [WorkspaceNotification], id: String) -> [WorkspaceNotification] {
        notifications.map { $0.id == id ? $0.copy(isRead: true) : $0 }
    }

    /// Marks every notification read.
    public static func markAllRead(_ notifications: [WorkspaceNotification]) -> [WorkspaceNotification] {
        notifications.map { $0.copy(isRead: true) }
    }

    /// Marks every notification in one workspace read.
    public static func markWorkspaceRead(
        _ notifications: [WorkspaceNotification],
        workspaceId: String
    ) -> [WorkspaceNotification] {
        notifications.map { $0.workspaceId == workspaceId ? $0.copy(isRead: true) : $0 }
    }

    /// Mutes the selected notification and everything else in the same workspace. Muted
    /// notifications are also marked read so their unread dot clears immediately.
    public static func mute(_ notifications: [WorkspaceNotification], id: String) -> [WorkspaceNotification] {
        guard let workspaceId = notifications.first(where: { $0.id == id })?.workspaceId else {
            return notifications
        }
        return muteWorkspace(notifications, workspaceId: workspaceId)
    }

    /// Mutes an entire workspace group.
    public static func muteWorkspace(
        _ notifications: [WorkspaceNotification],
        workspaceId: String
    ) -> [WorkspaceNotification] {
        notifications.map {
            $0.workspaceId == workspaceId ? $0.copy(isRead: true, isMuted: true) : $0
        }
    }

    /// Un-mutes a workspace group so it resumes surfacing notifications.
    public static func unmuteWorkspace(
        _ notifications: [WorkspaceNotification],
        workspaceId: String
    ) -> [WorkspaceNotification] {
        notifications.map { $0.workspaceId == workspaceId ? $0.copy(isMuted: false) : $0 }
    }

    /// Removes one notification from the tray.
    public static func dismiss(_ notifications: [WorkspaceNotification], id: String) -> [WorkspaceNotification] {
        notifications.filter { $0.id != id }
    }

    /// Removes every notification belonging to a workspace.
    public static func dismissWorkspace(
        _ notifications: [WorkspaceNotification],
        workspaceId: String
    ) -> [WorkspaceNotification] {
        notifications.filter { $0.workspaceId != workspaceId }
    }

    /// Flips one notification's expanded state.
    public static func toggleExpanded(_ notifications: [WorkspaceNotification], id: String) -> [WorkspaceNotification] {
        notifications.map { $0.id == id ? $0.copy(isExpanded: !$0.isExpanded) : $0 }
    }

    /// Prepends `notification` unless its workspace is muted, in which case it is dropped so a
    /// muted workspace stops surfacing new items.
    public static func add(
        _ notifications: [WorkspaceNotification],
        notification: WorkspaceNotification,
        mutedWorkspaces: Set<String> = []
    ) -> [WorkspaceNotification] {
        mutedWorkspaces.contains(notification.workspaceId) ? notifications : [notification] + notifications
    }

    /// Compact relative age ("now", "8m", "3h", "2d", "1w"). `nowMillis` is injected so the
    /// formatting is deterministic under test.
    public static func relativeTime(createdAt: Int64, nowMillis: Int64) -> String {
        let delta = max(nowMillis - createdAt, 0)
        switch delta {
        case ..<minute: return "now"
        case ..<hour: return "\(delta / minute)m"
        case ..<day: return "\(delta / hour)h"
        case ..<week: return "\(delta / day)d"
        default: return "\(delta / week)w"
        }
    }
}
