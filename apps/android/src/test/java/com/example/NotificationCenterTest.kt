package com.example

import engine.icons.NativeIconCatalog
import engine.notifications.NotificationCenter
import engine.notifications.NotificationSeed
import engine.notifications.NotificationType
import engine.notifications.WorkspaceNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure-Kotlin notification tray model and reducer
 * ([engine.notifications.NotificationCenter]). No Android or Compose types are touched, so
 * these run as plain JUnit tests.
 */
class NotificationCenterTest {

    private val now = 1_700_000_000_000L

    private fun n(
        id: String,
        workspaceId: String = "ws_a",
        workspaceName: String = "Workspace A",
        icon: String = "storefront",
        createdAt: Long = now,
        isRead: Boolean = false,
        isMuted: Boolean = false,
        isExpanded: Boolean = false
    ) = WorkspaceNotification(
        id = id,
        workspaceId = workspaceId,
        workspaceName = workspaceName,
        workspaceIcon = icon,
        title = "Title $id",
        message = "Message $id",
        createdAt = createdAt,
        isRead = isRead,
        isMuted = isMuted,
        isExpanded = isExpanded
    )

    // ------------------------------------------------------------------
    // Seeding
    // ------------------------------------------------------------------

    @Test
    fun `demo seed covers multiple workspaces with unread items`() {
        val seed = NotificationSeed.demo(now)
        assertTrue("seed must span several workspaces", NotificationCenter.group(seed).size >= 3)
        assertTrue("seed must contain unread items", NotificationCenter.unreadCount(seed) > 0)
        assertTrue("seed must contain at least one read item", seed.any { it.isRead })
    }

    @Test
    fun `demo seed icons all resolve through the native icon catalog`() {
        val unresolved = NotificationSeed.demo(now)
            .map { it.workspaceIcon }
            .distinct()
            .filterNot { NativeIconCatalog.isKnown(it) }
        assertTrue("workspace icons without a catalog entry: $unresolved", unresolved.isEmpty())
    }

    @Test
    fun `notification types map to catalog icons`() {
        val unresolved = NotificationType.entries
            .map { it.icon }
            .filterNot { NativeIconCatalog.isKnown(it) }
        assertTrue("notification type icons without a catalog entry: $unresolved", unresolved.isEmpty())
    }

    @Test
    fun `fromWire falls back to SYSTEM for unknown values`() {
        assertEquals(NotificationType.SYSTEM, NotificationType.fromWire("not-a-type"))
        assertEquals(NotificationType.SYSTEM, NotificationType.fromWire(null))
        assertEquals(NotificationType.MENTION, NotificationType.fromWire("mention"))
    }

    // ------------------------------------------------------------------
    // Grouping
    // ------------------------------------------------------------------

    @Test
    fun `group partitions by workspace, newest group first, newest item first`() {
        val list = listOf(
            n("a1", workspaceId = "ws_a", createdAt = now - 1000),
            n("b1", workspaceId = "ws_b", workspaceName = "B", createdAt = now - 500),
            n("a2", workspaceId = "ws_a", createdAt = now - 100),
            n("a3", workspaceId = "ws_a", createdAt = now - 5000)
        )
        val groups = NotificationCenter.group(list)

        assertEquals(2, groups.size)
        // ws_a's newest (now-100) beats ws_b's newest (now-500), so ws_a is first.
        assertEquals("ws_a", groups[0].workspaceId)
        assertEquals("ws_b", groups[1].workspaceId)
        // Within a group, newest first.
        assertEquals(listOf("a2", "a1", "a3"), groups[0].notifications.map { it.id })
        assertEquals(3, groups[0].size)
        assertEquals(3, groups[0].unreadCount)
        assertEquals("a2", groups[0].latest?.id)
        assertEquals("Message a2", groups[0].preview)
    }

    @Test
    fun `group counts only unread and reports muted only when every item is muted`() {
        val list = listOf(
            n("a1", isRead = true),
            n("a2", isRead = false),
            n("a3", isRead = false, isMuted = true)
        )
        val group = NotificationCenter.group(list).single()
        assertEquals(2, group.unreadCount)
        assertFalse("mixed mute state is not fully muted", group.isMuted)

        val allMuted = NotificationCenter.group(listOf(n("m1", isMuted = true), n("m2", isMuted = true)))
        assertTrue(allMuted.single().isMuted)
    }

    @Test
    fun `unreadCount aggregates across and within workspaces`() {
        val list = listOf(
            n("a1", workspaceId = "ws_a"),
            n("a2", workspaceId = "ws_a", isRead = true),
            n("b1", workspaceId = "ws_b")
        )
        assertEquals(2, NotificationCenter.unreadCount(list))
        assertEquals(1, NotificationCenter.unreadCount(list, "ws_a"))
        assertEquals(1, NotificationCenter.unreadCount(list, "ws_b"))
        assertEquals(0, NotificationCenter.unreadCount(list, "ws_missing"))
    }

    // ------------------------------------------------------------------
    // Reducers
    // ------------------------------------------------------------------

    @Test
    fun `markRead affects only the target notification`() {
        val list = listOf(n("a1"), n("a2"))
        val updated = NotificationCenter.markRead(list, "a1")
        assertTrue(updated.first { it.id == "a1" }.isRead)
        assertFalse(updated.first { it.id == "a2" }.isRead)
        // Original list is untouched (pure reducer).
        assertFalse(list.first { it.id == "a1" }.isRead)
    }

