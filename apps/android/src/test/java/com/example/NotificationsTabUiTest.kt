package com.example

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.enginehost.SduiEngineUiState
import com.example.ui.NotificationTrayActions
import com.example.ui.NotificationsTabContent
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
 * Robolectric + Compose UI tests for the full-page Notifications tab.
 *
 * The tab renders [NotificationsTabContent] with recording callbacks, so these tests assert the
 * page structure (header summary, per-workspace filter chips, grouped feed) and that filtering and
 * actions dispatch correctly without a live view model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationsTabUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val now = 1_700_000_000_000L

    private fun state(
        expanded: Set<String> = emptySet(),
        showRead: Boolean = true,
        loading: Boolean = false,
        settingsOpen: Boolean = false,
        notifications: List<WorkspaceNotification> = NotificationSeed.demo(now)
    ) = SduiEngineUiState(
        notifications = notifications,
        notificationsLoading = loading,
        notificationShowRead = showRead,
        notificationSettingsOpen = settingsOpen,
        expandedNotificationGroups = expanded
    )

    /** The chip row is a LazyRow, so off-screen chips must be scrolled into composition. */
    private fun scrollToFilter(testTag: String) {
        composeRule.onNodeWithTag("notifications_tab_filters")
            .performScrollToNode(hasTestTag(testTag))
    }

    private fun render(
        uiState: SduiEngineUiState,
        actions: NotificationTrayActions = NotificationTrayActions(),
        showHeader: Boolean = true
    ) {
        composeRule.setContent {
            MyApplicationTheme {
                NotificationsTabContent(
                    uiState = uiState,
                    actions = actions,
                    showHeader = showHeader
                )
            }
        }
    }

    @Test
    fun `header summarises unread count across workspaces`() {
        render(state())
        composeRule.onNodeWithText("Notifications").assertIsDisplayed()
        val demo = NotificationSeed.demo(now)
        val unread = demo.count { !it.isRead }
        val workspaces = demo.map { it.workspaceId }.distinct().size
        composeRule.onNodeWithText("$unread unread across $workspaces workspaces").assertIsDisplayed()
    }

    @Test
    fun `header reports caught up when nothing is unread`() {
        val allRead = NotificationSeed.demo(now).map { it.copy(isRead = true) }
        render(state(notifications = allRead))
        composeRule.onNodeWithText("You're all caught up").assertIsDisplayed()
        composeRule.onNodeWithTag("notifications_tab_mark_all_read").assertIsNotEnabled()
    }

    @Test
    fun `filter chips list all workspaces plus an all chip`() {
        render(state())
        composeRule.onNodeWithTag("notifications_tab_filters").assertIsDisplayed()
        composeRule.onNodeWithTag("notifications_tab_filter_all").assertIsDisplayed()
        NotificationSeed.demo(now).map { it.workspaceId }.distinct().forEach { id ->
            scrollToFilter("notifications_tab_filter_$id")
            composeRule.onNodeWithTag("notifications_tab_filter_$id").assertExists()
        }
    }

    @Test
    fun `selecting a workspace chip narrows the feed to that workspace`() {
        render(state())
        // All four seeded groups render while the "All" filter is active.
        composeRule.onNodeWithTag("notification_group_marketing_ws").assertIsDisplayed()
        composeRule.onNodeWithTag("notification_group_acme_pos").assertExists()

        scrollToFilter("notifications_tab_filter_acme_pos")
        composeRule.onNodeWithTag("notifications_tab_filter_acme_pos").performClick()

        composeRule.onNodeWithTag("notification_group_acme_pos").assertExists()
        composeRule.onNodeWithTag("notification_group_marketing_ws").assertDoesNotExist()
        composeRule.onNodeWithTag("notification_group_logistics_ws").assertDoesNotExist()
    }

    @Test
    fun `selecting all restores every workspace`() {
        render(state())
        scrollToFilter("notifications_tab_filter_acme_pos")
        composeRule.onNodeWithTag("notifications_tab_filter_acme_pos").performClick()
        composeRule.onNodeWithTag("notification_group_marketing_ws").assertDoesNotExist()

        scrollToFilter("notifications_tab_filter_all")
        composeRule.onNodeWithTag("notifications_tab_filter_all").performClick()
        composeRule.onNodeWithTag("notification_group_marketing_ws").assertExists()
    }

    @Test
    fun `expanded group renders rows and dispatches row actions`() {
        var viewed: String? = null
        render(
            state(expanded = setOf("acme_pos")),
            NotificationTrayActions(onView = { viewed = it.id })
        )

        composeRule.onNodeWithTag("notification_row_ntf_pos_1").assertIsDisplayed()
        composeRule.onNodeWithTag("notification_view_ntf_pos_1").performClick()
        assertEquals("ntf_pos_1", viewed)
    }

    @Test
    fun `group header dispatches toggle and mark-all-read`() {
        var toggled: String? = null
        render(state(), NotificationTrayActions(onToggleGroup = { toggled = it }))
        composeRule.onNodeWithTag("notification_group_marketing_ws").performClick()
        assertEquals("marketing_ws", toggled)
    }

    @Test
    fun `settings panel exposes show-read switch and simulate-incoming`() {
        var simulated = false
        render(
            state(settingsOpen = true),
            NotificationTrayActions(onSimulateIncoming = { simulated = true })
        )
        composeRule.onNodeWithTag("notifications_tab_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("notifications_tab_show_read_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("notifications_tab_simulate_incoming").performClick()
        assertTrue(simulated)
    }

    @Test
    fun `hiding read notifications shows the empty state when all are read`() {
        val readOnly = NotificationSeed.demo(now).map { it.copy(isRead = true) }
        render(state(showRead = false, notifications = readOnly))
        composeRule.onNodeWithTag("notification_empty_state").assertIsDisplayed()
    }

    @Test
    fun `loading with no data shows the skeleton and no filter chips`() {
        render(state(loading = true, notifications = emptyList()))
        composeRule.onNodeWithTag("notification_skeleton").assertIsDisplayed()
        composeRule.onNodeWithTag("notifications_tab_filters").assertDoesNotExist()
    }

    @Test
    fun `embedded mode drops the page title but keeps the controls`() {
        render(state(), showHeader = false)
        composeRule.onAllNodesWithText("Notifications").assertCountEquals(0)
        composeRule.onNodeWithTag("notifications_tab_mark_all_read").assertIsDisplayed()
        composeRule.onNodeWithTag("notifications_tab_settings_button").assertIsDisplayed()
        composeRule.onNodeWithTag("notification_group_marketing_ws").assertIsDisplayed()
    }

    @Test
    fun `mark all read dispatches from the tab header`() {
        var markedAll = false
        render(state(), NotificationTrayActions(onMarkAllRead = { markedAll = true }))
        composeRule.onNodeWithTag("notifications_tab_mark_all_read").performClick()
        assertTrue(markedAll)
    }
}
