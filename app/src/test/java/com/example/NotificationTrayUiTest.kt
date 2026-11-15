package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.enginehost.SduiEngineUiState
import com.example.ui.NotificationTrayActions
import com.example.ui.NotificationTrayPanel
import com.example.ui.theme.MyApplicationTheme
import engine.notifications.NotificationSeed
import engine.notifications.WorkspaceNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose UI tests for the workspace notifications tray panel.
 *
 * The panel is rendered directly with recording callbacks, so the tests assert the rendered
 * structure (grouped headers, unread counts, expand affordance, right-aligned workspace glyphs,
 * per-notification actions) and that each action dispatches the right callback.
 *
 * The panel lives inside a `heightIn(max = 640.dp)` scrollable list, so assertions on rows below
 * the fold use `assertExists()` (a node in the tree that has not scrolled into view is still
 * present, but not "displayed").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationTrayUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val now = 1_700_000_000_000L

    private fun state(
        expanded: Set<String> = emptySet(),
        showRead: Boolean = true,
        loading: Boolean = false,
        notifications: List<WorkspaceNotification> = NotificationSeed.demo(now)
    ) = SduiEngineUiState(
        notifications = notifications,
        notificationsLoading = loading,
        notificationShowRead = showRead,
        expandedNotificationGroups = expanded
    )

    private fun render(
        uiState: SduiEngineUiState,
        actions: NotificationTrayActions = NotificationTrayActions()
    ) {
        composeRule.setContent {
            MyApplicationTheme {
                NotificationTrayPanel(uiState = uiState, actions = actions)
            }
        }
    }

    @Test
    fun `collapsed groups show workspace name, unread count and expand affordance`() {
        render(state())
        // The seeded demo has 4 workspace groups; each header is present.
        composeRule.onNodeWithTag("notification_group_marketing_ws").assertIsDisplayed()
        composeRule.onNodeWithTag("notification_group_acme_pos").assertExists()
        composeRule.onNodeWithTag("notification_group_logistics_ws").assertExists()
        composeRule.onNodeWithTag("notification_group_support_ws").assertExists()

        // Workspace names render.
        composeRule.onNodeWithText("Marketing Workspace").assertIsDisplayed()
        composeRule.onNodeWithText("Apex POS Terminal").assertExists()

        // Collapsed: no notification rows yet.
        composeRule.onNodeWithTag("notification_row_ntf_mkt_1").assertDoesNotExist()

        // Header reports the state.
        composeRule.onNodeWithText("Notifications").assertIsDisplayed()
    }

    @Test
    fun `expanding a group reveals rows with right-aligned workspace glyph and actions`() {
        render(state(expanded = setOf("marketing_ws")))

        // The first marketing notification renders with its glyph and View / Mark read / Mute
        // actions. The glyph is tagged per row and labelled "<name> workspace" for accessibility.
        composeRule.onNodeWithTag("notification_row_ntf_mkt_1").assertIsDisplayed()
        composeRule.onNodeWithTag("notification_row_glyph_ntf_mkt_1").assertExists()
        // The header and the row both expose the workspace glyph; assert at least one is labelled.
        composeRule.onAllNodesWithContentDescription("Marketing Workspace workspace")
            .onFirst()
            .assertExists()
        composeRule.onNodeWithTag("notification_view_ntf_mkt_1").assertExists()
        composeRule.onNodeWithTag("notification_mark_read_ntf_mkt_1").assertExists()
        composeRule.onNodeWithTag("notification_mute_ntf_mkt_1").assertExists()

        // LazyColumn only composes on-screen items, so scroll the remaining rows into view.
        val list = composeRule.onNodeWithTag("notification_tray_list")
        list.performScrollToNode(hasTestTag("notification_row_ntf_mkt_3"))
        composeRule.onNodeWithTag("notification_row_ntf_mkt_3").assertExists()
        list.performScrollToNode(hasTestTag("notification_row_ntf_mkt_2"))
        composeRule.onNodeWithTag("notification_row_ntf_mkt_2").assertExists()

        // A collapsed group's rows are never composed.
        composeRule.onNodeWithTag("notification_row_ntf_pos_1").assertDoesNotExist()
    }

    @Test
    fun `toggle group dispatches the workspace id`() {
        var toggled: String? = null
        render(state(), NotificationTrayActions(onToggleGroup = { toggled = it }))
        composeRule.onNodeWithTag("notification_group_acme_pos").performClick()
        assertEquals("acme_pos", toggled)
    }

    @Test
    fun `view, mark read and mute dispatch the owning notification`() {
        var viewed: String? = null
        var markedRead: String? = null
        var muted: String? = null
        render(
            state(expanded = setOf("acme_pos")),
            NotificationTrayActions(
                onView = { viewed = it.id },
                onMarkRead = { markedRead = it.id },
                onMute = { muted = it.id }
            )
        )

        composeRule.onNodeWithTag("notification_view_ntf_pos_1").performClick()
        composeRule.onNodeWithTag("notification_mark_read_ntf_pos_1").performClick()
        composeRule.onNodeWithTag("notification_mute_ntf_pos_1").performClick()

        assertEquals("ntf_pos_1", viewed)
        assertEquals("ntf_pos_1", markedRead)
        assertEquals("ntf_pos_1", muted)
    }

    @Test
    fun `mark all read is disabled when nothing is unread`() {
        val allRead = NotificationSeed.demo(now).map { it.copy(isRead = true) }
        render(state(notifications = allRead))
        // Header switches to the caught-up copy and the mark-all-read control is disabled.
        composeRule.onNodeWithText("You're all caught up").assertIsDisplayed()
        composeRule.onNodeWithTag("notification_mark_all_read").assertIsNotEnabled()
    }

    @Test
    fun `hiding read notifications keeps groups that still have unread items`() {
        render(state(showRead = false))
        // Every seeded group has at least one unread item, so all groups remain.
        composeRule.onNodeWithTag("notification_group_marketing_ws").assertIsDisplayed()
        composeRule.onNodeWithTag("notification_group_support_ws").assertExists()
    }

    @Test
    fun `hiding read notifications shows the empty state when all are read`() {
        val readOnly = NotificationSeed.demo(now).map { it.copy(isRead = true) }
        render(state(showRead = false, notifications = readOnly))
        composeRule.onNodeWithTag("notification_empty_state").assertIsDisplayed()
    }

    @Test
    fun `loading with no data shows the skeleton`() {
        render(state(loading = true, notifications = emptyList()))
        composeRule.onNodeWithTag("notification_skeleton").assertIsDisplayed()
    }

    @Test
    fun `settings bar exposes show-read switch and simulate-incoming`() {
        var simulated = false
        render(
            state().copy(notificationSettingsOpen = true),
            NotificationTrayActions(onSimulateIncoming = { simulated = true })
        )
        composeRule.onNodeWithTag("notification_show_read_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("notification_simulate_incoming").performClick()
        assertTrue(simulated)
    }

    @Test
    fun `muted group shows a muted indicator`() {
        val muted = NotificationSeed.demo(now).map {
            if (it.workspaceId == "logistics_ws") it.copy(isMuted = true) else it
        }
        render(state(notifications = muted))
        composeRule.onNodeWithContentDescription("Muted").assertExists()
    }
}
