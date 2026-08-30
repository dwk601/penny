package com.dwk.flowmoney

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Transactions tab list/map toggle and the three location filters. The geocode cache is seeded up
 * front so the map path never needs a Nominatim request, and no assertion depends on a map tile
 * being fetched.
 */
class TransactionLocationUiTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var transactions by mutableStateOf(allTransactions)

    @Before fun seedGeocodeCache() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
        transactions = allTransactions
        runBlocking {
            val dao = FlowMoneyDatabase.get(context).placeGeocodeDao()
            listOf(
                Triple("portland|or|us", 45.5152 to -122.6784, "Portland"),
                Triple("portland|me|us", 43.6591 to -70.2568, "Portland"),
                Triple("seattle|wa|us", 47.6062 to -122.3321, "Seattle"),
            ).forEach { (key, coordinates, city) ->
                dao.upsert(
                    PlaceGeocodeEntity(
                        placeKey = key,
                        city = city,
                        state = null,
                        country = "US",
                        latitude = coordinates.first,
                        longitude = coordinates.second,
                        resolvedAtEpochMillis = 1_700_000_000_000,
                    ),
                )
            }
        }
    }

    @After fun clearDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
    }

    @Test fun listAndMapViewsToggleAndTheMapReportsTransactionsItCannotPlace() {
        setTransactionsTab()

        composeRule.onNodeWithTag("transaction_view_list").assertIsSelected()
        composeRule.onNodeWithTag("transaction_view_map").assertIsNotSelected()
        composeRule.onNodeWithTag("transactions_list").assertExists()
        composeRule.onNodeWithTag("transactions_map").assertDoesNotExist()

        composeRule.onNodeWithTag("transaction_view_map").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("transaction_view_map").assertIsSelected()
        composeRule.onNodeWithTag("transactions_map").assertExists()
        // One list hosts both modes, so map mode is proven by the rows being gone, not the list.
        composeRule.onNodeWithTag("transaction_content_tx-nowhere").assertDoesNotExist()
        // One seeded transaction has no location at all, so the map has to surface it.
        composeRule.onNodeWithTag("transactions_unmappable").assertExists()

        composeRule.onNodeWithTag("transaction_view_list").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("transactions_list").assertExists()
        composeRule.onNodeWithTag("transactions_map").assertDoesNotExist()
        composeRule.onNodeWithTag("transaction_content_tx-nowhere").assertExists()
    }

    @Test fun theMapRendersAMarkerForACachedResolvablePlace() {
        val geocoder = PlaceGeocoder(FlowMoneyDatabase.get(context).placeGeocodeDao())
        composeRule.setContent {
            MaterialTheme {
                TransactionMap(
                    transactions = listOf(allTransactions.first()),
                    geocoder = geocoder,
                    onEdit = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("transactions_map").assertExists()
        composeRule.onNodeWithTag("transactions_unmappable").assertDoesNotExist()
    }

    @Test fun theMapCanBeLeftAndReenteredRepeatedly() {
        setTransactionsTab()

        repeat(3) {
            composeRule.onNodeWithTag("transaction_view_map").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("transactions_map").assertExists()
            // Leaving the map releases the osmdroid view; re-entering must build a usable one.
            composeRule.onNodeWithTag("transactions_unmappable").assertExists()

            composeRule.onNodeWithTag("transaction_view_list").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("transactions_list").assertExists()
            composeRule.onNodeWithTag("transactions_map").assertDoesNotExist()
        }

        assertVisible("tx-portland-or", "tx-portland-me", "tx-seattle", "tx-nowhere")
    }

    /**
     * Regression guard for the detach crash: the map is only ever reachable by toggling into it,
     * which means it is always composed after the first frame.
     */
    @Test fun theMapSurvivesBeingComposedAfterTheFirstFrame() {
        val geocoder = PlaceGeocoder(FlowMoneyDatabase.get(context).placeGeocodeDao())
        var showMap by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                if (showMap) {
                    TransactionMap(
                        transactions = listOf(allTransactions.first()),
                        geocoder = geocoder,
                        onEdit = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { showMap = true }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("transactions_map").assertExists()
    }

    @Test fun theMapReportsEveryTransactionItCannotPlaceWhenNothingResolves() {
        val geocoder = PlaceGeocoder(FlowMoneyDatabase.get(context).placeGeocodeDao())
        composeRule.setContent {
            MaterialTheme {
                TransactionMap(
                    transactions = allTransactions.filter { it.locationCity == null },
                    geocoder = geocoder,
                    onEdit = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("transactions_map").assertExists()
        composeRule.onNodeWithTag("transactions_unmappable").assertExists()
    }

    @Test fun everyLocationFilterGroupIsOfferedOnlyForPlacesThatExist() {
        setTransactionsTab()
        expandFilters()

        scrollTo("transaction_city_filter_group")
        composeRule.onNodeWithTag("transaction_city_filter_all").assertIsSelected()
        // Repeated city names are disambiguated by state and country in both label and tag.
        composeRule.onNodeWithTag("transaction_city_filter_portlandorus").assertExists()
        composeRule.onNodeWithTag("transaction_city_filter_portlandmeus").assertExists()
        composeRule.onNodeWithTag("transaction_city_filter_seattle").assertExists()

        scrollTo("transaction_state_filter_group")
        listOf("or", "me", "wa").forEach { state ->
            composeRule.onNodeWithTag("transaction_state_filter_$state").assertExists()
        }

        scrollTo("transaction_country_filter_group")
        composeRule.onNodeWithTag("transaction_country_filter_us").assertExists()
        composeRule.onNodeWithTag("transaction_country_filter_ca").assertDoesNotExist()
    }

    @Test fun selectingADuplicatedCityNarrowsToExactlyThatPlace() {
        setTransactionsTab()
        expandFilters()
        scrollTo("transaction_city_filter_group")

        composeRule.onNodeWithTag("transaction_city_filter_portlandorus").performClick()
        composeRule.waitForIdle()

        assertVisible("tx-portland-or")
        assertHidden("tx-portland-me", "tx-seattle", "tx-nowhere")

        // Dropping the state widens back to both cities named Portland.
        scrollTo("transaction_state_filter_group")
        composeRule.onNodeWithTag("transaction_state_filter_all").performClick()
        composeRule.waitForIdle()

        assertVisible("tx-portland-or", "tx-portland-me")
        assertHidden("tx-seattle", "tx-nowhere")
    }

    @Test fun aUniqueCitySelectionClearsAConflictingStateAndAppliesTheCity() {
        setTransactionsTab()
        expandFilters()

        // Oregon is active, then the user picks Seattle, which is in Washington.
        scrollTo("transaction_state_filter_group")
        composeRule.onNodeWithTag("transaction_state_filter_or").performClick()
        composeRule.waitForIdle()
        assertVisible("tx-portland-or")
        assertHidden("tx-seattle")

        scrollTo("transaction_city_filter_group")
        composeRule.onNodeWithTag("transaction_city_filter_seattle").performClick()
        composeRule.waitForIdle()

        assertVisible("tx-seattle")
        assertHidden("tx-portland-or", "tx-portland-me", "tx-nowhere")
        composeRule.onNodeWithTag("transaction_city_filter_seattle").assertIsSelected()
        scrollTo("transaction_state_filter_group")
        composeRule.onNodeWithTag("transaction_state_filter_all").assertIsSelected()
        composeRule.onNodeWithTag("transaction_state_filter_or").assertIsNotSelected()
    }

    @Test fun aUniqueCitySelectionClearsBothAConflictingStateAndCountry() {
        transactions = allTransactions + toronto
        setTransactionsTab()
        expandFilters()

        scrollTo("transaction_state_filter_group")
        composeRule.onNodeWithTag("transaction_state_filter_or").performClick()
        scrollTo("transaction_country_filter_group")
        composeRule.onNodeWithTag("transaction_country_filter_us").performClick()
        composeRule.waitForIdle()
        assertVisible("tx-portland-or")
        assertHidden("tx-toronto")

        scrollTo("transaction_city_filter_group")
        composeRule.onNodeWithTag("transaction_city_filter_toronto").performClick()
        composeRule.waitForIdle()

        assertVisible("tx-toronto")
        assertHidden("tx-portland-or", "tx-portland-me", "tx-seattle", "tx-nowhere")
        scrollTo("transaction_state_filter_group")
        composeRule.onNodeWithTag("transaction_state_filter_all").assertIsSelected()
        scrollTo("transaction_country_filter_group")
        composeRule.onNodeWithTag("transaction_country_filter_all").assertIsSelected()
    }

    @Test fun aUniqueCitySelectionKeepsAnAlreadyAgreeingStateAndCountry() {
        transactions = allTransactions + toronto
        setTransactionsTab()
        expandFilters()

        scrollTo("transaction_state_filter_group")
        composeRule.onNodeWithTag("transaction_state_filter_wa").performClick()
        scrollTo("transaction_country_filter_group")
        composeRule.onNodeWithTag("transaction_country_filter_us").performClick()
        composeRule.waitForIdle()

        scrollTo("transaction_city_filter_group")
        composeRule.onNodeWithTag("transaction_city_filter_seattle").performClick()
        composeRule.waitForIdle()

        assertVisible("tx-seattle")
        assertHidden("tx-portland-or", "tx-portland-me", "tx-toronto", "tx-nowhere")
        scrollTo("transaction_state_filter_group")
        composeRule.onNodeWithTag("transaction_state_filter_wa").assertIsSelected()
        scrollTo("transaction_country_filter_group")
        composeRule.onNodeWithTag("transaction_country_filter_us").assertIsSelected()
    }

    @Test fun duplicateCityDisambiguationStillOverridesAConflictingStateAndCountry() {
        transactions = allTransactions + toronto
        setTransactionsTab()
        expandFilters()

        scrollTo("transaction_state_filter_group")
        composeRule.onNodeWithTag("transaction_state_filter_on").performClick()
        scrollTo("transaction_country_filter_group")
        composeRule.onNodeWithTag("transaction_country_filter_ca").performClick()
        composeRule.waitForIdle()
        assertVisible("tx-toronto")

        // A duplicated city name still pins state and country to that exact place.
        scrollTo("transaction_city_filter_group")
        composeRule.onNodeWithTag("transaction_city_filter_portlandmeus").performClick()
        composeRule.waitForIdle()

        assertVisible("tx-portland-me")
        assertHidden("tx-portland-or", "tx-seattle", "tx-toronto", "tx-nowhere")
        scrollTo("transaction_state_filter_group")
        composeRule.onNodeWithTag("transaction_state_filter_me").assertIsSelected()
        scrollTo("transaction_country_filter_group")
        composeRule.onNodeWithTag("transaction_country_filter_us").assertIsSelected()
    }

    @Test fun clearingTheCityLeavesTheOtherLocationFiltersAlone() {
        setTransactionsTab()
        expandFilters()

        scrollTo("transaction_country_filter_group")
        composeRule.onNodeWithTag("transaction_country_filter_us").performClick()
        scrollTo("transaction_city_filter_group")
        composeRule.onNodeWithTag("transaction_city_filter_seattle").performClick()
        composeRule.waitForIdle()
        assertVisible("tx-seattle")

        composeRule.onNodeWithTag("transaction_city_filter_all").performClick()
        composeRule.waitForIdle()

        assertVisible("tx-portland-or", "tx-portland-me", "tx-seattle")
        assertHidden("tx-nowhere")
        scrollTo("transaction_country_filter_group")
        composeRule.onNodeWithTag("transaction_country_filter_us").assertIsSelected()
    }

    @Test fun stateAndCountryFiltersNarrowTheListIndependently() {
        setTransactionsTab()
        expandFilters()

        scrollTo("transaction_state_filter_group")
        composeRule.onNodeWithTag("transaction_state_filter_wa").performClick()
        composeRule.waitForIdle()
        assertVisible("tx-seattle")
        assertHidden("tx-portland-or", "tx-portland-me", "tx-nowhere")

        scrollTo("transaction_state_filter_group")
        composeRule.onNodeWithTag("transaction_state_filter_all").performClick()
        scrollTo("transaction_country_filter_group")
        composeRule.onNodeWithTag("transaction_country_filter_us").performClick()
        composeRule.waitForIdle()

        assertVisible("tx-portland-or", "tx-portland-me", "tx-seattle")
        assertHidden("tx-nowhere")
    }

    @Test fun locationFiltersSurviveTheSwitchToTheMapView() {
        setTransactionsTab()
        expandFilters()
        scrollTo("transaction_city_filter_group")
        composeRule.onNodeWithTag("transaction_city_filter_seattle").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("transaction_view_map").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("transactions_map").assertExists()
        composeRule.onNodeWithTag("transaction_city_filter_seattle").assertIsSelected()
        // Everything on the map now has a place, so there is nothing to report as unmappable.
        composeRule.onNodeWithTag("transactions_unmappable").assertDoesNotExist()
    }

    @Test fun optionsThatDisappearFromTheDataClearTheSelectionInsteadOfEmptyingTheList() {
        setTransactionsTab()
        expandFilters()
        scrollTo("transaction_city_filter_group")
        composeRule.onNodeWithTag("transaction_city_filter_seattle").performClick()
        composeRule.waitForIdle()
        assertVisible("tx-seattle")
        assertHidden("tx-portland-or")

        composeRule.runOnIdle { transactions = allTransactions.filterNot { it.id == "tx-seattle" } }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("transaction_city_filter_seattle").assertDoesNotExist()
        composeRule.onNodeWithTag("transaction_city_filter_all").assertIsSelected()
        assertVisible("tx-portland-or", "tx-portland-me", "tx-nowhere")
    }

    @Test fun clearResetsEveryLocationFilterAtOnce() {
        setTransactionsTab()
        expandFilters()
        scrollTo("transaction_city_filter_group")
        composeRule.onNodeWithTag("transaction_city_filter_portlandorus").performClick()
        scrollTo("transaction_country_filter_group")
        composeRule.onNodeWithTag("transaction_country_filter_us").performClick()
        composeRule.waitForIdle()
        assertVisible("tx-portland-or")
        assertHidden("tx-nowhere")

        scrollTo("transaction_filter_clear")
        composeRule.onNodeWithTag("transaction_filter_clear").performClick()
        composeRule.waitForIdle()

        assertVisible("tx-portland-or", "tx-portland-me", "tx-seattle", "tx-nowhere")
        composeRule.onNodeWithTag("transaction_filter_clear").assertDoesNotExist()
        composeRule.onNodeWithTag("transaction_filter_clear_compact").assertDoesNotExist()
        // Clear collapses the panel, so the filter groups are gone with it.
        composeRule.onNodeWithTag("transaction_city_filter_group").assertDoesNotExist()
    }

    private fun setTransactionsTab() {
        composeRule.setContent {
            MaterialTheme {
                FlowMoneyScreen(
                    uiState =
                        MainUiState(
                            isLoading = false,
                            sortedTransactions = transactions,
                            recentTransactions = transactions,
                            rangeTransactions = transactions,
                            metrics = DashboardMetrics(0, 0, transactions.size),
                            dateRange =
                                DashboardDateRange(
                                    LocalDate.of(2026, 7, 1),
                                    LocalDate.of(2026, 8, 1),
                                    "July 2026",
                                ),
                            selectedMonth = YearMonth.of(2026, 7),
                            availableMonths = listOf(YearMonth.of(2026, 7)),
                        ),
                    selectedTab = DashboardTab.Transactions,
                    onChartRangeModeSelected = {},
                    onSelectedMonthChange = {},
                    onEdit = {},
                    onViewAllTransactions = {},
                    onDelete = {},
                    onAddTransaction = {},
                    onData = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun expandFilters() {
        composeRule.onNodeWithTag("transaction_filter_toggle").performClick()
        composeRule.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag(tag))
    }

    private fun assertVisible(vararg ids: String) {
        ids.forEach { id ->
            scrollTo("transaction_content_$id")
            composeRule.onNodeWithTag("transaction_content_$id").assertIsDisplayed()
        }
    }

    private fun assertHidden(vararg ids: String) {
        ids.forEach { id -> composeRule.onNodeWithTag("transaction_content_$id").assertDoesNotExist() }
    }

    private companion object {
        val allTransactions =
            listOf(
                transaction("tx-portland-or", "Blue Bottle", "Coffee", "Portland", "OR", "US"),
                transaction("tx-portland-me", "Lobster Shack", "Food", "Portland", "ME", "US"),
                transaction("tx-seattle", "Pike Place", "Food", "Seattle", "WA", "US"),
                transaction("tx-nowhere", "Rent", "Home", null, null, null),
            )

        /** Only used by the conflict tests, so the shared fixture above stays untouched. */
        val toronto = transaction("tx-toronto", "Tim Hortons", "Coffee", "Toronto", "ON", "CA")

        fun transaction(
            id: String,
            merchant: String,
            category: String,
            city: String?,
            state: String?,
            country: String?,
        ) = Transaction(
            id = id,
            occurredAtEpochMillis =
                LocalDate
                    .of(2026, 7, 10)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli(),
            merchant = merchant,
            category = category,
            note = "",
            cents = -500,
            locationCity = city,
            locationState = state,
            locationCountry = country,
        )
    }
}
