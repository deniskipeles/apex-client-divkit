package engine.notifications

/**
 * Built-in demo notification seed for the workspace notifications tray.
 *
 * Spreads notifications across several workspaces so grouping, per-group unread counts,
 * right-aligned workspace glyphs, and the View / Mark read / Mute actions are all visible on
 * launch. Ages are relative to [nowMillis] so the demo never looks stale.
 *
 * Android-free so the tray seed is unit-testable on the JVM.
 */
object NotificationSeed {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    fun demo(nowMillis: Long): List<WorkspaceNotification> = listOf(
        // ---- Marketing Workspace ----
        WorkspaceNotification(
            id = "ntf_mkt_1",
            workspaceId = "marketing_ws",
            workspaceName = "Marketing Workspace",
            workspaceIcon = "campaign",
            title = "Sarah commented on \u201CQ4 Campaign Brief\u201D",
            message = "\u201CI have updated the budget assumptions for the launch quarter.\u201D",
            createdAt = nowMillis - 8 * MINUTE,
            type = NotificationType.COMMENT,
            target = NotificationTarget("document", "q4_campaign_brief")
        ),
        WorkspaceNotification(
            id = "ntf_mkt_2",
            workspaceId = "marketing_ws",
            workspaceName = "Marketing Workspace",
            workspaceIcon = "campaign",
            title = "David mentioned you in the launch discussion",
            message = "Can you confirm the retail promotion schedule before Friday?",
            createdAt = nowMillis - 42 * MINUTE,
            type = NotificationType.MENTION,
            target = NotificationTarget("thread", "launch_discussion")
        ),
        WorkspaceNotification(
            id = "ntf_mkt_3",
            workspaceId = "marketing_ws",
            workspaceName = "Marketing Workspace",
            workspaceIcon = "campaign",
            title = "Alex completed the design review",
            message = "The signage mockups are approved and ready for print.",
            createdAt = nowMillis - 3 * HOUR,
            type = NotificationType.TASK,
            isRead = true,
            target = NotificationTarget("task", "design_review")
        ),

        // ---- Apex POS Terminal (the built-in demo tenant) ----
        WorkspaceNotification(
            id = "ntf_pos_1",
            workspaceId = "acme_pos",
            workspaceName = "Apex POS Terminal",
            workspaceIcon = "storefront",
            title = "Kitchen ticket ready for pickup",
            message = "Order #1042 (Table 7) is plated and waiting at the pass.",
            createdAt = nowMillis - 5 * MINUTE,
            type = NotificationType.SYSTEM,
            target = NotificationTarget("order", "1042")
        ),
        WorkspaceNotification(
            id = "ntf_pos_2",
            workspaceId = "acme_pos",
            workspaceName = "Apex POS Terminal",
            workspaceIcon = "storefront",
            title = "Low stock: Truffle Parmesan Fries",
            message = "Only 6 units left across all registers. Reorder recommended.",
            createdAt = nowMillis - 26 * MINUTE,
            type = NotificationType.ASSIGNMENT,
            target = NotificationTarget("product", "ACM-102")
        ),

        // ---- Logistics Hub ----
        WorkspaceNotification(
            id = "ntf_log_1",
            workspaceId = "logistics_ws",
            workspaceName = "Logistics Hub",
            workspaceIcon = "group",
            title = "New file uploaded",
            message = "Product roadmap.pdf was uploaded to the planning folder.",
            createdAt = nowMillis - 1 * HOUR,
            type = NotificationType.FILE,
            target = NotificationTarget("file", "product_roadmap.pdf")
        ),

        // ---- Support Desk ----
        WorkspaceNotification(
            id = "ntf_sup_1",
            workspaceId = "support_ws",
            workspaceName = "Support Desk",
            workspaceIcon = "chat",
            title = "Task assigned to you",
            message = "Review the latest customer escalation for terminal POS-TERM-01.",
            createdAt = nowMillis - 2 * HOUR,
            type = NotificationType.ASSIGNMENT,
            target = NotificationTarget("task", "escalation_1042")
        ),
        WorkspaceNotification(
            id = "ntf_sup_2",
            workspaceId = "support_ws",
            workspaceName = "Support Desk",
            workspaceIcon = "chat",
            title = "Ticket reopened by customer",
            message = "The customer replied to ticket #8831 and reopened it.",
            createdAt = nowMillis - 2 * DAY,
            type = NotificationType.SYSTEM,
            isRead = true,
            target = NotificationTarget("ticket", "8831")
        )
    )

    /** A workspace id that the "simulate incoming" affordance targets by default. */
    val primaryWorkspaceId: String = "acme_pos"

    /**
     * Synthesises a fresh incoming notification for [workspaceId], used to prove real-time
     * group/count updates without a reload.
     */
    fun incoming(workspaceId: String, nowMillis: Long, sequence: Int): WorkspaceNotification {
        val known = demo(nowMillis).firstOrNull { it.workspaceId == workspaceId }
        val name = known?.workspaceName ?: "Workspace"
        val icon = known?.workspaceIcon ?: "storefront"
        return WorkspaceNotification(
            id = "ntf_live_${sequence}_$nowMillis",
            workspaceId = workspaceId,
            workspaceName = name,
            workspaceIcon = icon,
            title = "New activity in $name",
            message = "A teammate just posted an update in this workspace.",
            createdAt = nowMillis,
            type = NotificationType.SYSTEM,
            target = NotificationTarget("workspace", workspaceId)
        )
    }
}
