package com.dwk.flowmoney

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

data class MainUiState(
    val isLoading: Boolean = true,
    val sortedTransactions: List<Transaction> = emptyList(),
    val recentTransactions: List<Transaction> = emptyList(),
    val rangeTransactions: List<Transaction> = emptyList(),
    val metrics: DashboardMetrics = DashboardMetrics(spentCents = 0, incomeCents = 0, transactionCount = 0),
    val dateRange: DashboardDateRange = DashboardAnalytics.rangeFor(ChartRangeMode.Week, YearMonth.now()),
    val chartRangeMode: ChartRangeMode = ChartRangeMode.Week,
    val selectedMonth: YearMonth = YearMonth.now(),
    val availableMonths: List<YearMonth> = listOf(YearMonth.now()),
    val dailySpending: List<DailySpend> = emptyList(),
    val categoryTotals: List<CategoryTotal> = emptyList(),
    val suggestionHistory: TransactionSuggestionHistory = TransactionSuggestionHistory.Empty,
    val simpleFin: SimpleFinUiState = SimpleFinUiState(),
)

data class SimpleFinUiState(
    val profile: SimpleFinProfileEntity? = null,
    val accounts: List<SimpleFinAccountEntity> = emptyList(),
)

class MainViewModel(
    private val repository: TransactionGateway,
    private val simpleFinRepository: SimpleFinSyncRepository,
    private val simpleFinAccounts: Flow<List<SimpleFinAccountEntity>>,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val initializationReady = MutableStateFlow(false)
    private val initializationErrors = Channel<String>(Channel.BUFFERED)
    private val chartRangeMode = MutableStateFlow(ChartRangeMode.Week)
    private val selectedMonth = MutableStateFlow(YearMonth.now())
    private val transactionSnapshots = repository.transactions
        .map { transactions ->
            TransactionSnapshot(
                sortedTransactions = transactions,
                recentTransactions = transactions.take(5),
                suggestionHistory = TransactionSuggestions.historyFromNewestFirst(transactions),
            )
        }
        .flowOn(defaultDispatcher)
    private val dashboardState = combine(transactionSnapshots, chartRangeMode, selectedMonth) { snapshot, mode, requestedMonth ->
        val today = LocalDate.now()
        val sorted = snapshot.sortedTransactions
        val availableMonths = DashboardAnalytics.availableMonths(transactions = sorted, today = today)
        val month = requestedMonth.takeIf { it in availableMonths } ?: availableMonths.first()
        val range = DashboardAnalytics.rangeFor(mode = mode, selectedMonth = month, today = today)
        val ranged = DashboardAnalytics.filterTransactions(sorted, range)
        DashboardUiState(
            sortedTransactions = sorted,
            recentTransactions = snapshot.recentTransactions,
            rangeTransactions = ranged,
            metrics = DashboardAnalytics.metrics(ranged),
            dateRange = range,
            chartRangeMode = mode,
            selectedMonth = month,
            availableMonths = availableMonths,
            dailySpending = DashboardAnalytics.dailySpending(ranged, range),
            categoryTotals = DashboardAnalytics.categoryTotals(ranged),
            suggestionHistory = snapshot.suggestionHistory,
        )
    }.flowOn(defaultDispatcher)
    private val simpleFinState = combine(simpleFinRepository.profile, simpleFinAccounts) { profile, accounts ->
        SimpleFinUiState(profile = profile, accounts = accounts)
    }

    val initializationErrorEvents = initializationErrors.receiveAsFlow()

    val uiState = flow {
        initializationReady.first { it }
        emitAll(
            combine(dashboardState, simpleFinState) { dashboard, simpleFin ->
                MainUiState(
                    isLoading = false,
                    sortedTransactions = dashboard.sortedTransactions,
                    recentTransactions = dashboard.recentTransactions,
                    rangeTransactions = dashboard.rangeTransactions,
                    metrics = dashboard.metrics,
                    dateRange = dashboard.dateRange,
                    chartRangeMode = dashboard.chartRangeMode,
                    selectedMonth = dashboard.selectedMonth,
                    availableMonths = dashboard.availableMonths,
                    dailySpending = dashboard.dailySpending,
                    categoryTotals = dashboard.categoryTotals,
                    suggestionHistory = dashboard.suggestionHistory,
                    simpleFin = simpleFin,
                )
            },
        )
    }
        .flowOn(defaultDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    val isInitializationReady: Boolean
        get() = initializationReady.value

    fun reportInitializationComplete(errorMessage: String? = null) {
        if (initializationReady.compareAndSet(expect = false, update = true)) {
            errorMessage?.let(initializationErrors::trySend)
        }
    }

    fun setChartRangeMode(mode: ChartRangeMode) {
        chartRangeMode.value = mode
    }

    fun setSelectedMonth(month: YearMonth) {
        selectedMonth.value = month
    }

    suspend fun upsert(transaction: Transaction) {
        repository.upsert(transaction)
    }

    suspend fun delete(id: String) {
        repository.delete(id)
    }

    suspend fun importCsv(csv: String): Int {
        val transactions = withContext(defaultDispatcher) { CsvCodec.decode(csv) }
        return repository.importTransactions(transactions)
    }

    suspend fun importTrustedLegacyCsv(csv: String): Int {
        val transactions = withContext(defaultDispatcher) { CsvCodec.decode(csv) }
        return repository.importTrustedLegacyTransactions(transactions)
    }

    suspend fun exportCsv(): String {
        val transactions = repository.load()
        return withContext(defaultDispatcher) {
            CsvCodec.encode(transactions)
        }
    }

    suspend fun connectSimpleFin(setupToken: String): SimpleFinSyncResult {
        val token = setupToken.trim()
        if (token.isBlank()) return SimpleFinSyncResult.Failure("Paste a setup token")
        return withContext(ioDispatcher) { simpleFinRepository.connect(token) }
    }

    suspend fun syncSimpleFinNow(): SimpleFinSyncResult {
        if (simpleFinRepository.profile.first()?.isPaused == true) {
            return SimpleFinSyncResult.Failure("Reconnect SimpleFIN to sync")
        }
        return withContext(ioDispatcher) { simpleFinRepository.syncNow() }
    }

    suspend fun syncSimpleFinIfStale(): SimpleFinSyncResult? {
        val profile = simpleFinRepository.profile.first()
        if (profile == null || profile.isPaused) return null
        return withContext(ioDispatcher) { simpleFinRepository.syncIfStale() }
    }

    suspend fun updateAutomaticSyncsPerDay(count: Int) {
        withContext(ioDispatcher) { simpleFinRepository.updateAutomaticSyncsPerDay(count) }
    }

    suspend fun disconnectSimpleFin() {
        withContext(ioDispatcher) { simpleFinRepository.disconnect() }
    }

    class Factory(
        private val repository: TransactionGateway,
        private val simpleFinRepository: SimpleFinSyncRepository,
        private val simpleFinAccounts: Flow<List<SimpleFinAccountEntity>>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(repository, simpleFinRepository, simpleFinAccounts) as T
            }
            error("Unknown ViewModel: ${modelClass.name}")
        }
    }
}

private data class TransactionSnapshot(
    val sortedTransactions: List<Transaction>,
    val recentTransactions: List<Transaction>,
    val suggestionHistory: TransactionSuggestionHistory,
)

private data class DashboardUiState(
    val sortedTransactions: List<Transaction>,
    val recentTransactions: List<Transaction>,
    val rangeTransactions: List<Transaction>,
    val metrics: DashboardMetrics,
    val dateRange: DashboardDateRange,
    val chartRangeMode: ChartRangeMode,
    val selectedMonth: YearMonth,
    val availableMonths: List<YearMonth>,
    val dailySpending: List<DailySpend>,
    val categoryTotals: List<CategoryTotal>,
    val suggestionHistory: TransactionSuggestionHistory,
)
