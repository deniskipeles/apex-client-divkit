package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.ui.WorkspaceStandoutPill
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The collapsed pill was a 56x20 dp strip with `clickable` *inside* the minimum-size node, so
 * only the thin bar responded to a tap. These assertions pin the fix: the tagged node is the
 * full 48 dp touch target and clicking it opens the app bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkspaceStandoutPillTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `pill exposes the full 48 dp tap target and opens on click`() {
        var opened = 0
        compose.setContent {
            MyApplicationTheme {
                Box(modifier = Modifier.size(200.dp, 200.dp)) {
                    WorkspaceStandoutPill(isDark = true, onOpen = { opened += 1 })
                }
            }
        }

        compose.onNodeWithTag("workspace_standout_pill")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()

        compose.waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun `pill keeps its accessible label`() {
        compose.setContent {
            MyApplicationTheme {
                Box(modifier = Modifier.size(200.dp, 200.dp)) {
                    WorkspaceStandoutPill(isDark = false, onOpen = {})
                }
            }
        }

        compose.onNodeWithContentDescription("Workspace menu and Hub navigation").assertIsDisplayed()
    }
}
