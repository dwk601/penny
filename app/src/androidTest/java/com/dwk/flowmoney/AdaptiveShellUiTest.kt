package com.dwk.flowmoney

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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

    private val primaryTabs =
        listOf(
            DashboardTab.Overview,
            DashboardTab.Transactions,
            DashboardTab.Insights,
        )

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
        primaryTabs.forEach { tab ->
            composeRule.onAllNodesWithTag("tab_${tab.name.lowercase()}").assertCountEquals(1)
        }
        composeRule.onAllNodesWithTag("tab_review").assertCountEquals(0)

        composeRule.runOnIdle { resize(600.dp) }
        composeRule.onAllNodesWithTag("wide_navigation").assertCountEquals(1)
        composeRule.onAllNodesWithTag("compact_navigation").assertCountEquals(0)
        primaryTabs.forEach { tab ->
            composeRule.onAllNodesWithTag("tab_${tab.name.lowercase()}").assertCountEquals(1)
        }
        composeRule.onAllNodesWithTag("tab_review").assertCountEquals(0)
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
        val bar = bounds("penny_top_bar")
        assertTrue("add=$fab bar=$bar", fab.top >= bar.top && fab.bottom <= bar.bottom && fab.right <= bar.right)
        assertTrue("add=$fab content=$compactContent", fab.bottom <= compactContent.top)
        assertTrue("add=$fab navigation=$bottomNavigation", fab.bottom <= bottomNavigation.top)
        composeRule.onAllNodesWithTag("add_transaction_fab").assertCountEquals(1)
        val compactDensity = composeRule.onNodeWithTag("add_transaction_fab").fetchSemanticsNode().layoutInfo.density.density
        assertTrue(fab.width / compactDensity >= 48f && fab.height / compactDensity >= 48f)

        composeRule.runOnIdle { resize(600.dp) }
        val wideContent = bounds("destination_content")
        val rail = bounds("wide_navigation")
        assertTrue("rail=$rail content=$wideContent", rail.right <= wideContent.left)
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val wideFab = bounds("add_transaction_fab")
        assertTrue("content=$wideContent root=$root", wideContent.bottom < root.bottom)
        val wideBar = bounds("penny_top_bar")
        assertTrue("add=$wideFab bar=$wideBar", wideFab.top >= wideBar.top && wideFab.bottom <= wideBar.bottom && wideFab.right <= wideBar.right)
        assertTrue("add=$wideFab content=$wideContent", wideFab.bottom <= wideContent.top)
        val wideDensity = composeRule.onNodeWithTag("add_transaction_fab").fetchSemanticsNode().layoutInfo.density.density
        assertTrue(wideFab.width / wideDensity >= 48f && wideFab.height / wideDensity >= 48f)
        composeRule.onAllNodesWithTag("add_transaction_fab").assertCountEquals(1)
    }

    @Test
    fun reviewDestinationStaysHiddenFromCompactAndRailNavigation() {
        var resize: (Dp) -> Unit = {}
        composeRule.setContent {
            var width by remember { mutableStateOf(599.dp) }
            resize = { width = it }
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(width, 800.dp))) {
                MaterialTheme { TestShell(DashboardTab.Review, {}, reviewOriginTab = DashboardTab.Insights) }
            }
        }

        composeRule.onAllNodesWithTag("tab_review").assertCountEquals(0)
        composeRule.onNodeWithTag("tab_insights").assertIsSelected()
        composeRule.onNodeWithTag("tab_overview").assertIsNotSelected()
        composeRule.onNodeWithTag("tab_transactions").assertIsNotSelected()
        composeRule.onNodeWithTag("destination_content").assertIsDisplayed()
        composeRule.runOnIdle { resize(600.dp) }
        composeRule.onAllNodesWithTag("tab_review").assertCountEquals(0)
        composeRule.onNodeWithTag("tab_insights").assertIsSelected()
        composeRule.onNodeWithTag("tab_overview").assertIsNotSelected()
        composeRule.onNodeWithTag("tab_transactions").assertIsNotSelected()
        composeRule.onNodeWithTag("destination_content").assertIsDisplayed()
    }

    @Test fun bottomNavigationLabelsFitAtTwoX() {
        var width by mutableStateOf(360)
        var scale by mutableStateOf(1f)
        var selected by mutableStateOf(DashboardTab.Overview)
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(width.dp, 800.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(scale)) {
                    FlowMoneyTheme(dynamicColor = false) { TestShell(selected, { selected = it }) }
                }
            }
        }
        for (screenWidth in listOf(300, 360)) for (fontScale in listOf(1f, 1.5f, 2f)) {
            composeRule.runOnIdle { width = screenWidth; scale = fontScale }
            for (tab in primaryTabs) {
                primaryTabs.forEach { destination ->
                    val node = composeRule.onNodeWithTag("tab_${destination.name.lowercase()}")
                        .assert(hasContentDescription(destination.label) or hasText(destination.label))
                        .assertIsDisplayed().fetchSemanticsNode()
                    val density = node.layoutInfo.density.density
                    val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().count { it == destination.label }
                    val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().count { it.text == destination.label }
                    assertTrue("duplicate or missing name for ${destination.label}: ${node.config}", descriptions + texts == 1)
                    if (screenWidth == 300 && fontScale == 2f && destination != selected) {
                        assertTrue("hidden label must expose an icon name to Android accessibility", descriptions == 1)
                    }
                    assertTrue("$screenWidth/$fontScale ${destination.label}: ${node.boundsInRoot}", node.boundsInRoot.width / density >= 47.5f && node.boundsInRoot.height / density >= 47.5f)
                }
                val item = composeRule.onNodeWithTag("tab_${tab.name.lowercase()}").performClick().assertIsSelected().fetchSemanticsNode().boundsInRoot
                val text = composeRule.onNodeWithText(tab.label, useUnmergedTree = true).assertIsDisplayed()
                val textBounds = text.fetchSemanticsNode().boundsInRoot
                val results = mutableListOf<TextLayoutResult>()
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                val layout = results.single()
                assertTrue("$screenWidth/$fontScale ${tab.label} must be one line", layout.lineCount == 1)
                assertFalse("$screenWidth/$fontScale ${tab.label} overflows: $layout", layout.hasVisualOverflow)
                assertFalse(layout.isLineEllipsized(0))
                assertTrue("font ${layout.layoutInput.style.fontSize}", layout.layoutInput.style.fontSize.value >= 10f)
                assertTrue("$textBounds outside $item", textBounds.left >= item.left - 1 && textBounds.right <= item.right + 1 && textBounds.top >= item.top - 1 && textBounds.bottom <= item.bottom + 1)
                if (screenWidth == 360 && fontScale == 1f) assertTrue("roomy label shrank", layout.layoutInput.style.fontSize.value >= 12f)
            }
        }
    }

    @Test fun snackbarDoesNotCoverScrollableContentEnd() {
        var show: () -> Unit = {}
        composeRule.setContent {
            val host = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            show = { scope.launch { host.showSnackbar("Deleted transaction", actionLabel = "Undo", duration = SnackbarDuration.Indefinite) } }
            FlowMoneyTheme(dynamicColor = false) {
                AdaptiveFlowMoneyShell(selectedTab = DashboardTab.Insights, snackbarHostState = host, onTabSelected = {}, onAddTransaction = {}, onData = {}, modifier = Modifier.fillMaxSize()) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize().testTag("snackbar_safe_content"))
                }
            }
        }
        composeRule.runOnIdle { show() }
        val content = bounds("snackbar_safe_content")
        val snackbar = bounds("app_snackbar_host")
        assertTrue("content=$content snackbar=$snackbar", content.bottom <= snackbar.top + 1f)
    }

    @Test
    fun regularAddFabRemainsReachableOnPrimaryTabs() {
        composeRule.setContent {
            var selected by remember { mutableStateOf(DashboardTab.Overview) }
            MaterialTheme { TestShell(selected, { selected = it }) }
        }

        primaryTabs.forEach { tab ->
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
    reviewOriginTab: DashboardTab = DashboardTab.Overview,
) {
    AdaptiveFlowMoneyShell(
        selectedTab = selectedTab,
        reviewOriginTab = reviewOriginTab,
        snackbarHostState = remember { SnackbarHostState() },
        onTabSelected = onTabSelected,
        onAddTransaction = {},
        onData = {},
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