    @Test
    fun `markWorkspaceRead scopes to one workspace`() {
        val list = listOf(n("a1", workspaceId = "ws_a"), n("b1", workspaceId = "ws_b"))
        val updated = NotificationCenter.markWorkspaceRead(list, "ws_a")
        assertTrue(updated.first { it.workspaceId == "ws_a" }.isRead)
        assertFalse(updated.first { it.workspaceId == "ws_b" }.isRead)
    }

    @Test
    fun `markAllRead clears every unread flag`() {
        val updated = NotificationCenter.markAllRead(listOf(n("a1"), n("a2", isRead = true)))
        assertEquals(0, NotificationCenter.unreadCount(updated))
    }

    @Test
    fun `mute mutes the whole workspace and clears unread`() {
        val list = listOf(n("a1"), n("a2"), n("b1", workspaceId = "ws_b"))
        val updated = NotificationCenter.mute(list, "a1")
        assertTrue(updated.filter { it.workspaceId == "ws_a" }.all { it.isMuted && it.isRead })
        assertFalse(updated.first { it.workspaceId == "ws_b" }.isMuted)
        // Unread for the muted workspace collapses to zero.
        assertEquals(0, NotificationCenter.unreadCount(updated, "ws_a"))
    }

    @Test
    fun `mute of an unknown id is a no-op`() {
        val list = listOf(n("a1"))
        assertEquals(list, NotificationCenter.mute(list, "does_not_exist"))
    }

    @Test
    fun `unmute restores a workspace`() {
        val muted = NotificationCenter.muteWorkspace(listOf(n("a1"), n("b1", workspaceId = "ws_b")), "ws_a")
        assertTrue(muted.first { it.workspaceId == "ws_a" }.isMuted)
        val restored = NotificationCenter.unmuteWorkspace(muted, "ws_a")
        assertFalse(restored.first { it.workspaceId == "ws_a" }.isMuted)
        // An unrelated workspace was never muted.
        assertFalse(restored.first { it.workspaceId == "ws_b" }.isMuted)
    }

    @Test
    fun `dismiss removes one and dismissWorkspace removes a group`() {
        val list = listOf(n("a1"), n("a2"), n("b1", workspaceId = "ws_b"))
        assertEquals(listOf("a2", "b1"), NotificationCenter.dismiss(list, "a1").map { it.id })
        assertEquals(listOf("b1"), NotificationCenter.dismissWorkspace(list, "ws_a").map { it.id })
    }

    @Test
    fun `toggleExpanded flips one row`() {
        val list = listOf(n("a1", isExpanded = false), n("a2", isExpanded = true))
        val updated = NotificationCenter.toggleExpanded(list, "a1")
        assertTrue(updated.first { it.id == "a1" }.isExpanded)
        assertTrue(updated.first { it.id == "a2" }.isExpanded)
    }

    @Test
    fun `add prepends a notification unless its workspace is muted`() {
        val existing = listOf(n("a1"))
        val incoming = n("a2")
        val added = NotificationCenter.add(existing, incoming)
        assertEquals(listOf("a2", "a1"), added.map { it.id })

        val suppressed = NotificationCenter.add(existing, incoming, mutedWorkspaces = setOf("ws_a"))
        assertEquals(existing, suppressed)
    }

    @Test
    fun `simulated incoming notification lands in the right group and raises its count`() {
        val seed = NotificationSeed.demo(now)
        val before = NotificationCenter.group(seed).first { it.workspaceId == NotificationSeed.primaryWorkspaceId }
        val incoming = NotificationSeed.incoming(NotificationSeed.primaryWorkspaceId, now, sequence = 1)
        val after = NotificationCenter.group(NotificationCenter.add(seed, incoming))
            .first { it.workspaceId == NotificationSeed.primaryWorkspaceId }
        assertEquals(before.unreadCount + 1, after.unreadCount)
        assertEquals(incoming.id, after.latest?.id)
    }

    // ------------------------------------------------------------------
    // Relative time
    // ------------------------------------------------------------------

    @Test
    fun `relativeTime buckets by minute hour day week`() {
        assertEquals("now", NotificationCenter.relativeTime(now - 10_000, now))
        assertEquals("8m", NotificationCenter.relativeTime(now - 8 * 60_000, now))
        assertEquals("3h", NotificationCenter.relativeTime(now - 3 * 3_600_000, now))
        assertEquals("2d", NotificationCenter.relativeTime(now - 2 * 86_400_000, now))
        assertEquals("1w", NotificationCenter.relativeTime(now - 8 * 86_400_000, now))
        // Future timestamps clamp to "now" rather than producing a negative value.
        assertEquals("now", NotificationCenter.relativeTime(now + 5_000, now))
    }

    @Test
    fun `latest is null for an empty group payload`() {
        // Constructing a group directly guards the null-safe accessors used by the UI.
        val group = engine.notifications.NotificationGroup(
            workspaceId = "ws",
            workspaceName = "Ws",
            workspaceIcon = "storefront",
            notifications = emptyList(),
            unreadCount = 0,
            isMuted = false,
            latestAt = now
        )
        assertNull(group.latest)
        assertNull(group.preview)
        assertEquals(0, group.size)
    }

    @Test
    fun `every seeded workspace icon is non-blank and unique per workspace`() {
        val byWorkspace = NotificationSeed.demo(now).groupBy { it.workspaceId }
        byWorkspace.forEach { (id, items) ->
            val icons = items.map { it.workspaceIcon }.distinct()
            assertEquals("workspace $id must use a single icon", 1, icons.size)
            assertNotNull(icons.single())
            assertTrue(icons.single().isNotBlank())
        }
    }
}
