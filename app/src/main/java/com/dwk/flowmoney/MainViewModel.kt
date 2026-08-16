package com.dwk.flowmoney

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

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
    /** A claim is staged, but its initial sync has not completed. */
    val isConnectionPending: Boolean = false,
)

enum class PendingRangeResetStatus {
    Available,
    Restoring,
    RestoreFailed,
    Restored,
}

data class PendingRangeResetState(
    val id: Long,
    val range: PennyLocalDateRange,
    val zoneId: ZoneId,
    val count: TransactionRangeCount,
    val status: PendingRangeResetStatus,
)

enum class PendingRangeResetRestoreResult {
    Restored,
    Failed,
    Busy,
    AlreadyHandled,
}

class TransactionMutationBusyException : IllegalStateException("Another transaction mutation is already running")

class PendingRangeResetExistsException : IllegalStateException("A range reset is already pending")

class SimpleFinResetRangeOutOfWindowException : IllegalArgumentException("Range is outside SimpleFIN's current 45-day window")

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
    private val mutationMutex = Mutex()
    private val mutableMutationBusy = MutableStateFlow(false)
    private val pendingRangeResetLock = Any()
    private val nextRangeResetId = AtomicLong()
    private val mutablePendingRangeReset = MutableStateFlow<PendingRangeResetState?>(null)
    private var pendingRangeResetRecord: PendingRangeResetRecord? = null
    private val selectedMonth = MutableStateFlow(YearMonth.now())
    private val pendingConnectionRecovery =
        viewModelScope.async(ioDispatcher) {
            simpleFinRepository.recoverPendingConnection()
        }
    private val transactionSnapshots =
        repository.transactions
            .map { transactions ->
                TransactionSnapshot(
                    sortedTransactions = transactions,
                    recentTransactions = transactions.take(5),
                    suggestionHistory = TransactionSuggestions.historyFromNewestFirst(transactions),
                )
            }.flowOn(defaultDispatcher)
    private val dashboardState =
        combine(transactionSnapshots, chartRangeMode, selectedMonth) { snapshot, mode, requestedMonth ->
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
    private val simpleFinState =
        combine(
            simpleFinRepository.profile,
            simpleFinAccounts,
            simpleFinRepository.pendingConnectionState,
        ) { profile, accounts, pendingState ->
            SimpleFinUiState(
                profile = profile,
                accounts = accounts,
                isConnectionPending = pendingState == SimpleFinPendingConnectionState.RETRY_AVAILABLE,
            )
        }

    val initializationErrorEvents = initializationErrors.receiveAsFlow()
    val mutationBusy: StateFlow<Boolean> = mutableMutationBusy.asStateFlow()
    val pendingRangeReset: StateFlow<PendingRangeResetState?> = mutablePendingRangeReset.asStateFlow()
    val unreviewedCount: Flow<Int> = repository.unreviewedCount
    val unreviewedTransactions: Flow<List<Transaction>> = repository.unreviewedTransactions
    val merchantRules: Flow<List<MerchantRuleEntity>> = repository.merchantRules

    val uiState =
        flow {
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
        }.flowOn(defaultDispatcher)
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
        mutateTransactions { repository.upsert(transaction) }
    }

    suspend fun delete(id: String) {
        mutateTransactions { repository.delete(id) }
    }

    suspend fun getUnreviewedTransactions(): List<Transaction> = repository.getUnreviewedTransactions()

    suspend fun categorizeAndReview(
        ids: List<String>,
        category: String,
    ): ReviewUndoToken = mutateTransactions { repository.categorizeAndReview(ids, category) }

    suspend fun acceptAsOther(ids: List<String>): ReviewUndoToken = mutateTransactions { repository.acceptAsOther(ids) }

    suspend fun restoreReview(token: ReviewUndoToken) {
        mutateTransactions { repository.restoreReview(token) }
    }

    suspend fun getMerchantRules(): List<MerchantRuleEntity> = repository.getMerchantRules()

    suspend fun deleteMerchantRule(normalizedKey: String): MerchantRuleEntity? =
        mutateTransactions { repository.deleteMerchantRule(normalizedKey) }

    suspend fun saveAndApplyMerchantRule(
        originatingTransactionId: String,
        category: String,
        merchantOverride: String?,
        overwriteConflict: Boolean = false,
    ): MerchantRuleSaveResult =
        mutateTransactions {
            repository.saveAndApplyMerchantRule(
                originatingTransactionId,
                category,
                merchantOverride,
                overwriteConflict,
            )
        }

    suspend fun undoMerchantRuleSave(token: MerchantRuleUndoToken) {
        mutateTransactions { repository.undoMerchantRuleSave(token) }
    }

    suspend fun countRange(
        range: PennyLocalDateRange,
        zoneId: ZoneId,
    ): TransactionRangeCount = repository.countRange(range, zoneId)

    suspend fun resetRange(
        range: PennyLocalDateRange,
        zoneId: ZoneId,
        expectedCount: TransactionRangeCount,
        clock: Clock = Clock.system(zoneId),
    ): PendingRangeResetState {
        if (!isSimpleFinResetRangeCurrent(range, clock, zoneId)) {
            throw SimpleFinResetRangeOutOfWindowException()
        }
        return mutateTransactions {
            synchronized(pendingRangeResetLock) {
                if (pendingRangeResetRecord != null) throw PendingRangeResetExistsException()
            }
            val snapshot = repository.resetRange(range, zoneId, expectedCount)
            val state =
                PendingRangeResetState(
                    id = nextRangeResetId.incrementAndGet(),
                    range = range,
                    zoneId = zoneId,
                    count = snapshot.count,
                    status = PendingRangeResetStatus.Available,
                )
            synchronized(pendingRangeResetLock) {
                check(pendingRangeResetRecord == null) { "A range reset became pending during reset" }
                pendingRangeResetRecord = PendingRangeResetRecord(state = state, snapshot = snapshot)
                mutablePendingRangeReset.value = state
            }
            state
        }
    }

    suspend fun refreshWidgetsAfterRangeReset(
        id: Long,
        refresh: suspend () -> Unit,
    ): Boolean {
        val claimed =
            synchronized(pendingRangeResetLock) {
                val record = pendingRangeResetRecord
                if (
                    record == null ||
                    record.state.id != id ||
                    record.resetWidgetRefreshCompleted ||
                    record.resetWidgetRefreshClaimed
                ) {
                    false
                } else {
                    record.resetWidgetRefreshClaimed = true
                    true
                }
            }
        if (!claimed) return false
        withContext(NonCancellable) {
            try {
                refresh()
            } finally {
                synchronized(pendingRangeResetLock) {
                    pendingRangeResetRecord
                        ?.takeIf { it.state.id == id }
                        ?.let { record ->
                            record.resetWidgetRefreshClaimed = false
                            record.resetWidgetRefreshCompleted = true
                        }
                }
            }
        }
        return true
    }

    suspend fun restorePendingRangeReset(id: Long): PendingRangeResetRestoreResult {
        var immediateResult: PendingRangeResetRestoreResult? = null
        val operation: Deferred<PendingRangeResetRestoreResult>? =
            synchronized(pendingRangeResetLock) {
                val record = pendingRangeResetRecord
                when {
                    record == null || record.state.id != id -> {
                        immediateResult = PendingRangeResetRestoreResult.AlreadyHandled
                        null
                    }

                    record.state.status == PendingRangeResetStatus.Restored -> {
                        immediateResult = PendingRangeResetRestoreResult.Restored
                        null
                    }

                    record.restoreOperation != null -> {
                        record.restoreOperation
                    }

                    !mutationMutex.tryLock() -> {
                        immediateResult = PendingRangeResetRestoreResult.Busy
                        null
                    }

                    else -> {
                        mutableMutationBusy.value = true
                        updatePendingRangeResetStatusLocked(record, PendingRangeResetStatus.Restoring)
                        viewModelScope
                            .async(ioDispatcher, start = CoroutineStart.LAZY) {
                                try {
                                    check(repository.restoreRange(record.snapshot) == record.snapshot.count) {
                                        "Range restore count did not match its snapshot"
                                    }
                                    synchronized(pendingRangeResetLock) {
                                        pendingRangeResetRecord
                                            ?.takeIf { it === record }
                                            ?.let { updatePendingRangeResetStatusLocked(it, PendingRangeResetStatus.Restored) }
                                    }
                                    PendingRangeResetRestoreResult.Restored
                                } catch (cancelled: CancellationException) {
                                    synchronized(pendingRangeResetLock) {
                                        pendingRangeResetRecord
                                            ?.takeIf { it === record }
                                            ?.let {
                                                it.restoreOperation = null
                                                updatePendingRangeResetStatusLocked(it, PendingRangeResetStatus.RestoreFailed)
                                            }
                                    }
                                    throw cancelled
                                } catch (_: Throwable) {
                                    synchronized(pendingRangeResetLock) {
                                        pendingRangeResetRecord
                                            ?.takeIf { it === record }
                                            ?.let {
                                                it.restoreOperation = null
                                                updatePendingRangeResetStatusLocked(it, PendingRangeResetStatus.RestoreFailed)
                                            }
                                    }
                                    PendingRangeResetRestoreResult.Failed
                                } finally {
                                    mutableMutationBusy.value = false
                                    mutationMutex.unlock()
                                }
                            }.also {
                                record.restoreOperation = it
                                it.start()
                            }
                    }
                }
            }
        return immediateResult ?: checkNotNull(operation).await()
    }

    fun dismissPendingRangeReset(id: Long): Boolean =
        synchronized(pendingRangeResetLock) {
            val record = pendingRangeResetRecord
            if (
                record == null ||
                record.state.id != id ||
                record.state.status == PendingRangeResetStatus.Restoring ||
                record.state.status == PendingRangeResetStatus.Restored
            ) {
                false
            } else {
                pendingRangeResetRecord = null
                mutablePendingRangeReset.value = null
                true
            }
        }

    suspend fun completePendingRangeResetRestore(
        id: Long,
        refresh: suspend () -> Unit,
    ): Boolean {
        val claimed =
            synchronized(pendingRangeResetLock) {
                val record = pendingRangeResetRecord
                if (
                    record == null ||
                    record.state.id != id ||
                    record.state.status != PendingRangeResetStatus.Restored ||
                    record.restoreWidgetRefreshClaimed
                ) {
                    false
                } else {
                    record.restoreWidgetRefreshClaimed = true
                    true
                }
            }
        if (!claimed) return false
        withContext(NonCancellable) {
            try {
                refresh()
            } finally {
                synchronized(pendingRangeResetLock) {
                    if (pendingRangeResetRecord?.state?.id == id) {
                        pendingRangeResetRecord = null
                        mutablePendingRangeReset.value = null
                    }
                }
            }
        }
        return true
    }

    suspend fun importCsv(csv: String): Int {
        val transactions = withContext(defaultDispatcher) { CsvCodec.decode(csv) }
        return mutateTransactions { repository.importTransactions(transactions) }
    }

    suspend fun importTrustedLegacyCsv(csv: String): Int {
        val transactions = withContext(defaultDispatcher) { CsvCodec.decode(csv) }
        return mutateTransactions { repository.importTrustedLegacyTransactions(transactions) }
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
        return withContext(ioDispatcher) {
            pendingConnectionRecovery.await()
            simpleFinRepository.connect(token)
        }
    }

    suspend fun retryPendingSimpleFinConnection(): SimpleFinSyncResult =
        withContext(ioDispatcher) {
            pendingConnectionRecovery.await()
            simpleFinRepository.retryPendingConnection()
        }

    suspend fun cancelPendingSimpleFinConnection() {
        withContext(ioDispatcher) {
            pendingConnectionRecovery.await()
            simpleFinRepository.startOverPendingConnection()
        }
    }

    suspend fun syncSimpleFinNow(): SimpleFinSyncResult =
        withContext(ioDispatcher) {
            pendingConnectionRecovery.await()
            if (simpleFinRepository.profile.first()?.isPaused == true) {
                return@withContext SimpleFinSyncResult.Failure("Reconnect SimpleFIN to sync")
            }
            simpleFinRepository.syncNow()
        }

    suspend fun syncSimpleFinIfStale(): SimpleFinSyncResult? =
        withContext(ioDispatcher) {
            pendingConnectionRecovery.await()
            if (simpleFinRepository.pendingConnectionState.value == SimpleFinPendingConnectionState.RETRY_AVAILABLE) {
                return@withContext null
            }
            val profile = simpleFinRepository.profile.first()
            if (profile == null || profile.isPaused) return@withContext null
            simpleFinRepository.syncIfStale()
        }

    suspend fun updateAutomaticSyncsPerDay(count: Int) {
        withContext(ioDispatcher) {
            pendingConnectionRecovery.await()
            simpleFinRepository.updateAutomaticSyncsPerDay(count)
        }
    }

    suspend fun disconnectSimpleFin() {
        withContext(ioDispatcher) {
            pendingConnectionRecovery.await()
            simpleFinRepository.disconnect()
        }
    }

    private suspend fun <T> mutateTransactions(block: suspend () -> T): T {
        if (!mutationMutex.tryLock()) throw TransactionMutationBusyException()
        mutableMutationBusy.value = true
        return try {
            block()
        } finally {
            mutableMutationBusy.value = false
            mutationMutex.unlock()
        }
    }

    private fun updatePendingRangeResetStatusLocked(
        record: PendingRangeResetRecord,
        status: PendingRangeResetStatus,
    ) {
        record.state = record.state.copy(status = status)
        mutablePendingRangeReset.value = record.state
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

private data class PendingRangeResetRecord(
    var state: PendingRangeResetState,
    val snapshot: TransactionRangeResetSnapshot,
    var resetWidgetRefreshClaimed: Boolean = false,
    var resetWidgetRefreshCompleted: Boolean = false,
    var restoreWidgetRefreshClaimed: Boolean = false,
    var restoreOperation: Deferred<PendingRangeResetRestoreResult>? = null,
)

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
