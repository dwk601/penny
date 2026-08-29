package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TransactionFiltersTest {
    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 6, 20)

    @Test
    fun filtersByWeekCategoryAndWhereText() {
        val transactions =
            listOf(
                transaction(id = "match", merchant = "Blue Bottle", category = "Coffee"),
                transaction(id = "old", date = today.minusDays(8), merchant = "Blue Bottle", category = "Coffee"),
                transaction(id = "category", merchant = "Blue Bottle", category = "Food"),
                transaction(id = "where", merchant = "Corner Shop", category = "Coffee"),
            )

        val filtered =
            TransactionFilters.apply(
                transactions = transactions,
                filter =
                    TransactionFilter(
                        time = TransactionTimeFilter.Week,
                        category = "Coffee",
                        where = "blue",
                    ),
                today = today,
                zoneId = utc,
            )

        assertThat(filtered.map { it.id }).containsExactly("match")
    }

    @Test
    fun sourceFilterSeparatesAllSyncedAndEveryManualSourceIncludingCsv() {
        val transactions =
            listOf(
                transaction(id = "synced", source = "simplefin"),
                transaction(id = "local", source = "local"),
                transaction(id = "csv-import", source = "local"),
                transaction(id = "other-manual", source = "other"),
                transaction(id = "wrong-case", source = "SimpleFIN"),
            )

        assertThat(apply(transactions, TransactionFilter(source = TransactionSourceFilter.All)))
            .containsExactly("synced", "local", "csv-import", "other-manual", "wrong-case")
            .inOrder()
        assertThat(apply(transactions, TransactionFilter(source = TransactionSourceFilter.Synced)))
            .containsExactly("synced")
        assertThat(apply(transactions, TransactionFilter(source = TransactionSourceFilter.Manual)))
            .containsExactly("local", "csv-import", "other-manual", "wrong-case")
            .inOrder()
    }

    @Test
    fun accountFilterUsesExactStableAccountKey() {
        val transactions =
            listOf(
                transaction(id = "first", source = "simplefin", accountKey = "simplefin:v2:first"),
                transaction(id = "second", source = "simplefin", accountKey = "simplefin:v2:second"),
                transaction(id = "manual"),
            )

        assertThat(apply(transactions, TransactionFilter(accountKey = "simplefin:v2:second")))
            .containsExactly("second")
        assertThat(apply(transactions, TransactionFilter(accountKey = "SIMPLEFIN:v2:second")))
            .isEmpty()
    }

    @Test
    fun accountOptionsDisambiguateRepeatedNamesWithInstitutionAndKeepUniqueNamesSimple() {
        val accounts =
            listOf(
                account(key = "first-key", name = "Checking", institution = "First Bank"),
                account(key = "second-key", name = "Checking", institution = "Second Bank"),
                account(key = "savings-key", name = "Savings", institution = "First Bank"),
            )

        assertThat(TransactionFilters.accountOptions(accounts))
            .containsExactly(
                TransactionAccountOption(
                    accountKey = "first-key",
                    accountName = "Checking",
                    institutionName = "First Bank",
                    label = "Checking (First Bank)",
                ),
                TransactionAccountOption(
                    accountKey = "second-key",
                    accountName = "Checking",
                    institutionName = "Second Bank",
                    label = "Checking (Second Bank)",
                ),
                TransactionAccountOption(
                    accountKey = "savings-key",
                    accountName = "Savings",
                    institutionName = "First Bank",
                    label = "Savings",
                ),
            ).inOrder()
    }

    @Test
    fun whereTextMatchesCaseAndAccentsInMerchantNoteOrAccountName() {
        val transactions =
            listOf(
                transaction(id = "merchant", merchant = "Café   Nero", category = "Coffee"),
                transaction(id = "note", merchant = "Shop", category = "Food", note = "Work lunch"),
                transaction(
                    id = "account",
                    merchant = "Shop",
                    category = "Food",
                    accountName = "Crédit   Union Checking",
                ),
            )

        assertThat(apply(transactions, TransactionFilter(where = "  CAFE NERO "))).containsExactly("merchant")
        assertThat(apply(transactions, TransactionFilter(where = "work"))).containsExactly("note")
        assertThat(apply(transactions, TransactionFilter(where = "credit union"))).containsExactly("account")
    }

    @Test
    fun unreviewedOnlyUsesTransactionFoundationDefinition() {
        val transactions =
            listOf(
                transaction(id = "unreviewed", source = "simplefin"),
                transaction(id = "reviewed", source = "simplefin", reviewedAtEpochMillis = 123L),
                transaction(id = "manual", source = "local"),
            )

        assertThat(apply(transactions, TransactionFilter(unreviewedOnly = true)))
            .containsExactly("unreviewed")
        assertThat(transactions.filter { it.isUnreviewed }.map { it.id })
            .containsExactlyElementsIn(apply(transactions, TransactionFilter(unreviewedOnly = true)))
    }

    @Test
    fun combinesTimeCategorySourceAccountReviewAndSearchFilters() {
        val matching =
            transaction(
                id = "match",
                merchant = "Corner Shop",
                category = "Café",
                note = "Team lunch",
                source = "simplefin",
                accountKey = "account-key",
            )
        val transactions =
            listOf(
                matching,
                matching.copy(id = "old", occurredAtEpochMillis = epochMillis(today.minusDays(8))),
                matching.copy(id = "category", category = "Travel"),
                matching.copy(id = "manual", source = "local"),
                matching.copy(id = "account", accountKey = "other-key"),
                matching.copy(id = "reviewed", reviewedAtEpochMillis = 1L),
                matching.copy(id = "where", merchant = "Other", note = "Other"),
            )

        val filter =
            TransactionFilter(
                time = TransactionTimeFilter.Week,
                category = "cafe",
                where = "team lunch",
                source = TransactionSourceFilter.Synced,
                accountKey = "account-key",
                unreviewedOnly = true,
            )

        assertThat(apply(transactions, filter)).containsExactly("match")
    }

    @Test
    fun categoriesReturnsAllPresentValuesInEncounterOrderWithoutNormalizedDuplicates() {
        val categoryNames =
            (1..13).map { "Category $it" } +
                listOf(" Café ", "cafe", "", " Other ", "Bills   Home", "bills home")
        val transactions = categoryNames.mapIndexed { index, category -> transaction(id = "$index", category = category) }

        assertThat(TransactionFilters.categories(transactions))
            .containsExactlyElementsIn(
                (1..13).map { "Category $it" } + listOf("Café", "Other", "Bills   Home"),
            ).inOrder()
    }

    @Test
    fun oldThreeArgumentFilterConstructionDefaultsNewFiltersToAll() {
        val filter = TransactionFilter(TransactionTimeFilter.Week, "Coffee", "blue")

        assertThat(filter.source).isEqualTo(TransactionSourceFilter.All)
        assertThat(filter.accountKey).isNull()
        assertThat(filter.unreviewedOnly).isFalse()
        assertThat(filter.city).isNull()
        assertThat(filter.state).isNull()
        assertThat(filter.country).isNull()
    }

    @Test
    fun locationFiltersComposeWithEveryPreExistingFilter() {
        val matching =
            transaction(
                id = "match",
                merchant = "Corner Shop",
                category = "Café",
                note = "Team lunch",
                source = "simplefin",
                accountKey = "account-key",
                city = "Portland",
                state = "OR",
                country = "US",
            )
        val transactions =
            listOf(
                matching,
                matching.copy(id = "old", occurredAtEpochMillis = epochMillis(today.minusDays(8))),
                matching.copy(id = "category", category = "Travel"),
                matching.copy(id = "manual", source = "local"),
                matching.copy(id = "account", accountKey = "other-key"),
                matching.copy(id = "reviewed", reviewedAtEpochMillis = 1L),
                matching.copy(id = "where", merchant = "Other", note = "Other"),
                matching.copy(id = "city", locationCity = "Seattle"),
                matching.copy(id = "state", locationState = "WA"),
                matching.copy(id = "country", locationCountry = "CA"),
                matching.copy(id = "no-location", locationCity = null, locationState = null, locationCountry = null),
            )

        val filter =
            TransactionFilter(
                time = TransactionTimeFilter.Week,
                category = "cafe",
                where = "team lunch",
                source = TransactionSourceFilter.Synced,
                accountKey = "account-key",
                unreviewedOnly = true,
                city = "Portland",
                state = "OR",
                country = "US",
            )

        assertThat(apply(transactions, filter)).containsExactly("match")
        assertThat(apply(transactions, TransactionFilter(city = "Portland")))
            .containsExactly("match", "old", "category", "manual", "account", "reviewed", "where", "state", "country")
            .inOrder()
        assertThat(apply(transactions, TransactionFilter(state = "WA"))).containsExactly("state")
        assertThat(apply(transactions, TransactionFilter(country = "CA"))).containsExactly("country")
    }

    @Test
    fun locationFilterMatchingUsesTheSharedSearchKeyNormalization() {
        val transactions =
            listOf(
                transaction(id = "accented", city = "  SÃO   Paulo ", state = " sp ", country = "BR"),
                transaction(id = "other", city = "Sao Paolo", state = "SP", country = "BR"),
            )

        assertThat(apply(transactions, TransactionFilter(city = "sao paulo"))).containsExactly("accented")
        assertThat(apply(transactions, TransactionFilter(city = "  SAO    PAULO  "))).containsExactly("accented")
        assertThat(apply(transactions, TransactionFilter(state = "SP")))
            .containsExactly("accented", "other")
            .inOrder()
        assertThat(apply(transactions, TransactionFilter(country = "br")))
            .containsExactly("accented", "other")
            .inOrder()
        assertThat(apply(transactions, TransactionFilter(city = "saopaulo"))).isEmpty()
    }

    @Test
    fun blankLocationFilterValuesOnlyMatchRowsWithoutThatLocationPart() {
        val transactions =
            listOf(
                transaction(id = "located", city = "Portland", state = "OR"),
                transaction(id = "unlocated"),
            )

        assertThat(apply(transactions, TransactionFilter(city = "   "))).containsExactly("unlocated")
        assertThat(apply(transactions, TransactionFilter(country = ""))).containsExactly("located", "unlocated").inOrder()
    }

    @Test
    fun cityOptionsDisambiguateRepeatedCityNamesAndKeepUniqueNamesSimple() {
        val transactions =
            listOf(
                transaction(id = "1", city = "Portland", state = "OR", country = "US"),
                transaction(id = "2", city = "Portland", state = "ME", country = "US"),
                transaction(id = "3", city = "Seattle", state = "WA", country = "US"),
                transaction(id = "4", city = "Paris", state = null, country = "FR"),
                transaction(id = "5", city = "Paris", state = "TX", country = null),
                transaction(id = "6", city = "  portland ", state = " or ", country = " us "),
                transaction(id = "7", city = "   ", state = "XX"),
                transaction(id = "8"),
            )

        assertThat(TransactionFilters.cityOptions(transactions))
            .containsExactly(
                TransactionCityOption(city = "Portland", state = "OR", country = "US", label = "Portland, OR, US"),
                TransactionCityOption(city = "Portland", state = "ME", country = "US", label = "Portland, ME, US"),
                TransactionCityOption(city = "Seattle", state = "WA", country = "US", label = "Seattle"),
                TransactionCityOption(city = "Paris", state = null, country = "FR", label = "Paris, FR"),
                TransactionCityOption(city = "Paris", state = "TX", country = null, label = "Paris, TX"),
            ).inOrder()
    }

    @Test
    fun stateAndCountryOptionsDeduplicateByNormalizedKeyInEncounterOrder() {
        val transactions =
            listOf(
                transaction(id = "1", city = "Portland", state = "OR", country = "US"),
                transaction(id = "2", city = "Salem", state = " or ", country = " us "),
                transaction(id = "3", city = "Seattle", state = "WA", country = "US"),
                transaction(id = "4", city = "Paris", state = "   ", country = "FR"),
                transaction(id = "5"),
            )

        assertThat(TransactionFilters.stateOptions(transactions)).containsExactly("OR", "WA").inOrder()
        assertThat(TransactionFilters.countryOptions(transactions)).containsExactly("US", "FR").inOrder()
        assertThat(TransactionFilters.stateOptions(emptyList())).isEmpty()
        assertThat(TransactionFilters.countryOptions(emptyList())).isEmpty()
        assertThat(TransactionFilters.cityOptions(emptyList())).isEmpty()
    }

    @Test
    fun viewModeExposesExactlyListAndMapLabels() {
        assertThat(TransactionViewMode.entries.map { it.name }).containsExactly("List", "Map").inOrder()
        assertThat(TransactionViewMode.entries.map { it.label }).containsExactly("List", "Map").inOrder()
    }

    private fun apply(
        transactions: List<Transaction>,
        filter: TransactionFilter,
    ): List<String> = TransactionFilters.apply(transactions, filter, today, utc).map { it.id }

    private fun transaction(
        id: String,
        date: LocalDate = today,
        merchant: String = "Merchant",
        category: String = "Other",
        note: String = "",
        source: String = "local",
        accountKey: String? = null,
        accountName: String? = null,
        reviewedAtEpochMillis: Long? = null,
        city: String? = null,
        state: String? = null,
        country: String? = null,
    ): Transaction =
        Transaction(
            id = id,
            occurredAtEpochMillis = epochMillis(date),
            merchant = merchant,
            category = category,
            note = note,
            cents = -100,
            source = source,
            accountKey = accountKey,
            accountName = accountName,
            reviewedAtEpochMillis = reviewedAtEpochMillis,
            locationCity = city,
            locationState = state,
            locationCountry = country,
        )

    private fun epochMillis(date: LocalDate): Long = date.atStartOfDay(utc).toInstant().toEpochMilli()

    private fun account(
        key: String,
        name: String,
        institution: String?,
    ): SimpleFinAccountEntity =
        SimpleFinAccountEntity(
            accountId = key,
            name = name,
            currency = "USD",
            institutionName = institution,
            balanceAmount = null,
            availableBalanceAmount = null,
            balanceDateEpochSeconds = null,
            lastSeenAtEpochMillis = 1L,
        )
}
