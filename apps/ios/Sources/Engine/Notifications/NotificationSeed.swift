import Foundation

/// Built-in demo notification seed for the workspace notifications tray. Mirrors
/// `engine.notifications.NotificationSeed`.
///
/// Spreads notifications across several workspaces so grouping, per-group unread counts,
/// right-aligned workspace glyphs, and the View / Mark read / Mute actions are all visible on
/// launch. Ages are relative to `nowMillis` so the demo never looks stale.
public enum NotificationSeed {

    private static let minute: Int64 = 60_000
    private static let hour: Int64 = 60 * minute
    private static let day: Int64 = 24 * hour

    public static func demo(nowMillis: Int64) -> [WorkspaceNotification] {
        [
            // ---- Marketing Workspace ----
            WorkspaceNotification(
                id: "ntf_mkt_1",
                workspaceId: "marketing_ws",
                workspaceName: "Marketing Workspace",
                workspaceIcon: "campaign",
                title: "Sarah commented on \u{201C}Q4 Campaign Brief\u{201D}",
                message: "\u{201C}I have updated the budget assumptions for the launch quarter.\u{201D}",
                createdAt: nowMillis - 8 * minute,
                type: .comment,
                target: NotificationTarget(type: "document", id: "q4_campaign_brief")
            ),
            WorkspaceNotification(
                id: "ntf_mkt_2",
                workspaceId: "marketing_ws",
                workspaceName: "Marketing Workspace",
                workspaceIcon: "campaign",
                title: "David mentioned you in the launch discussion",
                message: "Can you confirm the retail promotion schedule before Friday?",
                createdAt: nowMillis - 42 * minute,
                type: .mention,
                target: NotificationTarget(type: "thread", id: "launch_discussion")
            ),
            WorkspaceNotification(
                id: "ntf_mkt_3",
                workspaceId: "marketing_ws",
                workspaceName: "Marketing Workspace",
                workspaceIcon: "campaign",
                title: "Alex completed the design review",
                message: "The signage mockups are approved and ready for print.",
                createdAt: nowMillis - 3 * hour,
                type: .task,
                isRead: true,
                target: NotificationTarget(type: "task", id: "design_review")
            ),

            // ---- Apex POS Terminal (the built-in demo tenant) ----
            WorkspaceNotification(
                id: "ntf_pos_1",
                workspaceId: "acme_pos",
                workspaceName: "Apex POS Terminal",
                workspaceIcon: "storefront",
                title: "Kitchen ticket ready for pickup",
                message: "Order #1042 (Table 7) is plated and waiting at the pass.",
                createdAt: nowMillis - 5 * minute,
                type: .system,
                target: NotificationTarget(type: "order", id: "1042")
            ),
            WorkspaceNotification(
                id: "ntf_pos_2",
                workspaceId: "acme_pos",
                workspaceName: "Apex POS Terminal",
                workspaceIcon: "storefront",
                title: "Low stock: Truffle Parmesan Fries",
                message: "Only 6 units left across all registers. Reorder recommended.",
                createdAt: nowMillis - 26 * minute,
                type: .assignment,
                target: NotificationTarget(type: "product", id: "ACM-102")
            ),

            // ---- Logistics Hub ----
            WorkspaceNotification(
                id: "ntf_log_1",
                workspaceId: "logistics_ws",
                workspaceName: "Logistics Hub",
                workspaceIcon: "group",
                title: "New file uploaded",
                message: "Product roadmap.pdf was uploaded to the planning folder.",
                createdAt: nowMillis - 1 * hour,
                type: .file,
                target: NotificationTarget(type: "file", id: "product_roadmap.pdf")
            ),

            // ---- Support Desk ----
            WorkspaceNotification(
                id: "ntf_sup_1",
                workspaceId: "support_ws",
                workspaceName: "Support Desk",
                workspaceIcon: "chat",
                title: "Task assigned to you",
                message: "Review the latest customer escalation for terminal POS-TERM-01.",
                createdAt: nowMillis - 2 * hour,
                type: .assignment,
                target: NotificationTarget(type: "task", id: "escalation_1042")
            ),
            WorkspaceNotification(
                id: "ntf_sup_2",
                workspaceId: "support_ws",
                workspaceName: "Support Desk",
                workspaceIcon: "chat",
                title: "Ticket reopened by customer",
                message: "The customer replied to ticket #8831 and reopened it.",
                createdAt: nowMillis - 2 * day,
                type: .system,
                isRead: true,
                target: NotificationTarget(type: "ticket", id: "8831")
            )
        ]
    }

    /// A workspace id that the "simulate incoming" affordance targets by default.
    public static let primaryWorkspaceId: String = "acme_pos"

    /// Synthesises a fresh incoming notification for `workspaceId`, used to prove real-time
    /// group/count updates without a reload.
    public static func incoming(workspaceId: String, nowMillis: Int64, sequence: Int) -> WorkspaceNotification {
        let known = demo(nowMillis: nowMillis).first { $0.workspaceId == workspaceId }
        let name = known?.workspaceName ?? "Workspace"
        let icon = known?.workspaceIcon ?? "storefront"
        return WorkspaceNotification(
            id: "ntf_live_\(sequence)_\(nowMillis)",
            workspaceId: workspaceId,
            workspaceName: name,
            workspaceIcon: icon,
            title: "New activity in \(name)",
            message: "A teammate just posted an update in this workspace.",
            createdAt: nowMillis,
            type: .system,
            target: NotificationTarget(type: "workspace", id: workspaceId)
        )
    }
}
