package com.example

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.example.enginehost.SduiEngineUiState
import com.example.enginehost.WorkspaceProvisioner
import com.example.ui.DeveloperDiagnosticsSheet
import com.example.ui.SchemaVersionRollbackSheet
import com.example.ui.theme.MyApplicationTheme
import engine.db.CachedSchemaVersion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The developer surface used to be an always-present block under every workspace card. It now
 * lives behind `⋮ → Developer diagnostics`, and it no longer carries the route inspector or the
 * hardware capability tester. These tests pin both halves: the sheet renders what it should and
 * the removed sections stay removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeveloperDiagnosticsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val spec = WorkspaceProvisioner.defaultWorkspaces.first()

    @Test
    fun diagnosticsShowTheClientIdAndDropTheRemovedSections() {
        var rollbackOpened = false
        var settingsOpened = false
        composeRule.setContent {
            MyApplicationTheme {
                DeveloperDiagnosticsSheet(
                    uiState = SduiEngineUiState(activeWorkspace = spec),
                    spec = spec,
                    permissions = spec.defaultTools,
                    onOpenWorkspaceSettings = { settingsOpened = true },
                    onOpenSchemaRollback = { rollbackOpened = true }
                )
            }
        }

        composeRule.onNodeWithText(spec.publicClientId).assertIsDisplayed()
        composeRule.onNodeWithTag("copy_public_client_id_button").assertIsDisplayed()

        // Removed sections must not come back.
        listOf(
            "Active Route Path",
            "URL Queries",
            "Hardware capability tester",
            "Receive New Schema Version"
        ).forEach { removed ->
            composeRule.onAllNodesWithTextContaining(removed).assertCountEquals(0)
        }

        composeRule.onNodeWithTag("open_schema_rollback_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(true, rollbackOpened)
        assertEquals(false, settingsOpened)
    }

    @Test
    fun diagnosticsBridgeNoticeOpensWorkspaceSettings() {
        var settingsOpened = false
        composeRule.setContent {
            MyApplicationTheme {
                DeveloperDiagnosticsSheet(
                    uiState = SduiEngineUiState(activeWorkspace = spec),
                    spec = spec,
                    permissions = spec.defaultTools,
                    onOpenWorkspaceSettings = { settingsOpened = true },
                    onOpenSchemaRollback = {}
                )
            }
        }
        composeRule.onNodeWithText("Native Android bridge", substring = true)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(true, settingsOpened)
    }

    @Test
    fun rollbackLedgerExplainsAnEmptyCache() {
        composeRule.setContent {
            MyApplicationTheme {
                SchemaVersionRollbackSheet(
                    uiState = SduiEngineUiState(activeWorkspace = spec),
                    onRollback = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.onNodeWithTag("schema_rollback_empty_state").assertIsDisplayed()
        composeRule.onNodeWithText("No cached versions yet").assertIsDisplayed()
    }

    @Test
    fun rollbackLedgerRequiresConfirmationBeforeActivating() {
        val versions = listOf(
            CachedSchemaVersion(2L, "1.1", "jti-new", "{}", """{"card":{}}""", 2_000L, true, "Updated schema"),
            CachedSchemaVersion(1L, "1.0", "jti-old", "{}", """{"card":{}}""", 1_000L, false, "Initial schema")
        )
        var rolledBackTo: CachedSchemaVersion? = null
        composeRule.setContent {
            MyApplicationTheme {
                SchemaVersionRollbackSheet(
                    uiState = SduiEngineUiState(
                        activeWorkspace = spec,
                        schemaVersions = versions,
                        activeSchemaVersion = versions.first()
                    ),
                    onRollback = { rolledBackTo = it },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("schema_version_card_1.0").performScrollToNode(hasTestTag("rollback_to_v_1.0_button"))
        composeRule.onNodeWithTag("rollback_to_v_1.0_button").performClick()
        composeRule.waitForIdle()
        assertEquals("rollback must not fire before the user confirms", null, rolledBackTo)

        composeRule.onNodeWithTag("confirm_schema_rollback_button").performClick()
        composeRule.waitForIdle()
        assertEquals("1.0", rolledBackTo?.schemaVersion)
    }
}

/** `onAllNodesWithText` with a substring match, kept local so the assertions read clearly. */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextContaining(
    text: String
) = onAllNodes(hasText(text, substring = true))
