package com.dwk.flowmoney

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdaptiveShellUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun breakpointUsesExactlyOneNavigationSurface() {
        var resize: (Dp) -> Unit = {}
        composeRule.setContent {
            var width by remember { mutableStateOf(599.dp) }
            resize = { width = it }
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(width, 800.dp))) {
                MaterialTheme { TestShell(DashboardTab.Overview, {}) }
            }
        }
        composeRule.onAllNodesWithTag("compact_navigation").assertCountEquals(1)
        composeRule.onAllNodesWithTag("wide_navigation").assertCountEquals(0)
        DashboardTab.entries.forEach { tab ->
            composeRule.onAllNodesWithTag("tab_${tab.name.lowercase()}").assertCountEquals(1)
        }

        composeRule.runOnIdle { resize(600.dp) }
        composeRule.onAllNodesWithTag("wide_navigation").assertCountEquals(1)
        composeRule.onAllNodesWithTag("compact_navigation").assertCountEquals(0)
    }

    @Test
    fun selectionSurvivesWidthChangeAndMovesToTheNewSurface() {
        var widen: () -> Unit = {}
        composeRule.setContent {
            var width by remember { mutableStateOf(599.dp) }
            var selected by remember { mutableStateOf(DashboardTab.Overview) }
            widen = { width = 600.dp }
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(width, 800.dp))) {
                MaterialTheme {
                    TestShell(selected, { selected = it })
                }
            }
        }

        composeRule.onNodeWithTag("tab_transactions").performClick().assertIsSelected()
        composeRule.runOnIdle(widen)
        composeRule.onAllNodesWithTag("compact_navigation").assertCountEquals(0)
        composeRule.onAllNodesWithTag("wide_navigation").assertCountEquals(1)
        composeRule.onNodeWithTag("tab_transactions").assertIsSelected()
    }

    @Test
    fun contentIsAboveCompactNavigationAndBesideWideRail() {
        var resize: (Dp) -> Unit = {}
        composeRule.setContent {
            var width by remember { mutableStateOf(599.dp) }
            resize = { width = it }
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(width, 800.dp))) {
                MaterialTheme { TestShell(DashboardTab.Overview, {}) }
            }
        }
        val compactContent = bounds("destination_content")
        val bottomNavigation = bounds("compact_navigation")
        assertTrue("content=$compactContent navigation=$bottomNavigation", compactContent.bottom <= bottomNavigation.top)
        val fab = bounds("add_transaction_fab")
        assertTrue("fab=$fab navigation=$bottomNavigation", fab.bottom <= bottomNavigation.top)

        composeRule.runOnIdle { resize(600.dp) }
        val wideContent = bounds("destination_content")
        val rail = bounds("wide_navigation")
        assertTrue("rail=$rail content=$wideContent", rail.right <= wideContent.left)
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val wideFab = bounds("add_transaction_fab")
        assertTrue("content=$wideContent root=$root", wideContent.bottom < root.bottom)
        assertTrue("fab=$wideFab content=$wideContent", wideFab.bottom <= wideContent.bottom)
    }

    @Test
    fun reviewBadgeAnnouncesPendingStateOnCompactAndRail() {
        var resize: (Dp) -> Unit = {}
        composeRule.setContent {
            var width by remember { mutableStateOf(599.dp) }
            resize = { width = it }
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(width, 800.dp))) {
                MaterialTheme { TestShell(DashboardTab.Review, {}, pendingReviewCount = 7) }
            }
        }

        val pendingState =
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "7 transactions pending review",
            )
        composeRule.onNodeWithTag("tab_review").assertIsSelected().assert(pendingState)
        composeRule.runOnIdle { resize(600.dp) }
        composeRule.onNodeWithTag("tab_review").assertIsSelected().assert(pendingState)
    }

    @Test
    fun regularAddFabRemainsReachableOnAllFourTabs() {
        composeRule.setContent {
            var selected by remember { mutableStateOf(DashboardTab.Overview) }
            MaterialTheme { TestShell(selected, { selected = it }) }
        }

        DashboardTab.entries.forEach { tab ->
            composeRule.onNodeWithTag("tab_${tab.name.lowercase()}").performClick().assertIsSelected()
            val fab =
                composeRule
                    .onNodeWithTag("add_transaction_fab")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("FAB target was $fab", fab.width / composeRule.density.density >= 48f)
            assertTrue("FAB target was $fab", fab.height / composeRule.density.density >= 48f)
        }
    }

    @Test
    fun wideLandscapeWithLargeFontKeepsShellReachable() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(700.dp, 360.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(1.3f)) {
                    MaterialTheme { TestShell(DashboardTab.Overview, {}) }
                }
            }
        }

        composeRule.onNodeWithTag("wide_navigation").assertIsDisplayed()
        composeRule.onNodeWithText("Data").assertIsDisplayed()
        composeRule.onNodeWithTag("add_transaction_fab").assertIsDisplayed()
        composeRule.onNodeWithTag("destination_content").assertIsDisplayed()
        composeRule.onAllNodesWithTag("compact_navigation").assertCountEquals(0)
    }

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
}

@androidx.compose.runtime.Composable
private fun TestShell(
    selectedTab: DashboardTab,
    onTabSelected: (DashboardTab) -> Unit,
    pendingReviewCount: Int = 0,
) {
    AdaptiveFlowMoneyShell(
        selectedTab = selectedTab,
        snackbarHostState = remember { SnackbarHostState() },
        onTabSelected = onTabSelected,
        onAddTransaction = {},
        onData = {},
        pendingReviewCount = pendingReviewCount,
        modifier = Modifier.fillMaxSize(),
    ) { padding: PaddingValues ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .testTag("destination_content"),
        )
    }
}
