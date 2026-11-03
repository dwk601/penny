package com.dwk.flowmoney

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {
    private val openAddSheetRequests = MutableStateFlow(0)
    private val openOverviewRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        if (savedInstanceState == null) {
            requestAddSheetFrom(intent)
            requestOverviewFrom(intent)
        }
        val db = FlowMoneyDatabase.get(applicationContext)
        val repository = TransactionRepository(db.transactionDao())
        val simpleFinRepository = SimpleFinSyncRepository(applicationContext)
        val viewModel =
            ViewModelProvider(
                this,
                MainViewModel.Factory(repository, simpleFinRepository, db.simpleFinDao().observeAccounts()),
            ).get(MainViewModel::class.java)
        migrateLegacyPreferencesIfNeeded(viewModel)
        setContent {
            val openAddSheetRequest by openAddSheetRequests.collectAsStateWithLifecycle()
            val openOverviewRequest by openOverviewRequests.collectAsStateWithLifecycle()
            FlowMoneyTheme {
                FlowMoneyApp(
                    viewModel = viewModel,
                    openAddSheetRequest = openAddSheetRequest,
                    openOverviewRequest = openOverviewRequest,
                )
            }
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestAddSheetFrom(intent)
        requestOverviewFrom(intent)
    }

    private fun migrateLegacyPreferencesIfNeeded(viewModel: MainViewModel) {
        if (viewModel.isInitializationReady) return
        val preferences = getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_ROOM_MIGRATED, false)) {
            viewModel.reportInitializationComplete()
            return
        }

        lifecycleScope.launch {
            try {
                val legacyCsv = preferences.getString(KEY_TRANSACTIONS, "").orEmpty()
                if (legacyCsv.isNotBlank()) {
                    viewModel.importTrustedLegacyCsv(legacyCsv)
                    bestEffortWidgetRefresh { refreshPennyWidgets(applicationContext) }
                }
                check(
                    preferences
                        .edit()
                        .putBoolean(KEY_ROOM_MIGRATED, true)
                        .commit(),
                ) { "Could not record legacy migration" }
                viewModel.reportInitializationComplete()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                viewModel.reportInitializationComplete(
                    "Saved transaction migration could not be completed; it will retry next launch.",
                )
            }
        }
    }

    private fun requestAddSheetFrom(intent: Intent?) {
        if (intent?.getBooleanExtra(PennyWidgetProvider.EXTRA_OPEN_ADD_TRANSACTION, false) == true) {
            openAddSheetRequests.value = openAddSheetRequests.value + 1
            intent?.removeExtra(PennyWidgetProvider.EXTRA_OPEN_ADD_TRANSACTION)
        }
    }

    private fun requestOverviewFrom(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_OVERVIEW, false) == true) {
            openOverviewRequests.value = openOverviewRequests.value + 1
            intent?.removeExtra(EXTRA_OPEN_OVERVIEW)
        }
    }

    private companion object {
        const val KEY_PREFS = "flow_money"
        const val KEY_TRANSACTIONS = "transactions_csv"
        const val KEY_ROOM_MIGRATED = "room_migrated"
    }
}

internal const val EXTRA_OPEN_OVERVIEW = "com.dwk.flowmoney.OPEN_OVERVIEW"

internal fun csvAdvertisedLength(probe: () -> Long): Long = runCatching(probe).getOrDefault(-1L)

private suspend fun refreshPennyWidgets(context: Context) {
    PennyWidgetProvider.refreshAll(context)
}

internal suspend fun bestEffortWidgetRefresh(refresh: suspend () -> Unit) {
    try {
        refresh()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        // Widget updates are best-effort after transaction persistence has committed.
    }
}

internal data class DashboardNavigationState(
    val selectedTab: DashboardTab = DashboardTab.Overview,
    val coldStartRoutingHandled: Boolean = false,
    val restoredNavigation: Boolean = false,
    val userSelectedBeforeLoading: Boolean = false,
    val externalRouteHandled: Boolean = false,
    val reviewOriginTab: DashboardTab = DashboardTab.Overview,
)

internal val DashboardNavigationStateSaver =
    Saver<DashboardNavigationState, Any>(
        save = { state ->
            listOf(
                state.selectedTab.name,
                state.coldStartRoutingHandled,
                state.userSelectedBeforeLoading,
                state.externalRouteHandled,
                state.reviewOriginTab.name,
            )
        },
        restore = { saved ->
            // The String branch restores process state written before Review navigation existed.
            val values = saved as? List<*>
            val selectedName = (values?.getOrNull(0) as? String) ?: (saved as? String)
            DashboardNavigationState(
                selectedTab = DashboardTab.entries.firstOrNull { it.name == selectedName } ?: DashboardTab.Overview,
                coldStartRoutingHandled = (values?.getOrNull(1) as? Boolean) ?: true,
                restoredNavigation = true,
                userSelectedBeforeLoading = (values?.getOrNull(2) as? Boolean) ?: false,
                externalRouteHandled = (values?.getOrNull(3) as? Boolean) ?: false,
                reviewOriginTab = DashboardTab.entries.firstOrNull { it.name == values?.getOrNull(4) && it != DashboardTab.Review } ?: DashboardTab.Overview,
            )
        },
    )

internal fun completeColdStartNavigation(
    state: DashboardNavigationState,
    isLoading: Boolean,
): DashboardNavigationState {
    if (isLoading || state.coldStartRoutingHandled) return state
    val isTrueColdStart =
        !state.restoredNavigation &&
            !state.userSelectedBeforeLoading &&
            !state.externalRouteHandled
    return state.copy(
        selectedTab = if (isTrueColdStart) DashboardTab.Overview else state.selectedTab,
        coldStartRoutingHandled = true,
    )
}

internal enum class DataOperation(
    val label: String,
) {
    Import("Importing CSV"),
    Export("Exporting CSV"),
    Connect("Connecting bank"),
    StartOver("Starting over"),
    Sync("Syncing bank"),
    UpdateAutomaticSyncs("Saving sync schedule"),
    DeleteMerchantRule("Deleting merchant rule"),
    Disconnect("Disconnecting bank"),
    ResetDays("Resetting days"),
    RestoreDays("Restoring reset days"),
}

internal fun canDismissDataSheet(
    isHiding: Boolean,
    operation: DataOperation?,
): Boolean = !isHiding || operation == null

internal fun pickerResultOperation(
    uri: Uri?,
    operation: DataOperation,
): DataOperation? = uri?.let { operation }

private data class PendingMerchantRuleConflictRequest(
    val originatingTransactionId: String,
    val category: String,
    val merchantOverride: String?,
    val existingRule: MerchantRuleEntity,
    val proposedRule: MerchantRuleEntity,
    val editorTransaction: Transaction? = null,
    val editorDraft: EditorDraft? = null,
)

private data class PendingDeleteRequest(
    val transaction: Transaction,
    val closeEditor: Boolean,
)

private data class ResetDaysRequest(
    val range: PennyLocalDateRange,
    val zoneId: ZoneId,
    val count: TransactionRangeCount,
)

internal fun resetDaysRangeLabel(range: PennyLocalDateRange): String {
    val start = range.startInclusive
    val end = range.lastInclusive
    val month = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)
    return when {
        start == end -> {
            "${start.format(month)} ${start.dayOfMonth}, ${start.year}"
        }

        start.year == end.year && start.month == end.month -> {
            "${start.format(month)} ${start.dayOfMonth}–${end.dayOfMonth}, ${start.year}"
        }

        start.year == end.year -> {
            "${start.format(month)} ${start.dayOfMonth}–${end.format(month)} ${end.dayOfMonth}, ${start.year}"
        }

        else -> {
            "${start.format(month)} ${start.dayOfMonth}, ${start.year}–" +
                "${end.format(month)} ${end.dayOfMonth}, ${end.year}"
        }
    }
}

internal fun resetDaysCountLabel(count: TransactionRangeCount): String {
    val affected = count.affectedCount
    val itemLabel = if (affected == 1L) "item" else "items"
    val transactionLabel = if (count.transactionCount == 1L) "transaction" else "transactions"
    val deletionLabel = if (count.tombstoneCount == 1L) "deleted SimpleFIN record" else "deleted SimpleFIN records"
    return "$affected $itemLabel affected: ${count.transactionCount} $transactionLabel + " +
        "${count.tombstoneCount} $deletionLabel"
}

internal data class EditorDraft(
    val id: String?,
    val occurredAtEpochMillis: Long,
    val merchant: String,
    val amount: String,
    val isExpense: Boolean,
    val category: String,
    val note: String,
    val recurringIntervalName: String,
    val source: String,
    val accountKey: String?,
    val accountName: String?,
    val reviewedAtEpochMillis: Long?,
    val providerDescription: String?,
    val merchantOverride: String?,
    val providerMerchant: String?,
    val flowKind: FlowKind,
    val flowKindOverride: FlowKind?,
    val transactedAtEpochMillis: Long? = null,
) {
    val effectiveFlowKind: FlowKind
        get() = flowKindOverride ?: flowKind
}

private val EditorDraftSaver =
    androidx.compose.runtime.saveable.listSaver<EditorDraft, Any>(
        save = { draft -> saveEditorDraft(draft) },
        restore = ::restoreEditorDraft,
    )

internal fun saveEditorDraft(draft: EditorDraft): List<Any> =
    listOf(
        draft.id.orEmpty(),
        draft.occurredAtEpochMillis,
        draft.merchant,
        draft.amount,
        draft.isExpense,
        draft.category,
        draft.note,
        draft.recurringIntervalName,
        draft.source,
        draft.accountKey.orEmpty(),
        draft.accountName.orEmpty(),
        draft.reviewedAtEpochMillis ?: Long.MIN_VALUE,
        draft.providerDescription != null,
        draft.providerDescription.orEmpty(),
        draft.merchantOverride != null,
        draft.merchantOverride.orEmpty(),
        draft.providerMerchant != null,
        draft.providerMerchant.orEmpty(),
        draft.flowKind.name,
        draft.flowKindOverride?.name.orEmpty(),
        draft.transactedAtEpochMillis ?: Long.MIN_VALUE,
    )

/** Size-checked restoration keeps process state written by older app versions valid. */
internal fun restoreEditorDraft(values: List<Any>): EditorDraft {
    val fallback = newEditorDraft()
    val source = values.getOrNull(8) as? String ?: fallback.source
    val merchant = values.getOrNull(2) as? String ?: fallback.merchant
    val providerDescriptionPresent = values.getOrNull(12) as? Boolean ?: false
    val merchantOverridePresent = values.getOrNull(14) as? Boolean ?: false
    val providerMerchantPresent = values.getOrNull(16) as? Boolean ?: false
    return EditorDraft(
        id = (values.getOrNull(0) as? String)?.ifBlank { null },
        occurredAtEpochMillis = values.getOrNull(1) as? Long ?: fallback.occurredAtEpochMillis,
        merchant = merchant,
        amount = values.getOrNull(3) as? String ?: fallback.amount,
        isExpense = values.getOrNull(4) as? Boolean ?: fallback.isExpense,
        category = values.getOrNull(5) as? String ?: fallback.category,
        note = values.getOrNull(6) as? String ?: fallback.note,
        recurringIntervalName = values.getOrNull(7) as? String ?: fallback.recurringIntervalName,
        source = source,
        accountKey = (values.getOrNull(9) as? String)?.ifBlank { null },
        accountName = (values.getOrNull(10) as? String)?.ifBlank { null },
        reviewedAtEpochMillis =
            (values.getOrNull(11) as? Long)
                ?.takeUnless { it == Long.MIN_VALUE },
        providerDescription =
            (values.getOrNull(13) as? String)
                ?.takeIf { providerDescriptionPresent },
        merchantOverride =
            (values.getOrNull(15) as? String)
                ?.takeIf { merchantOverridePresent },
        providerMerchant =
            (values.getOrNull(17) as? String)
                ?.takeIf { providerMerchantPresent }
                ?: merchant.takeIf { source == "simplefin" && values.size <= 11 },
        flowKind =
            (values.getOrNull(18) as? String)
                ?.let { saved -> FlowKind.entries.firstOrNull { it.name == saved } }
                ?: fallback.flowKind,
        flowKindOverride =
            (values.getOrNull(19) as? String)
                ?.let { saved -> FlowKind.entries.firstOrNull { it.name == saved } },
        transactedAtEpochMillis =
            (values.getOrNull(20) as? Long)?.takeUnless { it == Long.MIN_VALUE },
    )
}

internal fun newEditorDraft(): EditorDraft =
    EditorDraft(
        id = null,
        occurredAtEpochMillis = LocalDateTime.now().toEpochMillis(),
        merchant = "",
        amount = "",
        isExpense = true,
        category = CategoryCatalog.categoriesFor(true).first(),
        note = "",
        recurringIntervalName = "",
        source = "local",
        accountKey = null,
        accountName = null,
        reviewedAtEpochMillis = null,
        providerDescription = null,
        merchantOverride = null,
        providerMerchant = null,
        flowKind = FlowKind.NORMAL,
        flowKindOverride = null,
        transactedAtEpochMillis = null,
    )

internal fun widgetQuickAddDraft(suggestionHistory: TransactionSuggestionHistory): EditorDraft {
    val learnedExpenseCategory = suggestionHistory.expenseCategories.firstOrNull { it.isUsed }?.label
    return newEditorDraft().copy(
        merchant = "",
        amount = "",
        isExpense = true,
        category = learnedExpenseCategory ?: CategoryCatalog.categoriesFor(isExpense = true).first(),
    )
}

internal fun Transaction.toEditorDraft() =
    EditorDraft(
        id = id,
        occurredAtEpochMillis = occurredAtEpochMillis,
        merchant = merchant,
        amount = MoneyFormatter.formatAmountText(cents),
        isExpense = cents < 0,
        category = category,
        note = note,
        recurringIntervalName = recurringInterval?.name.orEmpty(),
        source = source,
        accountKey = accountKey,
        accountName = accountName,
        reviewedAtEpochMillis = reviewedAtEpochMillis,
        providerDescription = providerDescription,
        merchantOverride = merchantOverride,
        providerMerchant = providerMerchant,
        flowKind = flowKind,
        flowKindOverride = flowKindOverride,
        transactedAtEpochMillis = transactedAtEpochMillis,
    )

internal fun EditorDraft.prepareForEditorSave(
    candidateId: String,
    nowEpochMillis: Long = System.currentTimeMillis(),
): EditorDraft =
    copy(
        id = candidateId,
        reviewedAtEpochMillis =
            if (id != null && source == "simplefin") {
                reviewedAtEpochMillis ?: nowEpochMillis
            } else {
                reviewedAtEpochMillis
            },
    )

internal fun EditorDraft.toTransaction(): Transaction {
    val parsedAmount = MoneyFormatter.parseAmountToCents(amount).absoluteValue
    val effectiveMerchant = merchant.trim().ifBlank { category }
    val rawProviderMerchant = providerMerchant ?: effectiveMerchant
    val originalEffectiveMerchant = merchantOverride ?: rawProviderMerchant
    val updatedOverride =
        if (source != "simplefin") {
            null
        } else if (effectiveMerchant == originalEffectiveMerchant) {
            merchantOverride
        } else {
            effectiveMerchant.takeUnless { it == rawProviderMerchant }
        }
    return Transaction(
        id = id ?: UUID.randomUUID().toString(),
        occurredAtEpochMillis = occurredAtEpochMillis,
        merchant = effectiveMerchant,
        category = category,
        note = note.trim(),
        cents = TransactionSuggestions.signedCents(parsedAmount, isExpense),
        recurringInterval = recurringIntervalName.toRecurrenceIntervalOrNull(),
        source = source,
        accountKey = accountKey,
        accountName = accountName,
        reviewedAtEpochMillis = reviewedAtEpochMillis,
        providerDescription = providerDescription,
        merchantOverride = updatedOverride,
        providerMerchant = rawProviderMerchant.takeIf { source == "simplefin" },
        flowKind = flowKind,
        flowKindOverride = flowKindOverride,
        transactedAtEpochMillis = transactedAtEpochMillis,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowMoneyApp(
    viewModel: MainViewModel,
    openAddSheetRequest: Int = 0,
    openOverviewRequest: Int = 0,
    transactionWidgetRefresh: suspend (Context) -> Unit = ::refreshPennyWidgets,
    coldStartSimpleFinSync: suspend () -> SimpleFinSyncResult? = viewModel::syncSimpleFinIfStale,
    connectSimpleFin: suspend (String) -> SimpleFinSyncResult = viewModel::connectSimpleFin,
    retryPendingSimpleFinConnection: suspend () -> SimpleFinSyncResult = viewModel::retryPendingSimpleFinConnection,
    cancelPendingSimpleFinConnection: suspend () -> Unit = viewModel::cancelPendingSimpleFinConnection,
    manualSimpleFinSync: suspend () -> SimpleFinSyncResult = viewModel::syncSimpleFinNow,
    appSnackbarHostState: SnackbarHostState? = null,
    resetDaysClock: Clock = Clock.systemDefaultZone(),
    resetDaysZoneId: ZoneId = ZoneId.systemDefault(),
    syncHealthClock: Clock = Clock.systemDefaultZone(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rememberedAppSnackbarHostState = remember { SnackbarHostState() }
    val snackbarHostState = appSnackbarHostState ?: rememberedAppSnackbarHostState
    val dataSnackbarHostState = remember { SnackbarHostState() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var unreviewedTransactions by remember(viewModel) { mutableStateOf<List<Transaction>>(emptyList()) }
    var merchantRules by remember(viewModel) { mutableStateOf<List<MerchantRuleEntity>>(emptyList()) }
    val viewModelMutationBusy by viewModel.mutationBusy.collectAsStateWithLifecycle()
    val pendingRangeReset by viewModel.pendingRangeReset.collectAsStateWithLifecycle()
    val syncHealthNow = rememberSyncHealthNow(syncHealthClock, uiState.simpleFin.profile)
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var editorSessionId by rememberSaveable { mutableStateOf(0) }
    var showDataSheet by rememberSaveable { mutableStateOf(false) }
    var geminiKeySaved by remember { mutableStateOf(false) }
    var geminiStatus by remember { mutableStateOf(GeminiRunStatus()) }
    var navigationState by rememberSaveable(stateSaver = DashboardNavigationStateSaver) {
        mutableStateOf(DashboardNavigationState())
    }
    var editorDraft by rememberSaveable(stateSaver = EditorDraftSaver) { mutableStateOf(newEditorDraft()) }
    var originalEditorDraft by rememberSaveable(stateSaver = EditorDraftSaver) { mutableStateOf(newEditorDraft()) }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var persistenceBusy by remember { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var pendingWidgetQuickAddAfterDiscard by rememberSaveable { mutableStateOf(false) }
    var lastHandledOpenAddSheetRequest by remember { mutableStateOf(0) }
    var dataOperation by remember { mutableStateOf<DataOperation?>(null) }
    var showDisconnectConfirmation by rememberSaveable { mutableStateOf(false) }
    var resetDaysConfirmation by remember { mutableStateOf<ResetDaysRequest?>(null) }
    var resetDaysFailure by rememberSaveable { mutableStateOf<String?>(null) }
    var resetDaysRecountRequest by rememberSaveable { mutableStateOf(0) }
    var resetDaysReselectRequest by rememberSaveable { mutableStateOf(0) }
    var pendingMerchantRuleConflict by remember { mutableStateOf<PendingMerchantRuleConflictRequest?>(null) }
    var pendingDeleteRequest by remember { mutableStateOf<PendingDeleteRequest?>(null) }
    val selectedTab = navigationState.selectedTab
    val editorDirty = editorDraft != originalEditorDraft
    val editorMutationBusy = persistenceBusy || viewModelMutationBusy
    val latestEditorDirty by rememberUpdatedState(editorDirty)
    val latestEditorMutationBusy by rememberUpdatedState(editorMutationBusy)

    LaunchedEffect(openOverviewRequest) {
        if (openOverviewRequest > 0) {
            navigationState =
                navigationState.copy(
                    selectedTab = DashboardTab.Overview,
                    coldStartRoutingHandled = true,
                    externalRouteHandled = true,
                )
        }
    }

    LaunchedEffect(openAddSheetRequest) {
        if (openAddSheetRequest > 0) {
            navigationState =
                navigationState.copy(
                    coldStartRoutingHandled = true,
                    externalRouteHandled = true,
                )
        }
    }

    LaunchedEffect(viewModel, uiState.isLoading) {
        if (!uiState.isLoading) {
            viewModel.unreviewedTransactions.collect { transactions ->
                unreviewedTransactions = transactions
            }
        }
    }

    LaunchedEffect(viewModel, uiState.isLoading) {
        if (!uiState.isLoading) {
            viewModel.merchantRules.collect { rules -> merchantRules = rules }
        }
    }

    LaunchedEffect(uiState.isLoading) {
        navigationState =
            completeColdStartNavigation(
                state = navigationState,
                isLoading = uiState.isLoading,
            )
    }

    LaunchedEffect(viewModel) {
        viewModel.initializationErrorEvents.collect { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(viewModel, snackbarHostState) {
        while (true) {
            val pending = viewModel.pendingRangeReset.first { it != null } ?: continue
            val id = pending.id
            val rangeLabel = resetDaysRangeLabel(pending.range)
            viewModel.refreshWidgetsAfterRangeReset(id) {
                bestEffortWidgetRefresh { transactionWidgetRefresh(context.applicationContext) }
            }
            var retryMessage: String? = null
            while (true) {
                val current = viewModel.pendingRangeReset.value
                if (current == null || current.id != id) break
                when (current.status) {
                    PendingRangeResetStatus.Restoring -> {
                        viewModel.pendingRangeReset.first {
                            it == null || it.id != id || it.status != PendingRangeResetStatus.Restoring
                        }
                        continue
                    }

                    PendingRangeResetStatus.Restored -> {
                        val completed =
                            viewModel.completePendingRangeResetRestore(id) {
                                bestEffortWidgetRefresh { transactionWidgetRefresh(context.applicationContext) }
                            }
                        if (completed) snackbarHostState.showSnackbar("Restored $rangeLabel")
                        break
                    }

                    PendingRangeResetStatus.RestoreFailed -> {
                        retryMessage = "Could not restore $rangeLabel."
                    }

                    PendingRangeResetStatus.Available -> {
                        Unit
                    }
                }
                val itemLabel = if (current.count.affectedCount == 1L) "item" else "items"
                val result =
                    snackbarHostState.showSnackbar(
                        message = retryMessage ?: "Reset $rangeLabel: ${current.count.affectedCount} $itemLabel removed",
                        actionLabel = if (retryMessage == null) "Undo" else "Retry",
                        withDismissAction = true,
                        duration = SnackbarDuration.Indefinite,
                    )
                if (result != SnackbarResult.ActionPerformed) {
                    if (viewModel.dismissPendingRangeReset(id)) break
                    continue
                }
                retryMessage =
                    when (viewModel.restorePendingRangeReset(id)) {
                        PendingRangeResetRestoreResult.Restored -> {
                            null
                        }

                        PendingRangeResetRestoreResult.Failed -> {
                            "Could not restore $rangeLabel."
                        }

                        PendingRangeResetRestoreResult.Busy -> {
                            "Could not restore $rangeLabel while another transaction change is running."
                        }

                        PendingRangeResetRestoreResult.AlreadyHandled -> {
                            break
                        }
                    }
            }
        }
    }

    LaunchedEffect(openAddSheetRequest, uiState.isLoading) {
        if (!uiState.isLoading && openAddSheetRequest > lastHandledOpenAddSheetRequest) {
            while (lastHandledOpenAddSheetRequest < openAddSheetRequest) {
                lastHandledOpenAddSheetRequest += 1
                if (showSheet && editorDirty) {
                    pendingWidgetQuickAddAfterDiscard = true
                    showDiscardDialog = true
                } else {
                    editorId = null
                    editorDraft = widgetQuickAddDraft(uiState.suggestionHistory)
                    originalEditorDraft = editorDraft
                    editorSessionId += 1
                    showSheet = true
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val result = coldStartSimpleFinSync()
            if (result is SimpleFinSyncResult.Success && (result.inserted > 0 || result.updated > 0)) {
                bestEffortWidgetRefresh { transactionWidgetRefresh(context) }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            // Automatic startup sync is best-effort; do not expose failure details from bank data paths.
        }
    }

    fun deleteWithUndo(request: PendingDeleteRequest) {
        val transaction = request.transaction
        val description = transaction.deleteDescription()
        scope.launch {
            try {
                try {
                    viewModel.delete(transaction.id)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Throwable) {
                    pendingDeleteRequest = null
                    persistenceBusy = false
                    snackbarHostState.showSnackbar("Could not delete $description")
                    return@launch
                }

                pendingDeleteRequest = null
                if (request.closeEditor) showSheet = false
                persistenceBusy = false
                bestEffortWidgetRefresh { transactionWidgetRefresh(context) }
                val result =
                    snackbarHostState.showSnackbar(
                        message = "Deleted $description",
                        actionLabel = "Undo",
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                if (result == SnackbarResult.ActionPerformed) {
                    val restored =
                        try {
                            persistenceBusy = true
                            viewModel.restoreDeletedTransaction(transaction)
                            true
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Throwable) {
                            false
                        } finally {
                            persistenceBusy = false
                        }
                    if (!restored) {
                        snackbarHostState.showSnackbar("Could not restore $description.")
                        return@launch
                    }
                    bestEffortWidgetRefresh { transactionWidgetRefresh(context) }
                }
            } finally {
                persistenceBusy = false
            }
        }
    }

    fun openNewTransactionEditor() {
        editorId = null
        editorDraft = newEditorDraft()
        originalEditorDraft = editorDraft
        editorSessionId += 1
        showSheet = true
    }

    fun openTransactionEditor(transaction: Transaction) {
        editorId = transaction.id
        editorDraft = transaction.toEditorDraft()
        originalEditorDraft = editorDraft
        editorSessionId += 1
        showSheet = true
    }

    fun requestEditorDismissal() {
        if (persistenceBusy || viewModel.mutationBusy.value) return
        if (editorDirty) showDiscardDialog = true else showSheet = false
    }

    fun requestDelete(
        transaction: Transaction,
        closeEditor: Boolean = false,
    ) {
        if (pendingDeleteRequest != null || persistenceBusy || viewModel.mutationBusy.value) return
        pendingDeleteRequest = PendingDeleteRequest(transaction, closeEditor)
    }

    fun confirmDelete() {
        val request = pendingDeleteRequest ?: return
        if (persistenceBusy || viewModel.mutationBusy.value) return
        persistenceBusy = true
        deleteWithUndo(request)
    }

    suspend fun offerReviewUndo(
        message: String,
        undo: suspend () -> Unit,
    ) {
        bestEffortWidgetRefresh { transactionWidgetRefresh(context) }
        val result =
            snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Undo",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
        if (result == SnackbarResult.ActionPerformed) {
            try {
                undo()
                bestEffortWidgetRefresh { transactionWidgetRefresh(context) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                snackbarHostState.showSnackbar("Could not undo review.")
            }
        }
    }

    suspend fun completeMerchantRuleSave(
        request: PendingMerchantRuleConflictRequest,
        applied: MerchantRuleSaveResult.Applied,
    ) {
        val editorTransaction = request.editorTransaction
        if (editorTransaction == null) {
            offerReviewUndo(
                message = "Reviewed as ${request.category} and saved future rule",
                undo = { viewModel.undoMerchantRuleSave(applied.undoToken) },
            )
            return
        }

        val savedDraft = request.editorDraft ?: editorTransaction.toEditorDraft()
        editorId = editorTransaction.id
        editorDraft = savedDraft
        originalEditorDraft = savedDraft
        showSheet = false
        persistenceBusy = false
        offerReviewUndo(
            message = "Saved transaction and future rule",
            undo = { viewModel.undoMerchantRuleSave(applied.undoToken) },
        )
    }

    fun saveMerchantRule(
        originatingTransactionId: String,
        category: String,
        merchantOverride: String?,
        overwriteConflict: Boolean = false,
        editorTransaction: Transaction? = null,
        candidateEditorDraft: EditorDraft? = null,
    ) {
        if (persistenceBusy || viewModel.mutationBusy.value) return
        val isEditorSave = editorTransaction != null
        val reviewedEditorTransaction =
            editorTransaction?.let { transaction ->
                if (transaction.source == "simplefin") {
                    transaction.copy(
                        reviewedAtEpochMillis = transaction.reviewedAtEpochMillis ?: System.currentTimeMillis(),
                    )
                } else {
                    transaction
                }
            }
        val reviewedEditorDraft =
            candidateEditorDraft?.copy(
                reviewedAtEpochMillis = reviewedEditorTransaction?.reviewedAtEpochMillis,
            )
        if (isEditorSave) persistenceBusy = true
        scope.launch {
            try {
                val result =
                    viewModel.saveAndApplyMerchantRule(
                        originatingTransactionId = originatingTransactionId,
                        category = category,
                        merchantOverride = merchantOverride,
                        overwriteConflict = overwriteConflict,
                        editorTransaction = reviewedEditorTransaction,
                    )
                when (result) {
                    is MerchantRuleSaveResult.Applied -> {
                        pendingMerchantRuleConflict = null
                        completeMerchantRuleSave(
                            request =
                                PendingMerchantRuleConflictRequest(
                                    originatingTransactionId = originatingTransactionId,
                                    category = category,
                                    merchantOverride = merchantOverride,
                                    existingRule = result.rule,
                                    proposedRule = result.rule,
                                    editorTransaction = reviewedEditorTransaction,
                                    editorDraft = reviewedEditorDraft,
                                ),
                            applied = result,
                        )
                    }

                    is MerchantRuleSaveResult.Conflict -> {
                        pendingMerchantRuleConflict =
                            PendingMerchantRuleConflictRequest(
                                originatingTransactionId = originatingTransactionId,
                                category = category,
                                merchantOverride = merchantOverride,
                                existingRule = result.existingRule,
                                proposedRule = result.proposedRule,
                                editorTransaction = reviewedEditorTransaction,
                                editorDraft = reviewedEditorDraft,
                            )
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                snackbarHostState.showSnackbar(
                    if (isEditorSave) "Could not save transaction and future rule." else "Could not save future rule.",
                )
            } finally {
                if (isEditorSave) persistenceBusy = false
            }
        }
    }

    fun categorizeAndReview(
        transaction: Transaction,
        category: String,
        useForFuture: Boolean,
    ) {
        if (persistenceBusy || viewModel.mutationBusy.value) return
        if (useForFuture) {
            saveMerchantRule(
                originatingTransactionId = transaction.id,
                category = category,
                merchantOverride = transaction.merchantOverride,
            )
            return
        }
        scope.launch {
            try {
                val token = viewModel.categorizeAndReview(listOf(transaction.id), category)
                offerReviewUndo("Reviewed ${transaction.merchant.ifBlank { "transaction" }} as $category") {
                    viewModel.restoreReview(token)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                snackbarHostState.showSnackbar("Could not review transaction.")
            }
        }
    }

    fun bulkCategorizeAndReview(
        ids: List<String>,
        category: String,
    ) {
        if (ids.isEmpty() || persistenceBusy || viewModel.mutationBusy.value) return
        scope.launch {
            try {
                val token = viewModel.categorizeAndReview(ids, category)
                offerReviewUndo("Categorized ${ids.size} transactions as $category") {
                    viewModel.restoreReview(token)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                snackbarHostState.showSnackbar("Could not review selected transactions.")
            }
        }
    }

    fun bulkAcceptAsOther(ids: List<String>) {
        if (ids.isEmpty() || persistenceBusy || viewModel.mutationBusy.value) return
        scope.launch {
            try {
                val token = viewModel.acceptAsOther(ids)
                offerReviewUndo("Accepted ${ids.size} transactions as Other") {
                    viewModel.restoreReview(token)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                snackbarHostState.showSnackbar("Could not accept selected transactions.")
            }
        }
    }

    fun requestManualSimpleFinSync(hostState: SnackbarHostState = snackbarHostState) {
        if (dataOperation != null) return
        dataOperation = DataOperation.Sync
        scope.launch {
            var message = "Bank sync failed"
            try {
                val result = manualSimpleFinSync()
                if (result is SimpleFinSyncResult.Success && (result.inserted > 0 || result.updated > 0)) {
                    bestEffortWidgetRefresh { transactionWidgetRefresh(context) }
                }
                message = result.snackbarMessage()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                message = "Bank sync failed"
            } finally {
                dataOperation = null
                if (showDataSheet) {
                    geminiStatus = withContext(Dispatchers.IO) { GeminiRunStatusStore(context).load() }
                }
            }
            hostState.showSnackbar(message)
        }
    }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val callbackOperation = pickerResultOperation(uri, DataOperation.Import)
            if (callbackOperation == null) {
                dataOperation = null
                return@rememberLauncherForActivityResult
            }
            val resultUri = uri ?: return@rememberLauncherForActivityResult
            dataOperation = callbackOperation
            scope.launch {
                var message = "Import failed"
                try {
                    val csv =
                        withContext(Dispatchers.IO) {
                            val resolver = context.contentResolver
                            val advertisedLength =
                                csvAdvertisedLength {
                                    resolver.openAssetFileDescriptor(resultUri, "r")?.use { it.length } ?: -1L
                                }
                            StrictUtf8Reader.read(
                                resolver.openInputStream(resultUri) ?: error("Could not open CSV"),
                                5 * 1024 * 1024,
                                advertisedLength,
                            )
                        }
                    val importedCount = viewModel.importCsv(csv)
                    if (importedCount > 0) {
                        bestEffortWidgetRefresh { transactionWidgetRefresh(context) }
                    }
                    message = "Imported $importedCount transactions"
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Throwable) {
                    message = "Import failed"
                } finally {
                    dataOperation = null
                }
                dataSnackbarHostState.showSnackbar(message)
            }
        }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            val callbackOperation = pickerResultOperation(uri, DataOperation.Export)
            if (callbackOperation == null) {
                dataOperation = null
                return@rememberLauncherForActivityResult
            }
            val resultUri = uri ?: return@rememberLauncherForActivityResult
            dataOperation = callbackOperation
            scope.launch {
                var message = "Export failed"
                try {
                    val csv = viewModel.exportCsv()
                    withContext(Dispatchers.IO) {
                        context.contentResolver
                            .openOutputStream(resultUri)
                            ?.bufferedWriter()
                            ?.use { writer -> writer.write(csv) }
                            ?: error("Could not create CSV")
                    }
                    message = "CSV exported"
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Throwable) {
                    message = "Export failed"
                } finally {
                    dataOperation = null
                }
                dataSnackbarHostState.showSnackbar(message)
            }
        }

    BackHandler(enabled = selectedTab == DashboardTab.Review) {
        navigationState = navigationState.copy(selectedTab = navigationState.reviewOriginTab)
    }

    AdaptiveFlowMoneyShell(
        selectedTab = selectedTab,
        reviewOriginTab = navigationState.reviewOriginTab,
        snackbarHostState = snackbarHostState,
        onTabSelected = { tab ->
            navigationState =
                navigationState.copy(
                    selectedTab = tab,
                    userSelectedBeforeLoading =
                        navigationState.userSelectedBeforeLoading || !navigationState.coldStartRoutingHandled,
                )
        },
        onAddTransaction = ::openNewTransactionEditor,
        onData = { showDataSheet = true },
        simpleFin = uiState.simpleFin,
        operation = dataOperation,
        syncHealthNowEpochMillis = syncHealthNow,
        onManualSync = ::requestManualSimpleFinSync,
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) { padding ->
        FlowMoneyScreen(
            uiState = uiState,
            selectedTab = selectedTab,
            onChartRangeModeSelected = viewModel::setChartRangeMode,
            onSelectedMonthChange = viewModel::setSelectedMonth,
            onEdit = ::openTransactionEditor,
            onViewAllTransactions = {
                navigationState = navigationState.copy(selectedTab = DashboardTab.Transactions)
            },
            onDelete = ::requestDelete,
            onAddTransaction = ::openNewTransactionEditor,
            onData = { showDataSheet = true },
            onReview = {
                navigationState =
                    navigationState.copy(
                        selectedTab = DashboardTab.Review,
                        reviewOriginTab = selectedTab.takeUnless { it == DashboardTab.Review } ?: DashboardTab.Overview,
                        coldStartRoutingHandled = true,
                    )
            },
            onCategorizeReview = ::categorizeAndReview,
            onBulkCategorizeReview = ::bulkCategorizeAndReview,
            onBulkAcceptOther = ::bulkAcceptAsOther,
            reviewOperationBusy = editorMutationBusy,
            reviewTransactions = unreviewedTransactions,
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding)
                    .consumeWindowInsets(padding),
        )
    }

    if (showSheet) {
        key(editorSessionId) {
            val editorSheetState =
                rememberModalBottomSheetState(
                    skipPartiallyExpanded = true,
                    confirmValueChange = { target ->
                        if (
                            target == androidx.compose.material3.SheetValue.Hidden &&
                            latestEditorDirty &&
                            !latestEditorMutationBusy
                        ) {
                            showDiscardDialog = true
                            false
                        } else {
                            !latestEditorMutationBusy
                        }
                    },
                )
            ModalBottomSheet(
                onDismissRequest = ::requestEditorDismissal,
                sheetState = editorSheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.testTag("editor_sheet"),
            ) {
                TransactionEditor(
                    transaction = editorId?.let { id -> uiState.sortedTransactions.firstOrNull { it.id == id } },
                    draft = editorDraft,
                    suggestionHistory = uiState.suggestionHistory,
                    onDraftChange = { editorDraft = it },
                    onSave = { transaction ->
                        if (!persistenceBusy && !viewModel.mutationBusy.value) {
                            val candidateId = editorDraft.id ?: transaction.id
                            val candidateDraft = editorDraft.prepareForEditorSave(candidateId)
                            persistenceBusy = true
                            scope.launch {
                                var saveFailureMessage: String? = null
                                try {
                                    try {
                                        viewModel.upsert(candidateDraft.toTransaction())
                                    } catch (failure: CancellationException) {
                                        throw failure
                                    } catch (_: Throwable) {
                                        saveFailureMessage = "Could not save transaction."
                                    }
                                    if (saveFailureMessage == null) {
                                        editorId = candidateId
                                        editorDraft = candidateDraft
                                        originalEditorDraft = candidateDraft
                                        showSheet = false
                                        bestEffortWidgetRefresh { transactionWidgetRefresh(context) }
                                    }
                                } finally {
                                    persistenceBusy = false
                                }
                                saveFailureMessage?.let { snackbarHostState.showSnackbar(it) }
                            }
                        }
                    },
                    onSaveWithFutureRule = { transaction ->
                        val candidateId = editorDraft.id ?: transaction.id
                        val candidateDraft = editorDraft.prepareForEditorSave(candidateId)
                        val candidateTransaction = candidateDraft.toTransaction()
                        saveMerchantRule(
                            originatingTransactionId = candidateId,
                            category = candidateTransaction.category,
                            merchantOverride = candidateTransaction.merchantOverride,
                            editorTransaction = candidateTransaction,
                            candidateEditorDraft = candidateDraft,
                        )
                    },
                    onDelete =
                        editorId?.let { id ->
                            uiState.sortedTransactions.firstOrNull { it.id == id }?.let { transaction ->
                                { requestDelete(transaction, closeEditor = true) }
                            }
                        },
                    onCancel = ::requestEditorDismissal,
                    persistenceBusy = editorMutationBusy,
                    modifier = Modifier,
                )
            }
        }
    }

    pendingDeleteRequest?.let { request ->
        AlertDialog(
            onDismissRequest = {
                if (!editorMutationBusy) pendingDeleteRequest = null
            },
            title = { Text("Delete transaction?") },
            text = {
                Text(
                    "Delete ${request.transaction.deleteDescription()}? " +
                        "You can restore it with Undo after deletion.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = ::confirmDelete,
                    enabled = !editorMutationBusy,
                    modifier = Modifier.testTag("confirm_delete_transaction"),
                ) { Text("Delete", color = LocalFinanceColors.current.expense) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeleteRequest = null },
                    enabled = !editorMutationBusy,
                    modifier = Modifier.testTag("cancel_delete_transaction"),
                ) { Text("Cancel") }
            },
            modifier = Modifier.testTag("delete_transaction_dialog"),
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = {
                showDiscardDialog = false
                pendingWidgetQuickAddAfterDiscard = false
            },
            title = { Text("Discard changes?") },
            text = { Text("Your transaction edits have not been saved.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        if (pendingWidgetQuickAddAfterDiscard) {
                            pendingWidgetQuickAddAfterDiscard = false
                            editorId = null
                            editorDraft = widgetQuickAddDraft(uiState.suggestionHistory)
                            originalEditorDraft = editorDraft
                            editorSessionId += 1
                            showSheet = true
                        } else {
                            showSheet = false
                        }
                    },
                ) { Text("Discard", color = LocalFinanceColors.current.expense) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    pendingWidgetQuickAddAfterDiscard = false
                }) { Text("Keep editing") }
            },
        )
    }

    LaunchedEffect(showDataSheet) {
        if (showDataSheet) {
            val (hasKey, status) =
                withContext(Dispatchers.IO) {
                    GeminiApiKeyStore(context).hasKey() to GeminiRunStatusStore(context).load()
                }
            geminiKeySaved = hasKey
            geminiStatus = status
        }
    }

    if (showDataSheet) {
        DataSheetModal(
            operation = dataOperation,
            snackbarHostState = dataSnackbarHostState,
            onDismissRequest = { showDataSheet = false },
        ) {
            DataSheet(
                simpleFin = uiState.simpleFin,
                operation = dataOperation,
                merchantRules = merchantRules,
                merchantRulesLoading = false,
                onDeleteMerchantRule = { normalizedKey ->
                    if (dataOperation != null) return@DataSheet
                    dataOperation = DataOperation.DeleteMerchantRule
                    scope.launch {
                        val message =
                            try {
                                if (viewModel.deleteMerchantRule(normalizedKey) == null) {
                                    "Merchant rule was already removed"
                                } else {
                                    "Merchant rule deleted"
                                }
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (_: Throwable) {
                                "Could not delete merchant rule"
                            } finally {
                                dataOperation = null
                            }
                        dataSnackbarHostState.showSnackbar(message)
                    }
                },
                onOpenSetup = {
                    if (dataOperation != null) return@DataSheet
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SimpleFinCreateUrl))
                    runCatching { context.startActivity(intent) }
                        .onFailure { scope.launch { dataSnackbarHostState.showSnackbar("Could not open SimpleFIN") } }
                },
                onConnect = { token ->
                    if (dataOperation != null) return@DataSheet
                    dataOperation = DataOperation.Connect
                    scope.launch {
                        var message = "Bank connection failed"
                        try {
                            val result = connectSimpleFin(token)
                            if (result is SimpleFinSyncResult.Success && (result.inserted > 0 || result.updated > 0)) {
                                bestEffortWidgetRefresh { transactionWidgetRefresh(context) }
                            }
                            message = result.connectionSnackbarMessage()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Throwable) {
                            message = "Bank connection failed"
                        } finally {
                            dataOperation = null
                        }
                        dataSnackbarHostState.showSnackbar(message)
                    }
                },
                onRetryConnection = {
                    if (dataOperation != null) return@DataSheet
                    dataOperation = DataOperation.Connect
                    scope.launch {
                        var message = "Bank connection failed"
                        try {
                            val result = retryPendingSimpleFinConnection()
                            if (result is SimpleFinSyncResult.Success && (result.inserted > 0 || result.updated > 0)) {
                                bestEffortWidgetRefresh { transactionWidgetRefresh(context) }
                            }
                            message = result.connectionSnackbarMessage()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Throwable) {
                            message = "Bank connection failed"
                        } finally {
                            dataOperation = null
                        }
                        dataSnackbarHostState.showSnackbar(message)
                    }
                },
                onCancelPendingConnection = {
                    if (dataOperation != null) return@DataSheet
                    dataOperation = DataOperation.StartOver
                    scope.launch {
                        var message = "Could not start over SimpleFIN"
                        try {
                            cancelPendingSimpleFinConnection()
                            message = "SimpleFIN connection canceled"
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Throwable) {
                            message = "Could not start over SimpleFIN"
                        } finally {
                            dataOperation = null
                        }
                        dataSnackbarHostState.showSnackbar(message)
                    }
                },
                onSync = { requestManualSimpleFinSync(dataSnackbarHostState) },
                onAutomaticSyncsPerDayChange = { count ->
                    if (dataOperation != null) return@DataSheet
                    dataOperation = DataOperation.UpdateAutomaticSyncs
                    scope.launch {
                        var message = "Could not update automatic sync schedule"
                        try {
                            viewModel.updateAutomaticSyncsPerDay(count)
                            message = "Automatic syncs set to $count per day"
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Throwable) {
                            message = automaticSyncScheduleUpdateFailureMessage(failure)
                        } finally {
                            dataOperation = null
                        }
                        dataSnackbarHostState.showSnackbar(message)
                    }
                },
                onAutomaticSyncTimeChange = { time ->
                    if (dataOperation != null) return@DataSheet
                    dataOperation = DataOperation.UpdateAutomaticSyncs
                    scope.launch {
                        var message = "Could not update automatic sync schedule"
                        try {
                            viewModel.updateAutomaticSyncTime(time)
                            message =
                                if (time == null) {
                                    "Automatic sync time cleared"
                                } else {
                                    "Automatic sync time set to ${time.format(TimeFormatter)}"
                                }
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Throwable) {
                            message = automaticSyncScheduleUpdateFailureMessage(failure)
                        } finally {
                            dataOperation = null
                        }
                        dataSnackbarHostState.showSnackbar(message)
                    }
                },
                onCountResetDays = viewModel::countRange,
                onResetDays = { range, zoneId, count ->
                    if (dataOperation == null && pendingRangeReset == null) {
                        resetDaysFailure = null
                        resetDaysConfirmation = ResetDaysRequest(range, zoneId, count)
                    }
                },
                resetDaysClock = resetDaysClock,
                resetDaysZoneId = resetDaysZoneId,
                rangeResetPending = pendingRangeReset != null,
                resetDaysRecountRequest = resetDaysRecountRequest,
                resetDaysReselectRequest = resetDaysReselectRequest,
                onImport = {
                    if (dataOperation == null) {
                        dataOperation = DataOperation.Import
                        runCatching {
                            importLauncher.launch(arrayOf("text/*", "text/csv", "application/csv"))
                        }.onFailure {
                            dataOperation = null
                            scope.launch { dataSnackbarHostState.showSnackbar("Import failed") }
                        }
                    }
                },
                onExport = {
                    if (dataOperation == null) {
                        dataOperation = DataOperation.Export
                        runCatching {
                            exportLauncher.launch("penny-${LocalDate.now()}.csv")
                        }.onFailure {
                            dataOperation = null
                            scope.launch { dataSnackbarHostState.showSnackbar("Export failed") }
                        }
                    }
                },
                onDisconnect = {
                    if (dataOperation == null) showDisconnectConfirmation = true
                },
                onClose = {
                    if (dataOperation == null) showDataSheet = false
                },
                geminiKeySaved = geminiKeySaved,
                geminiStatus = geminiStatus,
                onSaveGeminiKey = { key ->
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                GeminiApiKeyStore(context).save(key.trim())
                                GeminiRunStatusStore(context).clear()
                            }
                            geminiKeySaved = true
                            geminiStatus = GeminiRunStatus()
                            dataSnackbarHostState.showSnackbar(
                                "Gemini key saved. Auto-categorize runs after each sync.",
                            )
                        }.onFailure { failure ->
                            if (failure is CancellationException) throw failure
                            dataSnackbarHostState.showSnackbar("Could not save Gemini key")
                        }
                    }
                },
                onClearGeminiKey = {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                GeminiApiKeyStore(context).delete()
                                GeminiRunStatusStore(context).clear()
                            }
                            geminiKeySaved = false
                            geminiStatus = GeminiRunStatus()
                            dataSnackbarHostState.showSnackbar("Gemini key removed. Auto-categorize off.")
                        }.onFailure { failure ->
                            if (failure is CancellationException) throw failure
                            dataSnackbarHostState.showSnackbar("Could not remove Gemini key")
                        }
                    }
                },
                modifier = Modifier.imePadding(),
            )
        }
    }

    resetDaysConfirmation?.let { request ->
        ResetDaysConfirmationDialog(
            request = request,
            operation = dataOperation,
            failureMessage = resetDaysFailure,
            onConfirm = {
                if (dataOperation != null) return@ResetDaysConfirmationDialog
                if (!isSimpleFinResetRangeCurrent(request.range, resetDaysClock, request.zoneId)) {
                    resetDaysConfirmation = null
                    resetDaysFailure = null
                    resetDaysReselectRequest += 1
                    scope.launch {
                        dataSnackbarHostState.showSnackbar(
                            "These days moved outside SimpleFIN's current 45-day window. Choose the days again.",
                        )
                    }
                    return@ResetDaysConfirmationDialog
                }
                resetDaysFailure = null
                dataOperation = DataOperation.ResetDays
                scope.launch {
                    var recoveryMessage: String? = null
                    var recount = false
                    var reselect = false
                    val committed =
                        try {
                            viewModel.resetRange(
                                range = request.range,
                                zoneId = request.zoneId,
                                expectedCount = request.count,
                                clock = resetDaysClock,
                            )
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: TransactionRangeChangedException) {
                            recount = true
                            recoveryMessage = "Penny data changed. Review the updated count and confirm again."
                            null
                        } catch (_: SimpleFinResetRangeOutOfWindowException) {
                            reselect = true
                            recoveryMessage =
                                "These days moved outside SimpleFIN's current 45-day window. Choose the days again."
                            null
                        } catch (_: Throwable) {
                            resetDaysFailure = "Could not reset these days. Your Penny data was not changed."
                            null
                        } finally {
                            dataOperation = null
                        }
                    if (committed != null) {
                        resetDaysConfirmation = null
                        resetDaysFailure = null
                        showDataSheet = false
                    } else if (recoveryMessage != null) {
                        resetDaysConfirmation = null
                        resetDaysFailure = null
                        if (recount) resetDaysRecountRequest += 1
                        if (reselect) resetDaysReselectRequest += 1
                        dataSnackbarHostState.showSnackbar(recoveryMessage!!)
                    }
                }
            },
            onDismiss = {
                if (dataOperation == null) {
                    resetDaysConfirmation = null
                    resetDaysFailure = null
                }
            },
        )
    }

    pendingMerchantRuleConflict?.let { conflict ->
        val existingDisplay =
            conflict.existingRule.merchantOverride
                ?.let { ", display “$it”" }
                .orEmpty()
        val proposedDisplay =
            conflict.proposedRule.merchantOverride
                ?.let { ", display “$it”" }
                .orEmpty()
        AlertDialog(
            onDismissRequest = {
                if (!persistenceBusy && !viewModelMutationBusy) pendingMerchantRuleConflict = null
            },
            title = { Text("Replace saved rule?") },
            text = {
                Text(
                    "Rule “${conflict.existingRule.normalizedProviderMerchant}” currently uses " +
                        "${conflict.existingRule.category}$existingDisplay. Replace it with " +
                        "${conflict.proposedRule.category}$proposedDisplay?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingMerchantRuleConflict = null
                        saveMerchantRule(
                            originatingTransactionId = conflict.originatingTransactionId,
                            category = conflict.category,
                            merchantOverride = conflict.merchantOverride,
                            overwriteConflict = true,
                            editorTransaction = conflict.editorTransaction,
                            candidateEditorDraft = conflict.editorDraft,
                        )
                    },
                    enabled = !persistenceBusy && !viewModelMutationBusy,
                    modifier = Modifier.testTag("confirm_merchant_rule_overwrite"),
                ) { Text("Replace rule") }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingMerchantRuleConflict = null },
                    enabled = !persistenceBusy && !viewModelMutationBusy,
                ) { Text("Keep existing") }
            },
        )
    }

    if (showDisconnectConfirmation) {
        AlertDialog(
            onDismissRequest = {
                if (dataOperation == null) showDisconnectConfirmation = false
            },
            title = { Text("Disconnect bank?") },
            text = { Text("Bank syncing will stop. Imported and local transactions will remain in Penny.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (dataOperation != null) return@TextButton
                        showDisconnectConfirmation = false
                        dataOperation = DataOperation.Disconnect
                        scope.launch {
                            var message = "Could not disconnect bank"
                            try {
                                viewModel.disconnectSimpleFin()
                                message = "Bank disconnected"
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (_: Throwable) {
                                message = "Could not disconnect bank"
                            } finally {
                                dataOperation = null
                            }
                            dataSnackbarHostState.showSnackbar(message)
                        }
                    },
                    enabled = dataOperation == null,
                    modifier = Modifier.testTag("confirm_disconnect_button"),
                ) { Text("Disconnect", color = LocalFinanceColors.current.expense) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDisconnectConfirmation = false },
                    enabled = dataOperation == null,
                ) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DataSheetModal(
    operation: DataOperation?,
    onDismissRequest: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { target ->
                canDismissDataSheet(
                    isHiding = target == androidx.compose.material3.SheetValue.Hidden,
                    operation = operation,
                )
            },
        )
    ModalBottomSheet(
        onDismissRequest = {
            if (operation == null) onDismissRequest()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.testTag("data_sheet_modal"),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content,
            )
            snackbarHostState?.let { hostState ->
                SnackbarHost(
                    hostState = hostState,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

internal enum class DashboardTab(
    val label: String,
    val iconRes: Int,
) {
    Overview("Overview", R.drawable.ic_nav_overview),
    Transactions("Transactions", R.drawable.ic_nav_transactions),
    Insights("Insights", R.drawable.ic_nav_insights),
    Review("Review", R.drawable.ic_nav_review),
}

private val PrimaryDashboardTabs =
    listOf(
        DashboardTab.Overview,
        DashboardTab.Transactions,
        DashboardTab.Insights,
    )

internal enum class SyncHealthKind {
    Connected,
    Pending,
    ReconnectRequired,
    Syncing,
    Error,
    CoolingDown,
    Disconnected,
}

internal data class SyncHealthUiState(
    val kind: SyncHealthKind,
    val stateDescription: String,
    val manualSync: Boolean = false,
    val completed: Boolean = false,
) {
    val label: String get() = when (kind) {
        SyncHealthKind.Connected, SyncHealthKind.CoolingDown -> if (completed) "Synced" else "Sync now"
        SyncHealthKind.Syncing -> "Syncing"
        SyncHealthKind.Error -> "Error"
        SyncHealthKind.ReconnectRequired -> "Reconnect"
        SyncHealthKind.Disconnected -> "Disconnected"
        SyncHealthKind.Pending -> "Pending"
    }
}

internal fun simpleFinNextEligibleSyncAt(profile: SimpleFinProfileEntity): Long? {
    val attempt = profile.lastSyncAttemptAtEpochMillis
    val success = profile.lastSuccessfulSyncAtEpochMillis
    val lastAttemptSucceeded = success != null && (attempt == null || success >= attempt)
    val anchor = if (lastAttemptSucceeded) success else attempt ?: return null
    val interval =
        if (lastAttemptSucceeded) {
            automaticSyncIntervalMillis(profile.automaticSyncsPerDay)
        } else {
            SIMPLEFIN_RETRY_INTERVAL_MILLIS
        }
    return try {
        Math.addExact(anchor, interval)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

internal fun syncHealthUiState(
    simpleFin: SimpleFinUiState,
    operation: DataOperation?,
    nowEpochMillis: Long,
): SyncHealthUiState {
    if (operation == DataOperation.Sync) {
        return SyncHealthUiState(SyncHealthKind.Syncing, "Bank sync in progress")
    }
    if (simpleFin.isConnectionPending) {
        return SyncHealthUiState(SyncHealthKind.Pending, "Connection pending; open Data to retry")
    }
    val profile =
        simpleFin.profile
            ?: return SyncHealthUiState(SyncHealthKind.Disconnected, "Bank sync disconnected; open Data to connect")
    if (profile.isPaused) {
        return SyncHealthUiState(
            SyncHealthKind.ReconnectRequired,
            "Bank reconnect required; open Data",
        )
    }

    val attempt = profile.lastSyncAttemptAtEpochMillis
    val success = profile.lastSuccessfulSyncAtEpochMillis
    val lastAttemptSucceeded = success != null && (attempt == null || success >= attempt)
    val failedAttempt =
        profile.lastError?.isNotBlank() == true &&
            profile.lastSyncAttemptAtEpochMillis != null &&
            (
                profile.lastSuccessfulSyncAtEpochMillis == null ||
                    profile.lastSyncAttemptAtEpochMillis > profile.lastSuccessfulSyncAtEpochMillis
            )
    if (!isSimpleFinSyncEligible(profile, nowEpochMillis)) {
        val next = simpleFinNextEligibleSyncAt(profile).toSyncTime()
        return SyncHealthUiState(
            kind = if (failedAttempt) SyncHealthKind.Error else SyncHealthKind.CoolingDown,
            stateDescription =
                if (failedAttempt) {
                    "Sync error; cooling down until $next; open Data"
                } else if (lastAttemptSucceeded) {
                    "Sync completed; cooling down until $next; open Data"
                } else {
                    "Last sync didn’t finish; retry after $next; open Data"
                },
            completed = lastAttemptSucceeded && !failedAttempt,
        )
    }
    if (failedAttempt) {
        return SyncHealthUiState(
            SyncHealthKind.Error,
            "Sync error; retry now",
            manualSync = true,
        )
    }
    if (profile.automaticSyncsPerDay == 1) {
        return SyncHealthUiState(
            SyncHealthKind.Connected,
            when {
                attempt == null && success == null -> "Not synced yet; automatic sync is once daily; open Data to change cadence"
                !lastAttemptSucceeded -> "Last sync didn’t finish; automatic sync is once daily; open Data to change cadence"
                else -> "Sync due; automatic sync is once daily; open Data to change cadence"
            },
        )
    }
    return SyncHealthUiState(
        kind = SyncHealthKind.Connected,
        stateDescription = when {
            attempt == null && success == null -> "Not synced yet; sync now"
            !lastAttemptSucceeded -> "Last sync didn’t finish; sync now"
            else -> "Sync due; sync now"
        },
        manualSync = true,
    )
}

@Composable
internal fun rememberSyncHealthNow(clock: Clock, profile: SimpleFinProfileEntity?): Long {
    var now by remember(clock) { mutableStateOf(clock.millis()) }
    var resumeGeneration by remember(clock) { mutableStateOf(0) }
    LifecycleResumeEffect(clock) {
        now = clock.millis()
        resumeGeneration++
        onPauseOrDispose { }
    }
    LaunchedEffect(clock, profile, resumeGeneration) {
        val current = clock.millis()
        now = current
        val next = profile?.let(::simpleFinNextEligibleSyncAt)
        if (next != null && next != Long.MAX_VALUE && next > current) {
            val wait = try { Math.subtractExact(next, current) } catch (_: ArithmeticException) { Long.MAX_VALUE }
            delay(wait)
            now = clock.millis()
        }
    }
    return now
}

@Composable
internal fun AdaptiveFlowMoneyShell(
    selectedTab: DashboardTab,
    snackbarHostState: SnackbarHostState,
    onTabSelected: (DashboardTab) -> Unit,
    onAddTransaction: () -> Unit,
    onData: () -> Unit,
    simpleFin: SimpleFinUiState = SimpleFinUiState(),
    operation: DataOperation? = null,
    syncHealthNowEpochMillis: Long = System.currentTimeMillis(),
    onManualSync: () -> Unit = {},
    modifier: Modifier = Modifier,
    reviewOriginTab: DashboardTab = DashboardTab.Overview,
    content: @Composable (PaddingValues) -> Unit,
) {
    val navigationTab = if (selectedTab == DashboardTab.Review) reviewOriginTab.takeUnless { it == DashboardTab.Review } ?: DashboardTab.Overview else selectedTab
    BoxWithConstraints(modifier = modifier) {
        val isWide = maxWidth >= NavigationBreakpoint
        if (isWide) {
            Row(modifier = Modifier.fillMaxSize()) {
                FlowMoneyNavigationRail(
                    selectedTab = navigationTab,
                    onTabSelected = onTabSelected,
                )
                FlowMoneyScaffold(
                    selectedTab = navigationTab,
                    isWide = true,
                    snackbarHostState = snackbarHostState,
                    onTabSelected = onTabSelected,
                    onAddTransaction = onAddTransaction,
                    onData = onData,
                    simpleFin = simpleFin,
                    operation = operation,
                    syncHealthNowEpochMillis = syncHealthNowEpochMillis,
                    onManualSync = onManualSync,
                    modifier = Modifier.weight(1f),
                    content = content,
                )
            }
        } else {
            FlowMoneyScaffold(
                selectedTab = navigationTab,
                isWide = false,
                snackbarHostState = snackbarHostState,
                onTabSelected = onTabSelected,
                onAddTransaction = onAddTransaction,
                onData = onData,
                simpleFin = simpleFin,
                operation = operation,
                syncHealthNowEpochMillis = syncHealthNowEpochMillis,
                onManualSync = onManualSync,
                modifier = Modifier.fillMaxSize(),
                content = content,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowMoneyScaffold(
    selectedTab: DashboardTab,
    isWide: Boolean,
    snackbarHostState: SnackbarHostState,
    onTabSelected: (DashboardTab) -> Unit,
    onAddTransaction: () -> Unit,
    onData: () -> Unit,
    simpleFin: SimpleFinUiState,
    operation: DataOperation?,
    syncHealthNowEpochMillis: Long,
    onManualSync: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    var snackbarHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Scaffold(
        modifier = modifier,
        topBar = {
            FlowMoneyTopAppBar(
                simpleFin = simpleFin,
                operation = operation,
                syncHealthNowEpochMillis = syncHealthNowEpochMillis,
                onManualSync = onManualSync,
                onData = onData,
                onAddTransaction = onAddTransaction,
            )
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.testTag("app_snackbar_host").onSizeChanged { snackbarHeight = it.height },
            )
        },
        bottomBar = {
            if (!isWide) {
                FlowMoneyNavigationBar(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                )
            }
        },
        contentWindowInsets =
            if (isWide) {
                WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
            } else {
                WindowInsets(0.dp)
            },
        content = { padding ->
            val extra = if (snackbarHostState.currentSnackbarData != null) with(density) { snackbarHeight.toDp() } else 0.dp
            Box(Modifier.fillMaxSize().padding(bottom = extra)) { content(padding) }
        },
    )
}

@Composable
internal fun FlowMoneyTopAppBar(
    onData: () -> Unit,
    onAddTransaction: () -> Unit,
    simpleFin: SimpleFinUiState = SimpleFinUiState(),
    operation: DataOperation? = null,
    syncHealthNowEpochMillis: Long = System.currentTimeMillis(),
    onManualSync: () -> Unit = {},
) {
    val syncHealth = syncHealthUiState(simpleFin, operation, syncHealthNowEpochMillis)
    val syncActionEnabled = operation == null
    val density = LocalDensity.current
    val label = if (density.fontScale >= 1.5f && syncHealth.kind == SyncHealthKind.ReconnectRequired) "Fix"
        else if (density.fontScale >= 1.5f && syncHealth.kind == SyncHealthKind.Disconnected) "No bank"
        else syncHealth.label
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val labelWidth = remember(label, labelStyle, density, measurer) { measurer.measure(label, style = labelStyle, softWrap = false).size.width }
    PennyTopAppBar(
        status = {
            Box(
                modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                    .clickable(enabled = syncActionEnabled, role = Role.Button) {
                        if (syncHealth.manualSync) onManualSync() else onData()
                    }.semantics {
                        contentDescription = "Bank sync status"
                        stateDescription = syncHealth.stateDescription
                    }.padding(horizontal = 4.dp).testTag("sync_health_action"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.width(with(density) { labelWidth.toDp() } + 2.dp),
                    color =
                        if (syncHealth.kind == SyncHealthKind.Error || syncHealth.kind == SyncHealthKind.ReconnectRequired) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    style = labelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        onData = onData,
        onAddTransaction = onAddTransaction,
    )
}

@Composable
private fun FlowMoneyNavigationBar(
    selectedTab: DashboardTab,
    onTabSelected: (DashboardTab) -> Unit,
) {
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val longestLabelPx = remember(measurer, style, density) {
        PrimaryDashboardTabs.maxOf { measurer.measure(it.label, style = style.copy(fontSize = 10.sp), softWrap = false).size.width }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }
        // M3 reserves horizontal label padding; allow for pixel rounding as well.
        val requiredPx = longestLabelPx + with(density) { 16.dp.toPx() } + 2f
        val fallback = requiredPx > widthPx / PrimaryDashboardTabs.size
        val minimumPeerPx = with(density) { 48.dp.toPx() }
        val selectedPx = requiredPx.coerceAtLeast(widthPx / PrimaryDashboardTabs.size)
            .coerceAtMost((widthPx - 2 * minimumPeerPx).coerceAtLeast(0f))
        val peerPx = (widthPx - selectedPx) / 2f
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            windowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
            modifier = Modifier.testTag("compact_navigation"),
        ) {
            val rowScope = this
            PrimaryDashboardTabs.forEach { tab ->
                val selected = selectedTab == tab
                val weight = if (!fallback) 1f else if (selected) selectedPx else peerPx
                Box(Modifier.weight(weight.coerceAtLeast(1f))) {
                    rowScope.NavigationBarItem(
                        selected = selected,
                        onClick = { onTabSelected(tab) },
                        alwaysShowLabel = !fallback,
                        icon = { NavigationTabIcon(tab) },
                        label = {
                            BasicText(
                                tab.label,
                                modifier = Modifier.fillMaxWidth(),
                                style = style.copy(color = LocalContentColor.current, textAlign = TextAlign.Center),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().testTag("tab_${tab.name.lowercase(Locale.US)}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowMoneyNavigationRail(
    selectedTab: DashboardTab,
    onTabSelected: (DashboardTab) -> Unit,
) {
    NavigationRail(
        modifier =
            Modifier
                .fillMaxHeight()
                .testTag("wide_navigation"),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical),
    ) {
        PrimaryDashboardTabs.forEach { tab ->
            NavigationRailItem(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                icon = { NavigationTabIcon(tab) },
                label = { Text(tab.label, maxLines = 1) },
                modifier = Modifier.testTag("tab_${tab.name.lowercase(Locale.US)}"),
            )
        }
    }
}

@Composable
private fun NavigationTabIcon(tab: DashboardTab) {
    Icon(painter = painterResource(tab.iconRes), contentDescription = null)
}

@Composable
internal fun FlowMoneyScreen(
    uiState: MainUiState,
    selectedTab: DashboardTab,
    onChartRangeModeSelected: (ChartRangeMode) -> Unit,
    onSelectedMonthChange: (YearMonth) -> Unit,
    onEdit: (Transaction) -> Unit,
    onViewAllTransactions: () -> Unit,
    onDelete: (Transaction) -> Unit,
    onAddTransaction: () -> Unit,
    onData: () -> Unit,
    modifier: Modifier = Modifier,
    onReview: () -> Unit = {},
    onCategorizeReview: (Transaction, String, Boolean) -> Unit = { _, _, _ -> },
    onBulkCategorizeReview: (List<String>, String) -> Unit = { _, _ -> },
    onBulkAcceptOther: (List<String>) -> Unit = {},
    reviewOperationBusy: Boolean = false,
    reviewTransactions: List<Transaction>? = null,
) {
    if (uiState.isLoading) {
        LoadingState(modifier)
        return
    }

    AnimatedContent(
        targetState = selectedTab,
        modifier = modifier,
        transitionSpec = {
            (
                fadeIn(
                    animationSpec =
                        tween(
                            durationMillis = PennyMotion.DurationMedium,
                            easing = PennyMotion.StandardDecelerateEasing,
                        ),
                ) +
                    slideInVertically(
                        animationSpec =
                            tween(
                                durationMillis = PennyMotion.DurationMedium,
                                easing = PennyMotion.StandardEasing,
                            ),
                        initialOffsetY = { fullHeight -> fullHeight / 24 },
                    )
            ).togetherWith(
                fadeOut(
                    animationSpec =
                        tween(
                            durationMillis = PennyMotion.DurationShort,
                            easing = PennyMotion.StandardAccelerateEasing,
                        ),
                ),
            )
        },
        label = "dashboard_tab",
    ) { tab ->
        when (tab) {
            DashboardTab.Overview -> {
                OverviewPage(
                    sortedTransactions = uiState.sortedTransactions,
                    latestSpendingTransactions = uiState.latestSpendingTransactions,
                    metrics = uiState.metrics,
                    dateRange = uiState.dateRange,
                    chartRangeMode = uiState.chartRangeMode,
                    selectedMonth = uiState.selectedMonth,
                    availableMonths = uiState.availableMonths,
                    onChartRangeModeSelected = onChartRangeModeSelected,
                    onSelectedMonthChange = onSelectedMonthChange,
                    onEdit = onEdit,
                    onViewAllTransactions = onViewAllTransactions,
                    onDelete = onDelete,
                    onAddTransaction = onAddTransaction,
                    onData = onData,
                    simpleFin = uiState.simpleFin,
                    pendingReviewCount = uiState.sortedTransactions.count(Transaction::isUnreviewed),
                    onReview = onReview,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            DashboardTab.Transactions -> {
                TransactionsPage(
                    sortedTransactions = uiState.sortedTransactions,
                    simpleFinAccounts = uiState.simpleFin.accounts,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onAddTransaction = onAddTransaction,
                    onData = onData,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            DashboardTab.Insights -> {
                InsightsPage(
                    dailySpending = uiState.dailySpending,
                    categoryTotals = uiState.categoryTotals,
                    rangeTransactions = uiState.rangeTransactions,
                    dateRange = uiState.dateRange,
                    chartRangeMode = uiState.chartRangeMode,
                    selectedMonth = uiState.selectedMonth,
                    availableMonths = uiState.availableMonths,
                    onChartRangeModeSelected = onChartRangeModeSelected,
                    onSelectedMonthChange = onSelectedMonthChange,
                    onEdit = onEdit,
                    onAddTransaction = onAddTransaction,
                    onData = onData,
                    pendingReviewSummary = uiState.pendingReviewSummary,
                    onReview = onReview,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            DashboardTab.Review -> {
                ReviewPage(
                    unreviewedTransactions =
                        reviewTransactions
                            ?: uiState.sortedTransactions
                                .filter(Transaction::isUnreviewed)
                                .sortedByDescending(Transaction::occurredAtEpochMillis),
                    onEdit = onEdit,
                    onCategorize = onCategorizeReview,
                    onBulkCategorize = onBulkCategorizeReview,
                    onBulkAcceptOther = onBulkAcceptOther,
                    operationBusy = reviewOperationBusy,
                    isActive = selectedTab == DashboardTab.Review,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.testTag("app_loading_state"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))
            Text("Loading your spending", fontWeight = FontWeight.SemiBold)
            Text(
                "Getting transactions ready",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun OverviewPage(
    sortedTransactions: List<Transaction>,
    latestSpendingTransactions: List<Transaction>,
    metrics: DashboardMetrics,
    dateRange: DashboardDateRange,
    chartRangeMode: ChartRangeMode,
    selectedMonth: YearMonth,
    availableMonths: List<YearMonth>,
    onChartRangeModeSelected: (ChartRangeMode) -> Unit,
    onSelectedMonthChange: (YearMonth) -> Unit,
    onEdit: (Transaction) -> Unit,
    onViewAllTransactions: () -> Unit,
    onDelete: (Transaction) -> Unit,
    onAddTransaction: () -> Unit,
    onData: () -> Unit,
    simpleFin: SimpleFinUiState,
    pendingReviewCount: Int,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amountWidth = rememberTransactionAmountWidth(latestSpendingTransactions)
    Box(modifier = modifier) {
        LazyColumn(
            modifier =
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .testTag("overview_list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PagePadding,
        ) {
            item {
                DateRangeControls(
                    mode = chartRangeMode,
                    selectedMonth = selectedMonth,
                    dateRange = dateRange,
                    availableMonths = availableMonths,
                    onModeSelected = onChartRangeModeSelected,
                    onSelectedMonthChange = onSelectedMonthChange,
                )
            }
            if (sortedTransactions.isNotEmpty()) item { SummaryBand(metrics = metrics) }
            if (pendingReviewCount > 0) {
                item { OverviewReviewStrip(pendingReviewCount, onReview) }
            }
            if (sortedTransactions.isEmpty()) {
                item {
                    EmptyState(
                        onAddTransaction = onAddTransaction,
                        onData = onData,
                        disconnected = simpleFin.profile == null && !simpleFin.isConnectionPending,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                    )
                }
            } else {
                if (latestSpendingTransactions.isEmpty()) {
                    item { Text("No spending in this period", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                } else {
                item { Text("Latest spending", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            latestSpendingTransactions.forEachIndexed { index, transaction ->
                                key(transaction.id) {
                                    SwipeTransactionRow(
                                        transaction = transaction,
                                        onEdit = { onEdit(transaction) },
                                        onDelete = { onDelete(transaction) },
                                        showDate = true,
                                        amountWidth = amountWidth,
                                    )
                                }
                                if (index < latestSpendingTransactions.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 14.dp),
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }
                }
                item {
                    TextButton(onClick = onViewAllTransactions, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("All transactions")
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewReviewStrip(pendingReviewCount: Int, onReview: () -> Unit) {
    TextButton(
        onClick = onReview,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("overview_review_action").semantics {
            contentDescription = "Review $pendingReviewCount synced ${if (pendingReviewCount == 1) "transaction" else "transactions"}"
        },
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text("Review $pendingReviewCount", modifier = Modifier.testTag("overview_pending_review_count"))
    }
}

internal data class TransactionDayGroup(
    val date: LocalDate,
    val transactions: List<Transaction>,
    val spentCents: Long,
    val receivedCents: Long,
)

internal fun transactionDayGroups(transactions: List<Transaction>): List<TransactionDayGroup> =
    transactions
        .groupBy { it.occurredAtDateTime().toLocalDate() }
        .map { (date, dayTransactions) ->
            val metrics = DashboardAnalytics.metrics(dayTransactions)
            TransactionDayGroup(
                date = date,
                transactions = dayTransactions,
                spentCents = metrics.spentCents,
                receivedCents = metrics.incomeCents,
            )
        }

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
internal fun ReviewPage(
    unreviewedTransactions: List<Transaction>,
    onEdit: (Transaction) -> Unit,
    onCategorize: (Transaction, String, Boolean) -> Unit,
    onBulkCategorize: (List<String>, String) -> Unit,
    onBulkAcceptOther: (List<String>) -> Unit,
    operationBusy: Boolean,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
) {
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    BackHandler(enabled = isActive && selectionMode) {
        selectedIds = emptyList()
        selectionMode = false
    }
    var confirmAcceptOther by rememberSaveable { mutableStateOf(false) }
    val orderedTransactions =
        remember(unreviewedTransactions) {
            unreviewedTransactions
                .filter(Transaction::isUnreviewed)
                .sortedByDescending(Transaction::occurredAtEpochMillis)
        }
    val groups = remember(orderedTransactions) { transactionDayGroups(orderedTransactions) }
    val amountWidth = rememberTransactionAmountWidth(orderedTransactions)

    LaunchedEffect(orderedTransactions) {
        val availableIds = orderedTransactions.mapTo(mutableSetOf(), Transaction::id)
        selectedIds = selectedIds.filter(availableIds::contains)
        if (orderedTransactions.isEmpty()) selectionMode = false
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier =
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .testTag("review_list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PagePadding,
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Review", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${orderedTransactions.size} synced ${if (orderedTransactions.size == 1) "transaction" else "transactions"} available",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("review_pending_count"),
                        )
                    }
                    if (orderedTransactions.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                selectionMode = !selectionMode
                                selectedIds = emptyList()
                            },
                            enabled = !operationBusy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("review_select_action"),
                        ) { Text(if (selectionMode) "Done" else "Select") }
                    }
                }
            }
            if (selectionMode) {
                stickyHeader(key = "review_bulk_header") {
                    ReviewBulkControls(
                        selectedCount = selectedIds.size,
                        enabled = selectedIds.isNotEmpty() && !operationBusy,
                        onDone = { selectionMode = false; selectedIds = emptyList() },
                        onCategorize = { category ->
                            val ids = selectedIds
                            selectionMode = false
                            selectedIds = emptyList()
                            onBulkCategorize(ids, category)
                        },
                        onAcceptOther = { confirmAcceptOther = true },
                    )
                }
                item(key = "review_quick_categories") {
                    CategoryChoiceBar(
                        categories = (CategoryCatalog.expenseCategories + CategoryCatalog.incomeCategories).distinct(),
                        enabled = selectedIds.isNotEmpty() && !operationBusy,
                        moreContentDescription = "More quick bulk categories",
                        testTagPrefix = "review_quick_category",
                        onCategory = { category ->
                            val ids = selectedIds
                            selectionMode = false
                            selectedIds = emptyList()
                            onBulkCategorize(ids, category)
                        },
                    )
                }
            }
            if (orderedTransactions.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth().testTag("review_empty_state"),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("All caught up", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Synced transactions that are not auto-confirmed will appear here for optional corrections.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                groups.forEach { group ->
                    if (selectionMode) {
                        item(key = "review_day_${group.date}") { TransactionDayHeader(group) }
                    } else {
                        stickyHeader(key = "review_day_${group.date}") { TransactionDayHeader(group) }
                    }
                    item(key = "review_rows_${group.date}") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            group.transactions.forEach { transaction ->
                                key(transaction.id) {
                                    ReviewTransactionCard(
                                        transaction = transaction,
                                        selectionMode = selectionMode,
                                        amountWidth = amountWidth,
                                        selected = transaction.id in selectedIds,
                                        enabled = !operationBusy,
                                        onSelectionChange = { selected ->
                                            selectedIds =
                                                if (selected) {
                                                    (selectedIds + transaction.id).distinct()
                                                } else {
                                                    selectedIds - transaction.id
                                                }
                                        },
                                        onEdit = { onEdit(transaction) },
                                        onCategorize = { category, useForFuture ->
                                            onCategorize(transaction, category, useForFuture)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmAcceptOther) {
        AlertDialog(
            onDismissRequest = { if (!operationBusy) confirmAcceptOther = false },
            title = { Text("Accept as Other?") },
            text = {
                Text(
                    "Mark ${selectedIds.size} selected ${if (selectedIds.size == 1) "transaction" else "transactions"} " +
                        "reviewed with category Other?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selectedIds
                        confirmAcceptOther = false
                        selectionMode = false
                        selectedIds = emptyList()
                        onBulkAcceptOther(ids)
                    },
                    enabled = selectedIds.isNotEmpty() && !operationBusy,
                    modifier = Modifier.testTag("review_confirm_accept_other"),
                ) { Text("Accept as Other") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAcceptOther = false }, enabled = !operationBusy) { Text("Cancel") }
            },
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReviewBulkControls(
    selectedCount: Int,
    enabled: Boolean,
    onDone: () -> Unit,
    onCategorize: (String) -> Unit,
    onAcceptOther: () -> Unit,
) {
    val categories = remember { (CategoryCatalog.expenseCategories + CategoryCatalog.incomeCategories).distinct() }
    var menuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) menuExpanded = false }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().height(56.dp).testTag("review_bulk_controls"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone, modifier = Modifier.size(48.dp).testTag("review_done_selecting").semantics { contentDescription = "Done selecting" }) {
                Text("×", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clearAndSetSemantics {})
            }
            BasicText(
                "$selectedCount selected",
                modifier = Modifier.weight(1f).testTag("review_selected_count"),
                style = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.SemiBold),
                maxLines = 1, softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
            )
            Box {
                IconButton(onClick = { menuExpanded = true }, enabled = enabled, modifier = Modifier.size(48.dp).testTag("review_bulk_actions").semantics { contentDescription = "Bulk actions" }) {
                    Text("⋮", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clearAndSetSemantics {})
                }
                DropdownMenu(expanded = menuExpanded && enabled, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Accept as Other…") }, onClick = { menuExpanded = false; onAcceptOther() }, modifier = Modifier.testTag("review_accept_other_action"))
                    categories.forEach { category ->
                        DropdownMenuItem(text = { Text(category) }, onClick = { menuExpanded = false; onCategorize(category) }, modifier = Modifier.testTag("review_bulk_category_${category.toCategoryChipTagSuffix()}"))
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReviewTransactionCard(
    transaction: Transaction,
    selectionMode: Boolean,
    amountWidth: Int,
    selected: Boolean,
    enabled: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onCategorize: (String, Boolean) -> Unit,
) {
    var useForFuture by rememberSaveable(transaction.id) { mutableStateOf(false) }
    val selectionModifier =
        if (selectionMode) {
            Modifier
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onClick = { onSelectionChange(!selected) },
                ).semantics { stateDescription = if (selected) "Selected" else "Not selected" }
        } else {
            Modifier
        }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("review_row_${transaction.id}")
                .then(selectionModifier),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .then(
                            if (selectionMode) {
                                Modifier
                            } else {
                                Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onEdit)
                            },
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                        Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                TransactionPresentation(
                    transaction,
                    amountWidth = amountWidth,
                    decisionWidthInset = if (selectionMode) 0.dp else 56.dp,
                    modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = transaction.rowAccessibility() },
                )
            }
            if (!selectionMode) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(enabled = enabled, role = Role.Checkbox) { useForFuture = !useForFuture }
                            .semantics { stateDescription = if (useForFuture) "Selected" else "Not selected" }
                            .padding(horizontal = 4.dp)
                            .testTag("review_use_future_${transaction.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = useForFuture, onCheckedChange = null, enabled = enabled)
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text("Use for future", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Apply this category to future transactions from the same normalized merchant.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                CategoryChoiceBar(
                    categories = CategoryCatalog.categoriesFor(transaction.cents < 0),
                    enabled = enabled,
                    moreContentDescription = "More categories for ${transaction.merchant}",
                    testTagPrefix = "review_category_${transaction.id}",
                    onCategory = { category -> onCategorize(category, useForFuture) },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CategoryChoiceBar(
    categories: List<String>,
    enabled: Boolean,
    moreContentDescription: String,
    testTagPrefix: String,
    onCategory: (String) -> Unit,
) {
    var moreExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) moreExpanded = false }
    val visible = categories.take(4)
    val remaining = categories.drop(4)
    val choices: @Composable () -> Unit = {
        visible.forEach { category ->
            SuggestionChip(
                label = category,
                isSelection = false,
                enabled = enabled,
                testTag = "${testTagPrefix}_${category.toCategoryChipTagSuffix()}",
                onClick = { onCategory(category) },
            )
        }
        if (remaining.isNotEmpty()) {
            Box {
                SuggestionChip(
                    label = "More (${remaining.size})",
                    isSelection = false,
                    enabled = enabled,
                    accessibilityLabel = moreContentDescription,
                    testTag = "${testTagPrefix}_more",
                    onClick = { moreExpanded = true },
                )
                DropdownMenu(
                    expanded = moreExpanded && enabled,
                    onDismissRequest = { moreExpanded = false },
                    modifier = Modifier.testTag("${testTagPrefix}_menu"),
                ) {
                    remaining.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category) },
                            onClick = {
                                moreExpanded = false
                                onCategory(category)
                            },
                            enabled = enabled,
                            modifier =
                                Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics { contentDescription = "$category. $moreContentDescription" }
                                    .testTag("${testTagPrefix}_more_${category.toCategoryChipTagSuffix()}"),
                        )
                    }
                }
            }
        }
    }
    FlowRow(modifier = Modifier.fillMaxWidth().testTag("${testTagPrefix}_choices"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { choices() }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
private fun TransactionsPage(
    sortedTransactions: List<Transaction>,
    simpleFinAccounts: List<SimpleFinAccountEntity>,
    onEdit: (Transaction) -> Unit,
    onDelete: (Transaction) -> Unit,
    onAddTransaction: () -> Unit,
    onData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var timeFilterName by rememberSaveable { mutableStateOf(TransactionTimeFilter.All.name) }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var whereFilter by rememberSaveable { mutableStateOf("") }
    var sourceFilterName by rememberSaveable { mutableStateOf(TransactionSourceFilter.All.name) }
    var selectedAccountKey by rememberSaveable { mutableStateOf<String?>(null) }
    var unreviewedOnly by rememberSaveable { mutableStateOf(false) }
    var selectedCity by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedState by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCountry by rememberSaveable { mutableStateOf<String?>(null) }
    var viewModeName by rememberSaveable { mutableStateOf(TransactionViewMode.List.name) }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val timeFilter = remember(timeFilterName) { TransactionTimeFilter.valueOf(timeFilterName) }
    val sourceFilter = remember(sourceFilterName) { TransactionSourceFilter.valueOf(sourceFilterName) }
    val viewMode = remember(viewModeName) { TransactionViewMode.valueOf(viewModeName) }
    val categories = remember(sortedTransactions) { TransactionFilters.categories(sortedTransactions) }
    val accountOptions = remember(simpleFinAccounts) { TransactionFilters.accountOptions(simpleFinAccounts) }
    val cityOptions = remember(sortedTransactions) { TransactionFilters.cityOptions(sortedTransactions) }
    val stateOptions = remember(sortedTransactions) { TransactionFilters.stateOptions(sortedTransactions) }
    val countryOptions = remember(sortedTransactions) { TransactionFilters.countryOptions(sortedTransactions) }
    val geocoder =
        remember(context) {
            PlaceGeocoder(
                dao = FlowMoneyDatabase.get(context).placeGeocodeDao(),
                context = context,
            )
        }
    val filter =
        remember(
            timeFilter,
            selectedCategory,
            whereFilter,
            sourceFilter,
            selectedAccountKey,
            unreviewedOnly,
            selectedCity,
            selectedState,
            selectedCountry,
        ) {
            TransactionFilter(
                time = timeFilter,
                category = selectedCategory,
                where = whereFilter,
                source = sourceFilter,
                accountKey = selectedAccountKey,
                unreviewedOnly = unreviewedOnly,
                city = selectedCity,
                state = selectedState,
                country = selectedCountry,
            )
        }
    val filteredTransactions =
        remember(sortedTransactions, filter) {
            TransactionFilters.apply(sortedTransactions, filter)
        }

    LaunchedEffect(categories) {
        if (selectedCategory != null && categories.none { it == selectedCategory }) selectedCategory = null
    }
    LaunchedEffect(accountOptions) {
        if (selectedAccountKey != null && accountOptions.none { it.accountKey == selectedAccountKey }) {
            selectedAccountKey = null
        }
    }
    LaunchedEffect(cityOptions, selectedCity, selectedState, selectedCountry) {
        if (selectedCity != null &&
            cityOptions.none { option ->
                option.city == selectedCity &&
                    (selectedState == null || option.state == selectedState) &&
                    (selectedCountry == null || option.country == selectedCountry)
            }
        ) {
            selectedCity = null
        }
    }
    LaunchedEffect(stateOptions) {
        if (selectedState != null && stateOptions.none { it == selectedState }) selectedState = null
    }
    LaunchedEffect(countryOptions) {
        if (selectedCountry != null && countryOptions.none { it == selectedCountry }) selectedCountry = null
    }
    val dayGroups = remember(filteredTransactions) { transactionDayGroups(filteredTransactions) }
    val amountWidth = rememberTransactionAmountWidth(filteredTransactions)

    val filteredMetrics = remember(filteredTransactions) { DashboardAnalytics.metrics(filteredTransactions) }
    val clearFilters: () -> Unit = {
        timeFilterName = TransactionTimeFilter.All.name
        selectedCategory = null
        whereFilter = ""
        sourceFilterName = TransactionSourceFilter.All.name
        selectedAccountKey = null
        unreviewedOnly = false
        selectedCity = null
        selectedState = null
        selectedCountry = null
        filtersExpanded = false
    }
    val filterBar: @Composable () -> Unit = {
        TransactionFilterBar(
            timeFilter = timeFilter,
            selectedCategory = selectedCategory,
            categories = categories,
            whereFilter = whereFilter,
            sourceFilter = sourceFilter,
            selectedAccountKey = selectedAccountKey,
            accountOptions = accountOptions,
            unreviewedOnly = unreviewedOnly,
            selectedCity = selectedCity,
            selectedState = selectedState,
            selectedCountry = selectedCountry,
            cityOptions = cityOptions,
            stateOptions = stateOptions,
            countryOptions = countryOptions,
            viewMode = viewMode,
            isExpanded = filtersExpanded,
            onExpandedChange = { filtersExpanded = it },
            onTimeFilterChange = { timeFilterName = it.name },
            onCategoryChange = { selectedCategory = it },
            onWhereFilterChange = { whereFilter = it },
            onSourceFilterChange = { sourceFilterName = it.name },
            onAccountChange = { selectedAccountKey = it },
            onUnreviewedOnlyChange = { unreviewedOnly = it },
            onCityOptionChange = { option ->
                if (option == null) {
                    selectedCity = null
                } else {
                    val duplicate = cityOptions.count { it.city == option.city } > 1
                    selectedCity = option.city
                    if (duplicate) {
                        selectedState = option.state
                        selectedCountry = option.country
                    } else {
                        if (selectedState != null && selectedState != option.state) {
                            selectedState = null
                        }
                        if (selectedCountry != null && selectedCountry != option.country) {
                            selectedCountry = null
                        }
                    }
                }
            },
            onStateChange = { selectedState = it },
            onCountryChange = { selectedCountry = it },
            onViewModeChange = { viewModeName = it.name },
            onClear = clearFilters,
        )
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier =
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .align(Alignment.TopCenter)
                    .testTag("transactions_list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding =
                PaddingValues(
                    start = 20.dp,
                    top = 16.dp,
                    end = 20.dp,
                    bottom = 16.dp,
                ),
        ) {
            item {
                TransactionsHeader(
                    metrics = filteredMetrics,
                    hasTransfers = filteredTransactions.any { it.effectiveFlowKind == FlowKind.TRANSFER },
                )
            }
            item {
                filterBar()
            }
            if (viewMode == TransactionViewMode.Map) {
                item {
                    TransactionMap(
                        transactions = filteredTransactions,
                        geocoder = geocoder,
                        onEdit = onEdit,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .fillParentMaxHeight(0.72f)
                                .heightIn(min = 320.dp),
                    )
                }
            } else {
            if (sortedTransactions.isEmpty()) {
                item {
                    EmptyState(
                        onAddTransaction = onAddTransaction,
                        onData = onData,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 320.dp),
                    )
                }
            }
            if (sortedTransactions.isNotEmpty() && filteredTransactions.isEmpty()) {
                item {
                    Text(
                        text = "No matching transactions",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp)
                                .testTag("transactions_empty_filter"),
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (sortedTransactions.isNotEmpty()) {
                dayGroups.forEach { group ->
                    stickyHeader(key = "transaction_day_${group.date}") {
                        TransactionDayHeader(group)
                    }
                    item(key = "transaction_day_rows_${group.date}") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                group.transactions.forEachIndexed { index, transaction ->
                                    key(transaction.id) {
                                        SwipeTransactionRow(
                                            transaction = transaction,
                                            onEdit = { onEdit(transaction) },
                                            onDelete = { onDelete(transaction) },
                                            amountWidth = amountWidth,
                                        )
                                    }
                                    if (index < group.transactions.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 14.dp),
                                            thickness = 1.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TransactionDayHeader(group: TransactionDayGroup) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("transaction_day_header_${group.date}"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = group.date.format(DayHeaderFormatter),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (group.spentCents != 0L || group.receivedCents != 0L) FlowRow(
                modifier = Modifier.testTag("transaction_day_totals_${group.date}"),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (group.spentCents != 0L) Text(
                    text = "Spent ${MoneyFormatter.formatUsd(group.spentCents)}",
                    color = LocalFinanceColors.current.expense,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("transaction_day_spent_${group.date}"),
                )
                if (group.receivedCents != 0L) Text(
                    text = "Received ${MoneyFormatter.formatUsd(group.receivedCents)}",
                    color = LocalFinanceColors.current.income,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("transaction_day_received_${group.date}"),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TransactionFilterBar(
    timeFilter: TransactionTimeFilter,
    selectedCategory: String?,
    categories: List<String>,
    whereFilter: String,
    sourceFilter: TransactionSourceFilter,
    selectedAccountKey: String?,
    accountOptions: List<TransactionAccountOption>,
    unreviewedOnly: Boolean,
    selectedCity: String?,
    selectedState: String?,
    selectedCountry: String?,
    cityOptions: List<TransactionCityOption>,
    stateOptions: List<String>,
    countryOptions: List<String>,
    viewMode: TransactionViewMode,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onTimeFilterChange: (TransactionTimeFilter) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onWhereFilterChange: (String) -> Unit,
    onSourceFilterChange: (TransactionSourceFilter) -> Unit,
    onAccountChange: (String?) -> Unit,
    onUnreviewedOnlyChange: (Boolean) -> Unit,
    onCityOptionChange: (TransactionCityOption?) -> Unit,
    onStateChange: (String?) -> Unit,
    onCountryChange: (String?) -> Unit,
    onViewModeChange: (TransactionViewMode) -> Unit,
    onClear: () -> Unit,
) {
    var accountMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val selectedAccountLabel = accountOptions.firstOrNull { it.accountKey == selectedAccountKey }?.label
    val selectedCityLabel =
        cityOptions
            .firstOrNull { option ->
                option.city == selectedCity &&
                    (selectedState == null || option.state == selectedState) &&
                    (selectedCountry == null || option.country == selectedCountry)
            }?.label ?: selectedCity
    val activeCount = listOf(
        timeFilter != TransactionTimeFilter.All, selectedCategory != null,
        sourceFilter != TransactionSourceFilter.All, selectedAccountKey != null, unreviewedOnly,
        selectedCity != null || selectedState != null || selectedCountry != null,
    ).count { it }
    val hasActiveFilter = activeCount > 0 || whereFilter.isNotBlank()
    val summary =
        buildList {
            add(when (timeFilter) {
                TransactionTimeFilter.All -> "All time"
                TransactionTimeFilter.Week -> "Last 7 days"
                TransactionTimeFilter.Month -> "This month"
            })
            selectedCategory?.let { add(it) }
            if (sourceFilter != TransactionSourceFilter.All) add(sourceFilter.label)
            selectedAccountLabel?.let { add(it) }
            if (unreviewedOnly) add("Needs review")
            selectedCityLabel?.let { add(it) }
            if (selectedState != null && selectedCityLabel?.contains(selectedState) != true) add(selectedState)
            selectedCountry?.let { add(it) }
            if (whereFilter.isNotBlank()) add("“${whereFilter.trim()}”")
        }.joinToString(" · ")

    Column(
        modifier =
            Modifier.animateContentSize(
                animationSpec =
                    tween(
                        durationMillis = PennyMotion.DurationMedium,
                        easing = PennyMotion.StandardEasing,
                    ),
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = whereFilter,
            onValueChange = onWhereFilterChange,
            placeholder = { Text("Merchant, place, note, or account", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = if (whereFilter.isNotEmpty()) {
                { IconButton(onClick = { onWhereFilterChange("") }, modifier = Modifier.testTag("transaction_search_clear").semantics { contentDescription = "Clear search" }) { Text("×") } }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = flowTextFieldColors(),
            shape = MaterialTheme.shapes.large,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("transaction_where_filter"),
        )
        val filterLabel = if (activeCount > 0) "Filters · $activeCount" else "Filters"
        val labelStyle = MaterialTheme.typography.labelLarge
        val caretStyle = MaterialTheme.typography.bodyLarge
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val caret = if (isExpanded) "▴" else "▾"
        val desiredWidth = remember(filterLabel, caret, labelStyle, caretStyle, density, measurer) {
            with(density) {
                (measurer.measure(filterLabel, style = labelStyle, softWrap = false).size.width +
                    measurer.measure(caret, style = caretStyle, softWrap = false).size.width).toDp() + 20.dp
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().testTag("transaction_filter_controls")) {
            val filterWidth = desiredWidth.coerceAtLeast(48.dp).coerceAtMost((maxWidth - 104.dp).coerceAtLeast(48.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).selectableGroup().testTag("transaction_view_mode_group")) {
                    TransactionViewMode.entries.forEach { mode ->
                        val selected = viewMode == mode
                        Box(
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(MaterialTheme.shapes.large)
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                                .selectable(selected = selected, role = Role.RadioButton) { onViewModeChange(mode) }
                                .testTag("transaction_view_${mode.name.lowercase(Locale.US)}").padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(mode.label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge.copy(color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, textAlign = TextAlign.Center), maxLines = 1, softWrap = false, autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 14.sp))
                        }
                    }
                }
                Row(
                    modifier = Modifier.width(filterWidth).heightIn(min = 48.dp).clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(role = Role.Button) { onExpandedChange(!isExpanded) }
                        .semantics {
                            contentDescription = if (activeCount > 0) "Filters, $activeCount active" else "Filters"
                            stateDescription = if (isExpanded) "Expanded" else "Collapsed"
                        }
                        .padding(horizontal = 8.dp).testTag("transaction_filter_toggle"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(filterLabel, modifier = Modifier.weight(1f).testTag("transaction_filter_label"), style = labelStyle.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 1, softWrap = false, autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = labelStyle.fontSize))
                    Text(caret, color = MaterialTheme.colorScheme.primary, style = caretStyle, modifier = Modifier.testTag("transaction_filter_caret").clearAndSetSemantics { })
                }
            }
        }
        if (hasActiveFilter) {
            FlowRow(modifier = Modifier.fillMaxWidth().testTag("transaction_filter_summary_row"), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
                Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("transaction_filter_summary"))
                Box(Modifier.weight(1f).widthIn(min = 80.dp), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = onClear, modifier = Modifier.testTag("transaction_filter_clear_compact").semantics { contentDescription = "Clear all filters" }) { Text("Clear all") }
                }
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter =
                expandVertically(
                    animationSpec =
                        tween(
                            durationMillis = PennyMotion.DurationMedium,
                            easing = PennyMotion.StandardEasing,
                        ),
                ) +
                    fadeIn(
                        animationSpec =
                            tween(
                                durationMillis = PennyMotion.DurationShort,
                                easing = PennyMotion.StandardDecelerateEasing,
                            ),
                    ),
            exit =
                shrinkVertically(
                    animationSpec =
                        tween(
                            durationMillis = PennyMotion.DurationMedium,
                            easing = PennyMotion.StandardEasing,
                        ),
                ) +
                    fadeOut(
                        animationSpec =
                            tween(
                                durationMillis = PennyMotion.DurationShort,
                                easing = PennyMotion.StandardAccelerateEasing,
                            ),
                    ),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Range")
                FlowRow(
                    modifier =
                        Modifier
                            .selectableGroup()
                            .testTag("transaction_time_filter_group"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TransactionTimeFilter.entries.forEach { filter ->
                        SuggestionChip(
                            label = filter.label,
                            selected = timeFilter == filter,
                            testTag = "transaction_filter_${filter.name.lowercase(Locale.US)}",
                            onClick = { onTimeFilterChange(filter) },
                        )
                    }
                }
                SectionLabel("Source")
                FlowRow(
                    modifier =
                        Modifier
                            .selectableGroup()
                            .testTag("transaction_source_filter_group"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TransactionSourceFilter.entries.forEach { source ->
                        SuggestionChip(
                            label = source.label,
                            selected = sourceFilter == source,
                            testTag = "transaction_source_filter_${source.name.lowercase(Locale.US)}",
                            onClick = { onSourceFilterChange(source) },
                        )
                    }
                }
                if (accountOptions.isNotEmpty()) {
                    Box {
                        TextButton(
                            onClick = { accountMenuExpanded = true },
                            modifier =
                                Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("transaction_account_filter_action")
                                    .semantics {
                                        contentDescription = "Filter by account"
                                        stateDescription = selectedAccountLabel ?: "All accounts"
                                    },
                        ) { Text(selectedAccountLabel?.let { "Account: $it" } ?: "All accounts") }
                        DropdownMenu(
                            expanded = accountMenuExpanded,
                            onDismissRequest = { accountMenuExpanded = false },
                            modifier = Modifier.testTag("transaction_account_filter_menu"),
                        ) {
                            DropdownMenuItem(
                                text = { Text("All accounts") },
                                onClick = {
                                    accountMenuExpanded = false
                                    onAccountChange(null)
                                },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("transaction_account_filter_all"),
                            )
                            accountOptions.forEach { account ->
                                DropdownMenuItem(
                                    text = { Text(account.label) },
                                    onClick = {
                                        accountMenuExpanded = false
                                        onAccountChange(account.accountKey)
                                    },
                                    modifier =
                                        Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("transaction_account_filter_${account.accountKey}"),
                                )
                            }
                        }
                    }
                }
                SuggestionChip(
                    label = "Needs review only",
                    selected = unreviewedOnly,
                    testTag = "transaction_unreviewed_filter",
                    onClick = { onUnreviewedOnlyChange(!unreviewedOnly) },
                )
                SectionLabel("Category")
                FlowRow(
                    modifier =
                        Modifier
                            .selectableGroup()
                            .testTag("transaction_category_filter_group"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SuggestionChip(
                        label = "Any category",
                        selected = selectedCategory == null,
                        testTag = "transaction_category_filter_all",
                        onClick = { onCategoryChange(null) },
                    )
                    categories.forEach { category ->
                        SuggestionChip(
                            label = category,
                            selected = selectedCategory == category,
                            testTag = "transaction_category_filter_${category.toCategoryChipTagSuffix()}",
                            onClick = { onCategoryChange(category) },
                        )
                    }
                }
                if (cityOptions.isNotEmpty()) {
                    SectionLabel("City")
                    FlowRow(
                        modifier =
                            Modifier
                                .selectableGroup()
                                .testTag("transaction_city_filter_group"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SuggestionChip(
                            label = "Any city",
                            selected = selectedCity == null,
                            testTag = "transaction_city_filter_all",
                            onClick = { onCityOptionChange(null) },
                        )
                        cityOptions.forEach { option ->
                            val selected =
                                option.city == selectedCity &&
                                    (selectedState == null || option.state == selectedState) &&
                                    (selectedCountry == null || option.country == selectedCountry) &&
                                    (
                                        cityOptions.count { it.city == option.city } == 1 ||
                                            (option.state == selectedState && option.country == selectedCountry)
                                    )
                            SuggestionChip(
                                label = option.label,
                                selected = selected,
                                testTag = "transaction_city_filter_${option.label.toCategoryChipTagSuffix()}",
                                onClick = { onCityOptionChange(option) },
                            )
                        }
                    }
                }
                if (stateOptions.isNotEmpty()) {
                    SectionLabel("State")
                    FlowRow(
                        modifier =
                            Modifier
                                .selectableGroup()
                                .testTag("transaction_state_filter_group"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SuggestionChip(
                            label = "Any state",
                            selected = selectedState == null,
                            testTag = "transaction_state_filter_all",
                            onClick = { onStateChange(null) },
                        )
                        stateOptions.forEach { state ->
                            SuggestionChip(
                                label = state,
                                selected = selectedState == state,
                                testTag = "transaction_state_filter_${state.toCategoryChipTagSuffix()}",
                                onClick = { onStateChange(state) },
                            )
                        }
                    }
                }
                if (countryOptions.isNotEmpty()) {
                    SectionLabel("Country")
                    FlowRow(
                        modifier =
                            Modifier
                                .selectableGroup()
                                .testTag("transaction_country_filter_group"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SuggestionChip(
                            label = "Any country",
                            selected = selectedCountry == null,
                            testTag = "transaction_country_filter_all",
                            onClick = { onCountryChange(null) },
                        )
                        countryOptions.forEach { country ->
                            SuggestionChip(
                                label = country,
                                selected = selectedCountry == country,
                                testTag = "transaction_country_filter_${country.toCategoryChipTagSuffix()}",
                                onClick = { onCountryChange(country) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val CollapsedCategoryCount = 4

internal fun collapsedInsightCategories(totals: List<CategoryTotal>, keepOther: Boolean): List<CategoryTotal> {
    val first = totals.take(CollapsedCategoryCount)
    val other = totals.firstOrNull { it.category == "Other" }
    return if (keepOther && other != null && other !in first) first.take(CollapsedCategoryCount - 1) + other else first
}

@Composable
private fun InsightsPage(
    dailySpending: List<DailySpend>,
    categoryTotals: List<CategoryTotal>,
    rangeTransactions: List<Transaction>,
    dateRange: DashboardDateRange,
    chartRangeMode: ChartRangeMode,
    selectedMonth: YearMonth,
    availableMonths: List<YearMonth>,
    onChartRangeModeSelected: (ChartRangeMode) -> Unit,
    onSelectedMonthChange: (YearMonth) -> Unit,
    onEdit: (Transaction) -> Unit,
    onAddTransaction: () -> Unit,
    onData: () -> Unit,
    pendingReviewSummary: UnreviewedSpendingSummary,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedBucketKey by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var categoriesExpanded by remember(dateRange, chartRangeMode, selectedBucketKey) { mutableStateOf(false) }
    val buckets =
        remember(dailySpending, chartRangeMode, dateRange) {
            DashboardAnalytics.spendBuckets(dailySpending, chartRangeMode, dateRange)
        }
    val totalSpentCents = remember(dailySpending) { dailySpending.sumOf { it.cents } }

    val selectedBucket =
        remember(buckets, selectedBucketKey) {
            selectedBucketKey?.let { key -> buckets.firstOrNull { it.key == key } }
        }
    val bucketTransactions =
        remember(rangeTransactions, selectedBucket) {
            val bucket = selectedBucket ?: return@remember emptyList()
            rangeTransactions.filter {
                it.reportingSpendingCategory() != null &&
                    !it.localDate().isBefore(bucket.startInclusive) &&
                    it.localDate().isBefore(bucket.endExclusive)
            }
        }
    val visibleCategoryTotals =
        remember(selectedBucket, bucketTransactions, categoryTotals) {
            if (selectedBucket == null) {
                categoryTotals
            } else {
                DashboardAnalytics.categoryTotals(bucketTransactions, limit = Int.MAX_VALUE)
            }
        }
    val visibleTotalSpentCents = selectedBucket?.cents ?: totalSpentCents

    val keepOther = pendingReviewSummary.transactionCount > 0
    LaunchedEffect(buckets, visibleCategoryTotals, categoriesExpanded, keepOther) {
        if (selectedBucketKey != null && buckets.none { it.key == selectedBucketKey }) selectedBucketKey = null
        val available = if (categoriesExpanded) visibleCategoryTotals else collapsedInsightCategories(visibleCategoryTotals, keepOther)
        if (selectedCategory != null && available.none { it.category == selectedCategory }) {
            selectedCategory = null
        }
    }

    val selectedCategoryTotal =
        remember(visibleCategoryTotals, selectedCategory) {
            selectedCategory?.let { category -> visibleCategoryTotals.firstOrNull { it.category == category } }
        }
    val selectedTransactions =
        remember(rangeTransactions, selectedBucket, selectedCategoryTotal, bucketTransactions) {
            when {
                selectedBucket != null && selectedCategoryTotal != null -> {
                    bucketTransactions.filter {
                        it.reportingSpendingCategory() == selectedCategoryTotal.category
                    }
                }

                selectedBucket != null -> bucketTransactions

                selectedCategoryTotal != null -> {
                    rangeTransactions.filter {
                        it.reportingSpendingCategory() == selectedCategoryTotal.category
                    }
                }

                else -> {
                    emptyList()
                }
            }
        }
    val amountWidth = rememberTransactionAmountWidth(selectedTransactions)
    val insightTitle =
        selectedCategoryTotal?.let { category ->
            "${category.category} spending" + (selectedBucket?.let { " · ${it.label}" } ?: "")
        } ?: selectedBucket?.label
    val insightAmount =
        selectedCategoryTotal?.let { MoneyFormatter.formatUsd(it.cents) }
            ?: selectedBucket?.let { MoneyFormatter.formatUsd(it.cents) }

    Box(modifier = modifier) {
        LazyColumn(
            modifier =
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .testTag("insights_list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PagePadding,
        ) {
            item {
                InsightsHeroSummary(
                    totalCents = totalSpentCents,
                    range = dateRange,
                    dayCount = dailySpending.size,
                    hasTransfers = remember(rangeTransactions) { DashboardAnalytics.metrics(rangeTransactions).transferCents > 0 },
                )
            }
            item {
                DateRangeControls(
                    mode = chartRangeMode,
                    selectedMonth = selectedMonth,
                    dateRange = dateRange,
                    availableMonths = availableMonths,
                    onModeSelected = onChartRangeModeSelected,
                    onSelectedMonthChange = onSelectedMonthChange,
                    showRangeLabel = false,
                )
            }
            if (categoryTotals.isEmpty()) {
                item { InsightsEmptyState(onAddTransaction = onAddTransaction) }
            } else {
                item {
                    SpendingBucketsChart(
                        buckets = buckets,
                        selectedKey = selectedBucketKey,
                        onSelect = { bucket ->
                            selectedBucketKey = if (selectedBucketKey == bucket.key) null else bucket.key
                        },
                        range = dateRange,
                    )
                }
                item {
                    CategoryRankList(
                        totals = visibleCategoryTotals,
                        totalSpentCents = visibleTotalSpentCents,
                        selectedCategory = selectedCategory,
                        expanded = categoriesExpanded,
                        keepOther = keepOther,
                        onExpandedChange = { categoriesExpanded = it },
                        onSelect = { category ->
                            selectedCategory = if (selectedCategory == category) null else category
                        },
                    )
                }
                if (insightTitle != null && insightAmount != null) {
                    item {
                        InsightsSelectionHeader(
                            title = insightTitle,
                            amount = insightAmount,
                            isEmpty = selectedTransactions.isEmpty(),
                        )
                    }
                    items(selectedTransactions, key = { "insight_${it.id}" }) { transaction ->
                        TransactionRow(
                            transaction = transaction,
                            onClick = { onEdit(transaction) },
                            modifier = Modifier.testTag("insight_transaction_${transaction.id}"),
                            showDate = true,
                            amountWidth = amountWidth,
                        )
                    }
                }
            }
            if (pendingReviewSummary.transactionCount > 0) {
                item { PendingReviewInsightsCard(summary = pendingReviewSummary, onReview = onReview) }
            }
        }
    }
}

@Composable
private fun PendingReviewInsightsCard(
    summary: UnreviewedSpendingSummary,
    onReview: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag("insights_pending_review_card")) {
        val noun = if (summary.transactionCount == 1) "correction" else "corrections"
        Text("${summary.transactionCount} optional $noun · ${MoneyFormatter.formatUsd(summary.spentCents)} · included under Other", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("insights_pending_review_note"))
        TextButton(onClick = onReview, modifier = Modifier.heightIn(min = 48.dp).testTag("insights_pending_review_action")) { Text("Check & correct") }
    }
}

@Composable
private fun InsightsEmptyState(onAddTransaction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No spending in this range", fontWeight = FontWeight.SemiBold)
            Text(
                "Add an expense to see your timeline and categories.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onAddTransaction,
                colors = ButtonDefaults.buttonColors(),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.testTag("insights_empty_add"),
            ) { Text("Add transaction") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DataSheet(
    simpleFin: SimpleFinUiState,
    operation: DataOperation?,
    merchantRules: List<MerchantRuleEntity> = emptyList(),
    merchantRulesLoading: Boolean = false,
    onDeleteMerchantRule: (String) -> Unit = {},
    onOpenSetup: () -> Unit,
    onConnect: (String) -> Unit,
    onSync: () -> Unit,
    onAutomaticSyncsPerDayChange: (Int) -> Unit = {},
    onAutomaticSyncTimeChange: (LocalTime?) -> Unit = {},
    onCountResetDays: suspend (PennyLocalDateRange, ZoneId) -> TransactionRangeCount = { _, _ ->
        TransactionRangeCount(transactionCount = 0, tombstoneCount = 0)
    },
    onResetDays: (PennyLocalDateRange, ZoneId, TransactionRangeCount) -> Unit = { _, _, _ -> },
    resetDaysClock: Clock = Clock.systemDefaultZone(),
    resetDaysZoneId: ZoneId = ZoneId.systemDefault(),
    rangeResetPending: Boolean = false,
    resetDaysRecountRequest: Int = 0,
    resetDaysReselectRequest: Int = 0,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onDisconnect: () -> Unit,
    onClose: () -> Unit,
    onRetryConnection: () -> Unit,
    onCancelPendingConnection: () -> Unit,
    geminiKeySaved: Boolean = false,
    geminiStatus: GeminiRunStatus = GeminiRunStatus(),
    onSaveGeminiKey: (String) -> Unit = {},
    onClearGeminiKey: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val profile = simpleFin.profile
    val preferredSyncTime = simpleFin.preferredSyncTime
    var setupToken by remember { mutableStateOf("") }
    var automaticSyncsExpanded by rememberSaveable(profile?.connectionId, profile?.isPaused) { mutableStateOf(false) }
    var showSyncTimePicker by rememberSaveable(profile?.connectionId, profile?.isPaused) { mutableStateOf(false) }
    var ruleToDelete by remember { mutableStateOf<MerchantRuleEntity?>(null) }
    val isBusy = operation != null

    fun submitSetupToken() {
        val token = setupToken.trim()
        if (token.isNotBlank()) {
            setupToken = ""
            onConnect(token)
        }
    }
    val localDataSection: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DataSheetSectionHeader("Import & export")
            DataCard(modifier = Modifier.testTag("local_data_card")) {
                Text(
                    "Import transactions or export a portable CSV copy.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = onImport,
                        enabled = !isBusy,
                        colors = ButtonDefaults.buttonColors(),
                        shape = MaterialTheme.shapes.large,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("csv_import_button"),
                    ) { Text("Import CSV") }
                    Button(
                        onClick = onExport,
                        enabled = !isBusy,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        shape = MaterialTheme.shapes.large,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("csv_export_button"),
                    ) { Text("Export CSV") }
                }
            }
        }
    }
    val bankSyncSectionHeader: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DataSheetSectionHeader("Bank sync")
            SimpleFinConnectionStatus(simpleFin = simpleFin)
        }
    }
    val resetDaysCard: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DataSheetSectionHeader("Cleanup")
            SimpleFinResetDaysCard(
                operation = operation,
                onCountResetDays = onCountResetDays,
                onResetDays = onResetDays,
                clock = resetDaysClock,
                zoneId = resetDaysZoneId,
                rangeResetPending = rangeResetPending,
                externalRecountRequest = resetDaysRecountRequest,
                selectionGeneration = resetDaysReselectRequest,
            )
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f),
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Data", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, enabled = !isBusy) { Text("Close") }
            }

            operation?.let {
                Text(
                    text = "${it.label}…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .padding(top = 4.dp)
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                                stateDescription = it.label
                            }.testTag("data_operation_status"),
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("data_sheet_list"),
            ) {
                when {
                    simpleFin.isConnectionPending -> {
                        item { bankSyncSectionHeader() }
                        item {
                            DataCard(
                                modifier = Modifier.testTag("simplefin_pending_card"),
                                backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                Text("Connection pending", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Your setup token was accepted, but the first sync did not finish. " +
                                        "Retry without entering another token.",
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = onRetryConnection,
                                    enabled = !isBusy,
                                    colors = ButtonDefaults.buttonColors(),
                                    shape = MaterialTheme.shapes.large,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag("simplefin_retry_connection_button"),
                                ) { Text("Retry connection") }
                                TextButton(
                                    onClick = onCancelPendingConnection,
                                    enabled = !isBusy,
                                    modifier = Modifier.testTag("simplefin_cancel_pending_button"),
                                ) {
                                    Text("Cancel and start over", color = LocalFinanceColors.current.expense)
                                }
                            }
                        }
                        item { localDataSection() }
                        item { resetDaysCard() }
                    }

                    profile == null -> {
                        item { localDataSection() }
                        item { bankSyncSectionHeader() }
                        item {
                            DataCard(modifier = Modifier.testTag("simplefin_setup_card")) {
                                Text("Connect SimpleFIN", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Open SimpleFIN and copy a setup token.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = onOpenSetup, enabled = !isBusy) { Text("Open setup") }
                                Text(
                                    "Paste the token below, then connect.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = setupToken,
                                    onValueChange = { setupToken = it },
                                    label = { Text("Setup token") },
                                    singleLine = true,
                                    enabled = !isBusy,
                                    trailingIcon = {
                                        TextButton(
                                            onClick = ::submitSetupToken,
                                            enabled = !isBusy && setupToken.isNotBlank(),
                                        ) { Text("Connect") }
                                    },
                                    colors = flowTextFieldColors(),
                                    shape = MaterialTheme.shapes.medium,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag("simplefin_setup_token"),
                                )
                            }
                        }
                        item { resetDaysCard() }
                    }

                    profile.isPaused -> {
                        item { bankSyncSectionHeader() }
                        item {
                            DataCard(
                                modifier = Modifier.testTag("simplefin_setup_card"),
                                backgroundColor = MaterialTheme.colorScheme.errorContainer,
                            ) {
                                Text("Reconnect SimpleFIN", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Reconnect to resume bank sync. Your Penny data and CSV tools stay available.",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("simplefin_reconnect_message"),
                                )
                                Spacer(Modifier.height(10.dp))
                                StatusLine("Last sync", profile.lastSuccessfulSyncAtEpochMillis.toSyncTime())
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Open SimpleFIN and copy a new setup token.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                )
                                TextButton(onClick = onOpenSetup, enabled = !isBusy) { Text("Open setup") }
                                Text(
                                    "Paste the token below, then reconnect.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                )
                                OutlinedTextField(
                                    value = setupToken,
                                    onValueChange = { setupToken = it },
                                    label = { Text("New setup token") },
                                    singleLine = true,
                                    enabled = !isBusy,
                                    trailingIcon = {
                                        TextButton(
                                            onClick = ::submitSetupToken,
                                            enabled = !isBusy && setupToken.isNotBlank(),
                                        ) { Text("Reconnect") }
                                    },
                                    colors = flowTextFieldColors(),
                                    shape = MaterialTheme.shapes.medium,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag("simplefin_setup_token"),
                                )
                                TextButton(
                                    onClick = onDisconnect,
                                    enabled = !isBusy,
                                    modifier = Modifier.testTag("simplefin_disconnect_button"),
                                ) {
                                    Text("Disconnect", color = LocalFinanceColors.current.expense)
                                }
                            }
                        }
                        item { localDataSection() }
                        item { resetDaysCard() }
                    }

                    else -> {
                        item { bankSyncSectionHeader() }
                        item {
                            DataCard(modifier = Modifier.testTag("bank_sync_card")) {
                                Text("Status", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                StatusLine("Last sync", profile.lastSuccessfulSyncAtEpochMillis.toSyncTime())
                                StatusLine("Last error", if (profile.lastError.isNullOrBlank()) "None" else "Sync needs attention")
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(Modifier.height(10.dp))
                                Text("Schedule", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .semantics {
                                                contentDescription = "Automatic sync frequency"
                                                stateDescription = automaticSyncFrequencyLabel(profile.automaticSyncsPerDay)
                                            }.testTag("simplefin_auto_sync_frequency_control"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Automatic syncs", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            automaticSyncFrequencyLabel(profile.automaticSyncsPerDay),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.testTag("simplefin_auto_sync_frequency_value"),
                                        )
                                    }
                                    Box {
                                        TextButton(
                                            onClick = { automaticSyncsExpanded = true },
                                            enabled = !isBusy,
                                            modifier =
                                                Modifier
                                                    .heightIn(min = 48.dp)
                                                    .testTag("simplefin_auto_sync_frequency_action")
                                                    .semantics {
                                                        contentDescription = "Change automatic sync frequency"
                                                        stateDescription = automaticSyncFrequencyLabel(profile.automaticSyncsPerDay)
                                                    },
                                        ) { Text("Change") }
                                        DropdownMenu(
                                            expanded = automaticSyncsExpanded,
                                            onDismissRequest = { automaticSyncsExpanded = false },
                                            modifier = Modifier.testTag("simplefin_auto_sync_frequency_menu"),
                                        ) {
                                            (1..12).forEach { count ->
                                                DropdownMenuItem(
                                                    text = { Text(automaticSyncFrequencyLabel(count)) },
                                                    onClick = {
                                                        automaticSyncsExpanded = false
                                                        onAutomaticSyncsPerDayChange(count)
                                                    },
                                                    enabled = !isBusy,
                                                    modifier =
                                                        Modifier
                                                            .testTag("simplefin_auto_sync_frequency_option_$count")
                                                            .semantics {
                                                                contentDescription =
                                                                    "Set automatic sync frequency to ${automaticSyncFrequencyLabel(count)}"
                                                            },
                                                )
                                            }
                                        }
                                    }
                                }
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .semantics {
                                                contentDescription = "Automatic sync time"
                                                stateDescription = preferredSyncTime?.format(TimeFormatter) ?: "Any time"
                                            }.testTag("simplefin_sync_time_control"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Sync time", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            preferredSyncTime?.format(TimeFormatter) ?: "Any time",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.testTag("simplefin_sync_time_value"),
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (preferredSyncTime != null) {
                                            TextButton(
                                                onClick = { onAutomaticSyncTimeChange(null) },
                                                enabled = !isBusy,
                                                modifier =
                                                    Modifier
                                                        .heightIn(min = 48.dp)
                                                        .testTag("simplefin_sync_time_clear"),
                                            ) { Text("Clear") }
                                        }
                                        TextButton(
                                            onClick = { showSyncTimePicker = true },
                                            enabled = !isBusy,
                                            modifier =
                                                Modifier
                                                    .heightIn(min = 48.dp)
                                                    .testTag("simplefin_sync_time_action"),
                                        ) { Text("Change") }
                                    }
                                }
                                Text(
                                    "The first daily sync is scheduled at this time; later runs follow the interval. Timing is approximate and cannot guarantee SimpleFIN's 24-request daily limit.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(Modifier.height(10.dp))
                                Text("Actions", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Button(
                                    onClick = onSync,
                                    enabled = !isBusy,
                                    colors = ButtonDefaults.buttonColors(),
                                    shape = MaterialTheme.shapes.large,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag("simplefin_sync_button"),
                                ) { Text("Sync now") }
                                TextButton(
                                    onClick = onDisconnect,
                                    enabled = !isBusy,
                                    modifier = Modifier.testTag("simplefin_disconnect_button"),
                                ) {
                                    Text("Disconnect", color = LocalFinanceColors.current.expense)
                                }
                            }
                        }
                        item {
                            DataCard(modifier = Modifier.testTag("connected_accounts_card")) {
                                Text("Connected accounts", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                if (simpleFin.accounts.isEmpty()) {
                                    Text("No accounts synced yet", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                } else {
                                    simpleFin.accounts.forEach { account ->
                                        Text(account.name, fontWeight = FontWeight.SemiBold)
                                        account.balanceAmount?.let { balance ->
                                            Text(
                                                MoneyFormatter.formatUsd(
                                                    MoneyFormatter.parseAmountToCents(balance),
                                                ),
                                            )
                                        }
                                        Text(
                                            account.institutionName ?: account.currency.orEmpty(),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 13.sp,
                                        )
                                        if (account.availableBalanceAmount != null &&
                                            account.availableBalanceAmount != account.balanceAmount
                                        ) {
                                            Text(
                                                "Available ${
                                                    MoneyFormatter.formatUsd(
                                                        MoneyFormatter.parseAmountToCents(
                                                            account.availableBalanceAmount,
                                                        ),
                                                    )
                                                }",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 13.sp,
                                            )
                                        }
                                        account.balanceDateEpochSeconds?.let { seconds ->
                                            Text(
                                                "as of ${
                                                    Instant
                                                        .ofEpochMilli(seconds * 1000L)
                                                        .atZone(ZoneId.systemDefault())
                                                        .toLocalDateTime()
                                                        .format(ListDateFormatter)
                                                }",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 13.sp,
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }
                            }
                        }
                        item { localDataSection() }
                        item { resetDaysCard() }
                    }
                }
                item {
                    GeminiCategorizeCard(
                        keySaved = geminiKeySaved,
                        status = geminiStatus,
                        enabled = !isBusy,
                        onSaveKey = onSaveGeminiKey,
                        onClearKey = onClearGeminiKey,
                    )
                }
                item {
                    MerchantRulesCard(
                        rules = merchantRules,
                        loading = merchantRulesLoading,
                        enabled = !isBusy,
                        onDelete = { ruleToDelete = it },
                    )
                }
            }
        }
    }

    ruleToDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { if (!isBusy) ruleToDelete = null },
            title = { Text("Delete merchant rule?") },
            text = {
                Text(
                    "Delete rule “${rule.normalizedProviderMerchant}” (${rule.category})? " +
                        "Existing transactions will not be rewritten.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ruleToDelete = null
                        onDeleteMerchantRule(rule.normalizedProviderMerchant)
                    },
                    enabled = !isBusy,
                    modifier = Modifier.testTag("confirm_delete_merchant_rule"),
                ) { Text("Delete rule", color = LocalFinanceColors.current.expense) }
            },
            dismissButton = {
                TextButton(onClick = { ruleToDelete = null }, enabled = !isBusy) { Text("Cancel") }
            },
        )
    }

    if (showSyncTimePicker) {
        PennyRichTimePickerDialog(
            initialHour = preferredSyncTime?.hour ?: LocalTime.now().hour,
            initialMinute = preferredSyncTime?.minute ?: LocalTime.now().minute,
            is24Hour = true,
            onDismiss = { showSyncTimePicker = false },
            onConfirm = { hour, minute ->
                showSyncTimePicker = false
                onAutomaticSyncTimeChange(LocalTime.of(hour, minute))
            },
        )
    }
}

private fun geminiRunRelativeTime(fromEpochMillis: Long, nowEpochMillis: Long): String {
    val elapsed = (nowEpochMillis - fromEpochMillis).coerceAtLeast(0L)
    val minutes = elapsed / 60_000L
    val hours = elapsed / 3_600_000L
    val days = elapsed / 86_400_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "$minutes min ago"
        hours < 24L -> "$hours hr ago"
        days == 1L -> "1 day ago"
        else -> "$days days ago"
    }
}

private fun geminiRunStatusLine(status: GeminiRunStatus, nowEpochMillis: Long = System.currentTimeMillis()): String {
    if (status.lastFailed) return "Last run failed · retries after the next sync"
    val lastRun = status.lastRunAtEpochMillis ?: return "Runs after the next sync"
    val relative = geminiRunRelativeTime(lastRun, nowEpochMillis)
    return if (status.lastQueueEmpty) {
        "Last run $relative · nothing to categorize"
    } else {
        "Last run $relative · ${status.lastLabeled} categorized and confirmed"
    }
}

@Composable
private fun GeminiCategorizeCard(
    keySaved: Boolean,
    status: GeminiRunStatus,
    enabled: Boolean,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DataSheetSectionHeader("Auto-categorize")
        DataCard(modifier = Modifier.testTag("gemini_key_card")) {
            Text(
                "Paste a Google AI Studio key. After each sync, up to $GEMINI_CATEGORIZE_CHUNK_SIZE uncategorized transactions are labelled and marked reviewed automatically. Transactions you already reviewed and merchant-rule categories are never changed. Anything Gemini can't label stays in Review.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (keySaved) "Auto-categorize on · key saved" else "Auto-categorize off · no key saved",
                modifier = Modifier.testTag("gemini_key_status"),
            )
            if (keySaved) {
                Text(
                    geminiRunStatusLine(status),
                    modifier = Modifier.testTag("gemini_key_run_status"),
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = enabled,
                colors = flowTextFieldColors(),
                shape = MaterialTheme.shapes.medium,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("gemini_key_field"),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val key = apiKey
                    apiKey = ""
                    onSaveKey(key)
                },
                enabled = enabled && apiKey.isNotBlank(),
                colors = ButtonDefaults.buttonColors(),
                shape = MaterialTheme.shapes.large,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("gemini_key_save_button"),
            ) { Text("Save") }
            if (keySaved) {
                TextButton(
                    onClick = onClearKey,
                    enabled = enabled,
                    modifier = Modifier.testTag("gemini_key_clear_button"),
                ) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun MerchantRulesCard(
    rules: List<MerchantRuleEntity>,
    loading: Boolean,
    enabled: Boolean,
    onDelete: (MerchantRuleEntity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DataSheetSectionHeader("Merchant rules")
        DataCard(modifier = Modifier.testTag("merchant_rules_card")) {
            when {
                loading -> {
                    Text("Loading saved rules…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                rules.isEmpty() -> {
                    Text("No saved merchant rules", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> {
                    rules.forEachIndexed { index, rule ->
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("merchant_rule_$index"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(rule.normalizedProviderMerchant, fontWeight = FontWeight.SemiBold)
                                Text(
                                    buildString {
                                        append(rule.category)
                                        rule.merchantOverride?.let { append(" · Display: $it") }
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(
                                onClick = { onDelete(rule) },
                                enabled = enabled,
                                modifier =
                                    Modifier
                                        .heightIn(min = 48.dp)
                                        .semantics { contentDescription = "Delete rule ${rule.normalizedProviderMerchant}" }
                                        .testTag("delete_merchant_rule_$index"),
                            ) { Text("Delete") }
                        }
                        if (index < rules.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Deleting a rule does not rewrite historical transactions.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private data class ResetDaysCountKey(
    val range: PennyLocalDateRange,
    val zoneId: ZoneId,
    val localRequest: Int,
    val externalRequest: Int,
)

private sealed interface ResetDaysCountState {
    data object Idle : ResetDaysCountState

    data class Loading(
        val key: ResetDaysCountKey,
    ) : ResetDaysCountState

    data class Loaded(
        val key: ResetDaysCountKey,
        val count: TransactionRangeCount,
    ) : ResetDaysCountState

    data class Failed(
        val key: ResetDaysCountKey,
        val message: String,
    ) : ResetDaysCountState
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimpleFinResetDaysCard(
    operation: DataOperation?,
    onCountResetDays: suspend (PennyLocalDateRange, ZoneId) -> TransactionRangeCount,
    onResetDays: (PennyLocalDateRange, ZoneId, TransactionRangeCount) -> Unit,
    clock: Clock,
    zoneId: ZoneId,
    rangeResetPending: Boolean,
    externalRecountRequest: Int,
    selectionGeneration: Int,
) {
    var selectedStartEpochDay by rememberSaveable(selectionGeneration) { mutableStateOf<Long?>(null) }
    var selectedEndEpochDay by rememberSaveable(selectionGeneration) { mutableStateOf<Long?>(null) }
    var showPicker by rememberSaveable(selectionGeneration) { mutableStateOf(false) }
    var countRequest by remember(selectionGeneration) { mutableStateOf(0) }
    var countState by remember(selectionGeneration) { mutableStateOf<ResetDaysCountState>(ResetDaysCountState.Idle) }
    val latestCountResetDays by rememberUpdatedState(onCountResetDays)
    val selectedRange =
        remember(selectedStartEpochDay, selectedEndEpochDay) {
            val startEpochDay = selectedStartEpochDay
            val endEpochDay = selectedEndEpochDay
            if (startEpochDay == null || endEpochDay == null) {
                null
            } else {
                runCatching {
                    PennyLocalDateRange(
                        startInclusive = LocalDate.ofEpochDay(startEpochDay),
                        endExclusive = LocalDate.ofEpochDay(endEpochDay),
                    )
                }.getOrNull()
            }
        }
    val countKey =
        selectedRange?.let { range ->
            ResetDaysCountKey(
                range = range,
                zoneId = zoneId,
                localRequest = countRequest,
                externalRequest = externalRecountRequest,
            )
        }
    val visibleCountState =
        when (val state = countState) {
            ResetDaysCountState.Idle -> countKey?.let { ResetDaysCountState.Loading(it) } ?: ResetDaysCountState.Idle
            is ResetDaysCountState.Loading -> if (state.key == countKey) state else countKey?.let { ResetDaysCountState.Loading(it) }
            is ResetDaysCountState.Loaded -> if (state.key == countKey) state else countKey?.let { ResetDaysCountState.Loading(it) }
            is ResetDaysCountState.Failed -> if (state.key == countKey) state else countKey?.let { ResetDaysCountState.Loading(it) }
        } ?: ResetDaysCountState.Idle
    val isBusy = operation != null || rangeResetPending
    val isCounting = visibleCountState is ResetDaysCountState.Loading

    LaunchedEffect(countKey) {
        val key = countKey
        if (key == null) {
            countState = ResetDaysCountState.Idle
            return@LaunchedEffect
        }
        countState = ResetDaysCountState.Loading(key)
        countState =
            try {
                ResetDaysCountState.Loaded(key, latestCountResetDays(key.range, key.zoneId))
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                ResetDaysCountState.Failed(key, "Could not count affected data. Try again.")
            }
    }

    DataCard(
        modifier = Modifier.testTag("simplefin_reset_days_card"),
        backgroundColor = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            "Reset days",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Remove selected days from Penny, including transactions and SimpleFIN deletion history. " +
                "A later bank sync can re-download SimpleFIN data only while those days remain in its 45-day window. " +
                "Manually added and CSV-imported rows cannot be downloaded.",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (rangeResetPending) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Undo or dismiss the current reset before resetting more days.",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("simplefin_reset_days_pending_message"),
            )
        }
        Spacer(Modifier.height(12.dp))
        selectedRange?.let { range ->
            Text(
                resetDaysRangeLabel(range),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("simplefin_reset_days_range"),
            )
            Spacer(Modifier.height(6.dp))
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            when (val state = visibleCountState) {
                ResetDaysCountState.Idle -> {
                    Text(
                        "Choose a range to see how many items are affected.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("simplefin_reset_days_count"),
                    )
                }

                is ResetDaysCountState.Loading -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("simplefin_reset_days_count"),
                    ) {
                        CircularProgressIndicator(
                            modifier =
                                Modifier
                                    .size(18.dp)
                                    .testTag("simplefin_reset_days_count_busy"),
                        )
                        Text(
                            "Counting affected data…",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is ResetDaysCountState.Loaded -> {
                    Text(
                        resetDaysCountLabel(state.count),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("simplefin_reset_days_count"),
                    )
                }

                is ResetDaysCountState.Failed -> {
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("simplefin_reset_days_count"),
                    )
                    TextButton(
                        onClick = { countRequest += 1 },
                        enabled = !isBusy,
                        modifier = Modifier.testTag("simplefin_reset_days_count_retry_button"),
                    ) { Text("Retry count") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = { showPicker = true },
                enabled = !isBusy && !isCounting,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                shape = MaterialTheme.shapes.large,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("simplefin_reset_days_picker_button"),
            ) { Text(if (selectedRange == null) "Choose days" else "Change days") }
            val loadedCount = (visibleCountState as? ResetDaysCountState.Loaded)?.count
            Button(
                onClick = {
                    val range = selectedRange ?: return@Button
                    val count = loadedCount ?: return@Button
                    val key = countKey ?: return@Button
                    if (!isSimpleFinResetRangeCurrent(range, clock, zoneId)) {
                        countState =
                            ResetDaysCountState.Failed(
                                key,
                                "These days are outside SimpleFIN's current 45-day window. Choose again.",
                            )
                        return@Button
                    }
                    onResetDays(range, zoneId, count)
                },
                enabled = !isBusy && selectedRange != null && loadedCount != null,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                shape = MaterialTheme.shapes.large,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("simplefin_reset_days_reset_button"),
            ) { Text("Reset…") }
        }
    }

    if (showPicker) {
        val initialRange =
            selectedRange ?: simpleFinResyncPickerRange(clock, zoneId).let { selectableRange ->
                PennyLocalDateRange(selectableRange.lastInclusive, selectableRange.endExclusive)
            }
        PennySimpleFinDateRangePickerDialog(
            initialRange = initialRange,
            onDismiss = {
                if (!isBusy) showPicker = false
            },
            onConfirm = { range ->
                if (!isBusy) {
                    selectedStartEpochDay = range.startInclusive.toEpochDay()
                    selectedEndEpochDay = range.endExclusive.toEpochDay()
                    countRequest += 1
                    showPicker = false
                }
            },
            modifier = Modifier.testTag("simplefin_reset_days_picker_dialog"),
            clock = clock.withZone(zoneId),
        )
    }
}

@Composable
private fun ResetDaysConfirmationDialog(
    request: ResetDaysRequest,
    operation: DataOperation?,
    failureMessage: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isBusy = operation != null
    AlertDialog(
        onDismissRequest = {
            if (!isBusy) onDismiss()
        },
        title = { Text("Reset these days?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    resetDaysRangeLabel(request.range),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("simplefin_reset_days_confirmation_range"),
                )
                Text(
                    resetDaysCountLabel(request.count),
                    modifier = Modifier.testTag("simplefin_reset_days_confirmation_count"),
                )
                Text(
                    "This removes these days from Penny, including manually added and CSV-imported transactions " +
                        "and SimpleFIN deletion records. Only SimpleFIN data can be re-downloaded by a later bank sync, " +
                        "and only within its 45-day window.",
                )
                failureMessage?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier
                                .semantics { liveRegion = LiveRegionMode.Assertive }
                                .testTag("simplefin_reset_days_failure"),
                    )
                }
                if (isBusy) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.testTag("simplefin_reset_days_busy"),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Text(operation?.label.orEmpty() + "…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isBusy,
                modifier = Modifier.testTag("simplefin_reset_days_confirm_button"),
            ) { Text("Reset", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isBusy,
                modifier = Modifier.testTag("simplefin_reset_days_cancel_button"),
            ) { Text("Cancel") }
        },
        modifier = Modifier.testTag("simplefin_reset_days_confirmation_dialog"),
    )
}

@Composable
private fun DataSheetSectionHeader(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun SimpleFinConnectionStatus(
    simpleFin: SimpleFinUiState,
    modifier: Modifier = Modifier,
) {
    val status =
        when {
            simpleFin.profile == null -> "Not connected"
            simpleFin.profile.isPaused -> "Reconnect required"
            else -> "Connected"
        }
    val statusColor =
        when {
            simpleFin.profile == null -> MaterialTheme.colorScheme.onSurfaceVariant
            simpleFin.profile.isPaused -> LocalFinanceColors.current.expense
            else -> MaterialTheme.colorScheme.secondary
        }
    DataCard(
        modifier =
            modifier
                .testTag("simplefin_connection_status")
                .semantics(mergeDescendants = true) {
                    contentDescription = "SimpleFIN connection status"
                    stateDescription = status
                },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "SimpleFIN",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                status,
                color = statusColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DataCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors =
            CardDefaults.cardColors(
                containerColor = backgroundColor ?: MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.38f),
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.62f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun DateRangeControls(
    mode: ChartRangeMode,
    selectedMonth: YearMonth,
    dateRange: DashboardDateRange,
    availableMonths: List<YearMonth>,
    onModeSelected: (ChartRangeMode) -> Unit,
    onSelectedMonthChange: (YearMonth) -> Unit,
    showRangeLabel: Boolean = true,
) {
    var monthPickerExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(mode) {
        if (mode != ChartRangeMode.Month) {
            monthPickerExpanded = false
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("date_range_controls"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showRangeLabel) {
                Text(dateRange.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            PennyBinaryChoice(
                    firstLabel = ChartRangeMode.Week.label,
                    firstSelected = mode == ChartRangeMode.Week,
                    onFirstClick = {
                        monthPickerExpanded = false
                        onModeSelected(ChartRangeMode.Week)
                    },
                    firstModifier = Modifier.testTag("range_week"),
                    secondLabel = ChartRangeMode.Month.label,
                    secondSelected = mode == ChartRangeMode.Month,
                    onSecondClick = {
                        monthPickerExpanded = if (mode == ChartRangeMode.Month) !monthPickerExpanded else true
                        onModeSelected(ChartRangeMode.Month)
                    },
                    secondModifier = Modifier.testTag("range_month"),
                    modifier = Modifier.testTag("range_mode_group"),
                )
        }
        AnimatedVisibility(
            visible = mode == ChartRangeMode.Month && monthPickerExpanded,
            enter =
                fadeIn(
                    tween(
                        durationMillis = PennyMotion.DurationShort,
                        easing = PennyMotion.StandardDecelerateEasing,
                    ),
                ) +
                    expandVertically(
                        tween(
                            durationMillis = PennyMotion.DurationMedium,
                            easing = PennyMotion.StandardEasing,
                        ),
                    ),
            exit =
                fadeOut(
                    tween(
                        durationMillis = PennyMotion.DurationShort,
                        easing = PennyMotion.StandardAccelerateEasing,
                    ),
                ) +
                    shrinkVertically(
                        tween(
                            durationMillis = PennyMotion.DurationMedium,
                            easing = PennyMotion.StandardEasing,
                        ),
                    ),
        ) {
            MonthYearPicker(
                selectedMonth = selectedMonth,
                availableMonths = availableMonths,
                onSelectedMonthChange = { month ->
                    onSelectedMonthChange(month)
                    monthPickerExpanded = false
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthYearPicker(
    selectedMonth: YearMonth,
    availableMonths: List<YearMonth>,
    onSelectedMonthChange: (YearMonth) -> Unit,
) {
    val pickerMonths = remember(availableMonths) { availableMonths.toSet() }
    val availableYears =
        remember(availableMonths) {
            availableMonths.map { it.year }.distinct().sortedDescending()
        }
    var visibleYear by rememberSaveable { mutableStateOf(selectedMonth.year) }
    LaunchedEffect(selectedMonth, availableYears) {
        visibleYear =
            when {
                selectedMonth.year in availableYears -> selectedMonth.year
                visibleYear in availableYears -> visibleYear
                else -> availableYears.first()
            }
    }
    val olderYear =
        remember(visibleYear, availableYears) {
            availableYears.filter { it < visibleYear }.maxOrNull()
        }
    val newerYear =
        remember(visibleYear, availableYears) {
            availableYears.filter { it > visibleYear }.minOrNull()
        }
    val visibleMonths =
        remember(visibleYear, pickerMonths) {
            pickerMonths.filter { it.year == visibleYear }.sortedBy { it.monthValue }
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("month_year_picker"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PickerNavButton(
                text = "<",
                contentDescription = "Previous year",
                enabled = olderYear != null,
                onClick = { olderYear?.let { visibleYear = it } },
                modifier = Modifier.testTag("month_year_previous"),
            )
            Text(
                text = visibleYear.toString(),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
            )
            PickerNavButton(
                text = ">",
                contentDescription = "Next year",
                enabled = newerYear != null,
                onClick = { newerYear?.let { visibleYear = it } },
                modifier = Modifier.testTag("month_year_next"),
            )
        }
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            visibleMonths.forEach { option ->
                MonthChip(
                    month = option,
                    selected = selectedMonth == option,
                    onClick = { onSelectedMonthChange(option) },
                )
            }
        }
    }
}

@Composable
private fun MonthChip(
    month: YearMonth,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.large)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .testTag("month_chip_${month.year}_${month.monthValue.toString().padStart(2, '0')}")
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = month.format(MonthLabelFormatter),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun PickerNavButton(
    text: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .widthIn(min = 48.dp)
                .clip(MaterialTheme.shapes.large)
                .background(if (enabled) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color =
                if (enabled) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(
                        alpha = 0.35f,
                    )
                },
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryBand(metrics: DashboardMetrics) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Spent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(MoneyFormatter.formatUsd(metrics.spentCents), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Income", style = MaterialTheme.typography.bodySmall)
                Text(MoneyFormatter.formatUsd(metrics.incomeCents), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Net", style = MaterialTheme.typography.bodySmall)
                Text(MoneyFormatter.formatUsd(metrics.netCents), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            "${metrics.transactionCount} ${if (metrics.transactionCount == 1) "transaction" else "transactions"}" +
                if (metrics.transferCents > 0) " · Transfers excluded" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InsightsHeroSummary(
    totalCents: Long,
    range: DashboardDateRange,
    dayCount: Int,
    hasTransfers: Boolean,
) {
    val averageCents = if (dayCount <= 0) 0L else totalCents / dayCount
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = "Spent", style = MaterialTheme.typography.titleMedium)
        Text(
            text = MoneyFormatter.formatUsd(totalCents),
            style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.testTag("insights_total_amount"),
        )
        Text(
            text = "${range.label} · avg ${MoneyFormatter.formatUsd(averageCents)}/day" +
                if (hasTransfers) " · Transfers excluded" else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("insights_total_caption"),
        )
    }
}

/**
 * Hit targets, stated honestly: at the 360 dp reference width, [PagePadding]
 * (`horizontal = 20.dp`) leaves 320 dp, so a 7-bucket week row gives cells of
 * ≈44 × 112 dp — 4 dp under the 48 dp width guidance. This is a deliberate
 * deviation: the cell is full chart height, the whole cell (not just the drawn
 * bar) is the target, [Arrangement.spacedBy] is kept at 2.dp to maximise it,
 * and every cell carries `selectable` semantics so TalkBack and keyboard focus
 * reach it independently of width. Month buckets (4–5 cells, ≈62 dp) clear 48 dp.
 */
@Composable
private fun SpendingBucketsChart(
    buckets: List<SpendBucket>,
    selectedKey: String?,
    onSelect: (SpendBucket) -> Unit,
    range: DashboardDateRange,
) {
    val total = remember(buckets) { buckets.sumOf { it.cents } }
    val peak = remember(buckets) { buckets.maxOfOrNull { it.cents } ?: 0L }
    val selectionColor = LocalFinanceColors.current.expense
    val unselectedColor = MaterialTheme.colorScheme.outline
    val chartSummary =
        buildString {
            append("Spending timeline for ${range.label}. Total ${MoneyFormatter.formatUsd(total)}. ")
            append("Peak ${MoneyFormatter.formatUsd(peak)}.")
        }
    val firstIndex = 0
    val lastIndex = buckets.lastIndex
    val middleIndex = if (buckets.isEmpty()) 0 else buckets.size / 2

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("When you spent", fontWeight = FontWeight.SemiBold)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .semantics { contentDescription = chartSummary }
                    .testTag("spending_timeline_chart"),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                buckets.forEach { bucket ->
                    val selected = selectedKey == bucket.key
                    val amount = MoneyFormatter.formatUsd(bucket.cents)
                    val fraction =
                        when {
                            bucket.cents <= 0L || peak <= 0L -> 0f
                            else -> (bucket.cents.toFloat() / peak.toFloat()).coerceIn(0f, 1f)
                        }
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag("insight_bucket_${bucket.key}")
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(bucket) },
                                ).semantics(mergeDescendants = true) {
                                    contentDescription = "${bucket.label}, $amount"
                                    stateDescription = if (selected) "Selected" else "Not selected"
                                },
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 2.dp)
                                    .fillMaxWidth()
                                    .then(
                                        if (bucket.cents <= 0L) {
                                            Modifier.height(2.dp)
                                        } else {
                                            Modifier.fillMaxHeight(fraction)
                                        },
                                    ).clip(MaterialTheme.shapes.extraSmall)
                                    .background(if (selected) selectionColor else unselectedColor),
                        )
                    }
                }
            }
        }
        if (buckets.isNotEmpty() && LocalDensity.current.fontScale >= 1.3f) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(buckets.first().startInclusive.format(ShortDateFormatter), style = MaterialTheme.typography.labelSmall)
                Text(buckets.last().endExclusive.minusDays(1).format(ShortDateFormatter), style = MaterialTheme.typography.labelSmall)
            }
        } else if (buckets.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                buckets.forEachIndexed { index, bucket ->
                    val showLabel =
                        index == firstIndex ||
                            index == lastIndex ||
                            (index == middleIndex && middleIndex != firstIndex && middleIndex != lastIndex)
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        if (showLabel) {
                            Text(
                                text = bucket.label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRankList(
    totals: List<CategoryTotal>,
    totalSpentCents: Long,
    selectedCategory: String?,
    expanded: Boolean,
    keepOther: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    val amountStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val chartColors =
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.secondary,
            LocalFinanceColors.current.expense,
        )
    val collapsed = remember(totals, keepOther) { collapsedInsightCategories(totals, keepOther) }
    val shown = if (expanded) totals else collapsed
    val omitted = remember(totals, collapsed) { totals.filterNot { it in collapsed } }
    val amountWidth = remember(totals, amountStyle, measurer, density) {
        totals.maxOfOrNull { measurer.measure(MoneyFormatter.formatUsd(it.cents), style = amountStyle, softWrap = false).size.width } ?: 0
    }
    val percentStyle = MaterialTheme.typography.labelSmall
    val percentWidth = remember(percentStyle, measurer, density) {
        listOf("100%", "<1%").maxOf { measurer.measure(it, style = percentStyle, softWrap = false).size.width }
    }
    val chartSummary = shown.joinToString(prefix = "Top categories. ", separator = ". ") { "${it.category} ${MoneyFormatter.formatUsd(it.cents)}" } +
        if (!expanded && omitted.isNotEmpty()) ". ${omitted.size} more" else ""

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = chartSummary }
                .testTag("category_breakdown_chart"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Top categories", fontWeight = FontWeight.SemiBold)
        Column(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            shown.forEachIndexed { index, entry ->
                val selected = selectedCategory == entry.category
                val amount = MoneyFormatter.formatUsd(entry.cents)
                val fraction =
                    if (totalSpentCents <= 0L) {
                        0f
                    } else {
                        (entry.cents.toFloat() / totalSpentCents.toFloat()).coerceIn(0f, 1f)
                    }
                val sharePercent =
                    if (totalSpentCents <= 0L) {
                        0
                    } else {
                        ((entry.cents * 100.0) / totalSpentCents).roundToInt()
                    }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    Color.Transparent
                                },
                            ).testTag("insight_category_${entry.category.toCategoryChipTagSuffix()}")
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(entry.category) },
                            ).semantics(mergeDescendants = true) {
                                contentDescription = "${entry.category}, $amount"
                                stateDescription = if (selected) "Selected" else "Not selected"
                            }.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.category,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = amount,
                            style = amountStyle,
                            modifier = Modifier.width(with(density) { amountWidth.toDp() } + 2.dp),
                            textAlign = TextAlign.End,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (entry.cents > 0 && totalSpentCents > 0 && entry.cents.toDouble() * 100 < totalSpentCents) "<1%" else "$sharePercent%",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = percentStyle,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(with(density) { percentWidth.toDp() } + 2.dp),
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth(fraction)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(chartColors[index % chartColors.size]),
                        )
                    }
                }
            }
        }
        if (omitted.isNotEmpty()) {
            val label = if (expanded) "Show less" else {
                val noun = if (omitted.size == 1) "category" else "categories"
                "${omitted.size} more $noun · ${MoneyFormatter.formatUsd(omitted.sumOf { it.cents })}"
            }
            TextButton(onClick = { onExpandedChange(!expanded) }, modifier = Modifier.testTag("insight_categories_expand").semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) { Text(label) }
        }
    }
}

@Composable
private fun InsightsSelectionHeader(
    title: String,
    amount: String,
    isEmpty: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                amount,
                color = LocalFinanceColors.current.expense,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            )
        }
        if (isEmpty) {
            Text(
                "No matching expense transactions.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TransactionsHeader(
    metrics: DashboardMetrics,
    hasTransfers: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Transactions",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Spent ${MoneyFormatter.formatUsd(metrics.spentCents)} · ${metrics.transactionCount} ${if (metrics.transactionCount == 1) "transaction" else "transactions"}" +
                if (hasTransfers) " · Transfers excluded" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState(
    onAddTransaction: () -> Unit,
    onData: () -> Unit,
    modifier: Modifier = Modifier,
    disconnected: Boolean = false,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text("$", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text("Start tracking", fontWeight = FontWeight.SemiBold)
            Text(
                if (disconnected) {
                    "Connect your bank to track synced spending, or add a transaction manually."
                } else {
                    "Add your first transaction, or import a CSV from Data."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val addButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                    Button(
                        onClick = onAddTransaction,
                        colors =
                            if (disconnected) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                        shape = MaterialTheme.shapes.large,
                        modifier = buttonModifier.heightIn(min = 48.dp).testTag("empty_add_transaction"),
                    ) { Text("Add transaction") }
                }
                val dataButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                    Button(
                        onClick = onData,
                        colors =
                            if (disconnected) {
                                ButtonDefaults.buttonColors()
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            },
                        shape = MaterialTheme.shapes.large,
                        modifier = buttonModifier.heightIn(min = 48.dp).testTag("empty_data_action"),
                    ) { Text(if (disconnected) "Connect your bank" else "Import from Data") }
                }
                val primary = if (disconnected) dataButton else addButton
                val secondary = if (disconnected) addButton else dataButton
                if (maxWidth < 480.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        primary(Modifier.fillMaxWidth())
                        secondary(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        primary(Modifier.weight(1f))
                        secondary(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeTransactionRow(
    transaction: Transaction,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    amountWidth: Int,
    showDate: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val latestOnEdit by rememberUpdatedState(onEdit)
    val latestOnDelete by rememberUpdatedState(onDelete)
    var gestureGeneration by remember(transaction.id) { mutableStateOf(0) }

    key(gestureGeneration) {
        val dismissState = rememberSwipeToDismissBoxState()
        LaunchedEffect(dismissState.currentValue) {
            when (dismissState.currentValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    gestureGeneration += 1
                    latestOnEdit()
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    gestureGeneration += 1
                    latestOnDelete()
                }

                SwipeToDismissBoxValue.Settled -> {
                    Unit
                }
            }
        }

        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {
                SwipeActionBackground(dismissDirection = dismissState.dismissDirection)
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("transaction_row"),
        ) {
            TransactionRow(transaction = transaction, onClick = onEdit, onDelete = onDelete, showDate = showDate, amountWidth = amountWidth)
        }
    }
}

@Composable
private fun SwipeActionBackground(dismissDirection: SwipeToDismissBoxValue) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        when (dismissDirection) {
            SwipeToDismissBoxValue.StartToEnd -> {
                Text(
                    text = "Edit",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 18.dp),
                )
            }

            SwipeToDismissBoxValue.EndToStart -> {
                Text(
                    text = "Delete",
                    color = LocalFinanceColors.current.expense,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 18.dp),
                )
            }

            SwipeToDismissBoxValue.Settled -> {
                Unit
            }
        }
    }
}

@Composable
internal fun TransactionRow(
    transaction: Transaction,
    amountWidth: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    showDate: Boolean = false,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .testTag("transaction_content_${transaction.id}")
                .clickable(onClickLabel = "Edit", onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = transaction.rowAccessibility()
                    customActions =
                        buildList {
                            add(
                                CustomAccessibilityAction("Edit") {
                                    onClick()
                                    true
                                },
                            )
                            onDelete?.let { delete ->
                                add(
                                    CustomAccessibilityAction("Delete") {
                                        delete()
                                        true
                                    },
                                )
                            }
                        }
                },
    ) {
        TransactionPresentation(transaction, amountWidth = amountWidth, showDate = showDate, padded = true)
    }
}

internal fun transactionAmountCandidates(transactions: List<Transaction>): List<Int> {
    var negative: Int? = null
    var positive: Int? = null
    for (transaction in transactions) {
        val cents = transaction.cents
        if (cents < 0 && (negative == null || cents.toLong() < negative.toLong())) negative = cents
        if (cents >= 0 && (positive == null || cents > positive)) positive = cents
    }
    return listOfNotNull(negative, positive)
}

internal fun transactionAmountWidth(transactions: List<Transaction>, measure: (String) -> Int): Int =
    transactionAmountCandidates(transactions).maxOfOrNull { measure(MoneyFormatter.formatUsd(it)) } ?: 0

@Composable
internal fun rememberTransactionAmountWidth(transactions: List<Transaction>): Int {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
    val density = LocalDensity.current
    val resolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    return remember(transactions, measurer, style, density, resolver) {
        transactionAmountWidth(transactions) { text ->
            measurer.measure(text, style = style, softWrap = false).size.width
        }
    }
}

@Composable
private fun TransactionMetadata(transaction: Transaction, showDate: Boolean, availablePx: Float) {
    val (category, place, temporal) = transaction.rowMetadata(showDate)
    val style = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val temporalWidth = remember(temporal, style, density, measurer) {
        if (temporal.isEmpty()) 0 else measurer.measure(temporal, style = style, softWrap = false).size.width
    }
    val hasRepeat = transaction.recurringInterval != null
    val metadataPx = (availablePx - if (hasRepeat) with(density) { 18.dp.toPx() } else 0f).coerceAtLeast(0f)
    val together = temporal.isEmpty() || metadataPx >= temporalWidth + with(density) { 74.dp.toPx() }
    val flexibleWidth = if (together && temporal.isNotEmpty()) metadataPx - temporalWidth - with(density) { 10.dp.toPx() } else metadataPx
    val minimumPlaceWidth = remember(category, place, style, density, measurer) {
        if (place.isEmpty()) 0 else measurer.measure("$category · ${place.take(1)}…", style = style, softWrap = false).size.width
    }
    val showPlace = place.isNotEmpty() && flexibleWidth >= with(density) { 64.dp.toPx() } && flexibleWidth >= minimumPlaceWidth
    val flexible = if (showPlace) "$category · $place" else category
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (hasRepeat) {
                Icon(painterResource(R.drawable.ic_repeat), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp).testTag("transaction_repeat_${transaction.id}"))
                Spacer(Modifier.width(4.dp))
            }
            Text(flexible, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (together && temporal.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(temporal, modifier = Modifier.width(with(density) { temporalWidth.toDp() } + 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = style, softWrap = false)
            }
        }
        if (!together) Text(temporal, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = style)
    }
}

@Composable
private fun TransactionPresentation(
    transaction: Transaction,
    amountWidth: Int,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
    padded: Boolean = false,
    decisionWidthInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val amountStyle = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
    val amountText = MoneyFormatter.formatUsd(transaction.cents)
    val ownAmountWidth = remember(amountText, amountStyle, density, measurer) {
        measurer.measure(amountText, style = amountStyle, softWrap = false).size.width
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val actualAvailable = with(density) { (maxWidth - if (padded) 28.dp else 0.dp).coerceAtLeast(0.dp).toPx() }
        val merchantReservation = 96.dp * density.fontScale.coerceAtMost(2f) + 8.dp + decisionWidthInset
        val stack = amountWidth + with(density) { merchantReservation.toPx() } > actualAvailable
        Column(modifier = Modifier.fillMaxWidth().then(if (padded) Modifier.padding(horizontal = 14.dp, vertical = 10.dp) else Modifier)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = transaction.merchant.ifBlank { "Untitled" },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!stack) {
                    Spacer(Modifier.width(8.dp))
                    TransactionAmount(transaction, Modifier.width(with(density) { ownAmountWidth.toDp() }), availablePx = actualAvailable, measuredWidth = ownAmountWidth)
                }
            }
            TransactionMetadata(transaction, showDate, actualAvailable)
            if (stack) TransactionAmount(transaction, Modifier.fillMaxWidth(), availablePx = actualAvailable, measuredWidth = ownAmountWidth)
        }
    }
}

@Composable
private fun TransactionAmount(
    transaction: Transaction,
    modifier: Modifier = Modifier,
    availablePx: Float,
    measuredWidth: Int,
) {
    val color = when {
        transaction.effectiveFlowKind == FlowKind.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
        transaction.cents < 0 -> LocalFinanceColors.current.expense
        else -> LocalFinanceColors.current.income
    }
    val baseStyle = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum", color = color, textAlign = TextAlign.End)
    val text = MoneyFormatter.formatUsd(transaction.cents)
    val measurer = rememberTextMeasurer()
    val fontSize = remember(text, baseStyle, availablePx, measuredWidth, measurer) {
        var size = baseStyle.fontSize
        if (measuredWidth > availablePx) {
            while (size.value > 8f && measurer.measure(text, style = baseStyle.copy(fontSize = size), softWrap = false).size.width > availablePx) {
                size = (size.value - 0.5f).coerceAtLeast(8f).sp
            }
        }
        size
    }
    BasicText(
        text = text,
        style = baseStyle.copy(fontSize = fontSize),
        softWrap = false,
        maxLines = 1,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionEditor(
    transaction: Transaction?,
    draft: EditorDraft,
    suggestionHistory: TransactionSuggestionHistory,
    onDraftChange: (EditorDraft) -> Unit,
    onSave: (Transaction) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
    persistenceBusy: Boolean,
    modifier: Modifier = Modifier,
    onSaveWithFutureRule: ((Transaction) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dateTime = remember(draft.occurredAtEpochMillis) { draft.occurredAtDateTime() }
    var showAllCategories by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var locationStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var locationSuggestion by rememberSaveable { mutableStateOf<String?>(null) }
    var amountInput by rememberSaveable(transaction?.id) { mutableStateOf(draft.amount) }
    val isSynced = draft.source == "simplefin"
    var useForFuture by rememberSaveable(transaction?.id) { mutableStateOf(false) }
    var syncedAdvancedExpanded by rememberSaveable(transaction?.id) { mutableStateOf(false) }
    val editorStateKey = transaction?.id ?: draft.id ?: "new"
    var moreDetailsExpanded by rememberSaveable(editorStateKey) {
        mutableStateOf(draft.note.isNotBlank() || draft.recurringIntervalName.isNotBlank())
    }
    var previousNote by remember(editorStateKey) { mutableStateOf(draft.note) }
    val latestDraft by rememberUpdatedState(draft)
    val latestOnDraftChange by rememberUpdatedState(onDraftChange)

    LaunchedEffect(draft.amount) {
        amountInput = draft.amount
    }

    LaunchedEffect(draft.note) {
        if (previousNote.isBlank() && draft.note.isNotBlank()) moreDetailsExpanded = true
        previousNote = draft.note
    }

    val merchantSuggestions =
        remember(suggestionHistory, draft.merchant, draft.category, draft.isExpense) {
            TransactionSuggestions.merchantSuggestions(
                history = suggestionHistory,
                query = draft.merchant,
                category = draft.category,
                isExpense = draft.isExpense,
            )
        }
    val rankedCategories =
        remember(suggestionHistory, draft.isExpense) {
            suggestionHistory.categories(draft.isExpense)
        }
    val categoryPickerOptions =
        remember(rankedCategories, draft.category, showAllCategories) {
            CategoryCatalog.pickerOptions(
                ranked = rankedCategories,
                selectedCategory = draft.category,
                expanded = showAllCategories,
            )
        }
    LaunchedEffect(draft.amount, draft.merchant, draft.category, draft.isExpense, draft.occurredAtEpochMillis) {
        error = null
    }

    fun applyAmountKey(key: String) {
        val amount =
            when (key) {
                "back" -> {
                    amountInput.dropLast(1)
                }

                "." -> {
                    if (amountInput.contains(".")) amountInput else amountInput.ifBlank { "0" } + "."
                }

                else -> {
                    val candidate = if (amountInput == "0") key else amountInput + key
                    candidate.take(10)
                }
            }
        amountInput = amount
        onDraftChange(draft.copy(amount = amount))
    }

    fun updateTransferStatus(treatAsTransfer: Boolean) {
        val selectedFlowKind = if (treatAsTransfer) FlowKind.TRANSFER else FlowKind.NORMAL
        val updatedOverride = selectedFlowKind.takeUnless { it == draft.flowKind }
        if (updatedOverride != draft.flowKindOverride) {
            onDraftChange(draft.copy(flowKindOverride = updatedOverride))
        }
    }

    fun loadNearbySuggestion() {
        scope.launch {
            locationStatus = "Finding nearby address..."
            val suggestion =
                withContext(Dispatchers.IO) {
                    resolveLocationSuggestion(context)
                }
            if (suggestion == null) {
                locationStatus = "No recent location available"
            } else {
                locationSuggestion = suggestion.displayName
                locationStatus = "Nearby address ready"
                latestOnDraftChange(
                    latestDraft.copy(
                        merchant = latestDraft.merchant.ifBlank { suggestion.displayName },
                        note = latestDraft.note.ifBlank { suggestion.addressLine.orEmpty() },
                    ),
                )
            }
        }
    }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                loadNearbySuggestion()
            } else {
                locationStatus = "Location permission not granted"
            }
        }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .imePadding(),
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .align(Alignment.TopCenter)
                    .testTag("transaction_editor")
                    .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (transaction == null) "Add transaction" else "Edit transaction",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel, enabled = !persistenceBusy) {
                    Text("Close")
                }
            }

            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("transaction_editor_form"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 12.dp),
            ) {
                if (isSynced) {
                    item {
                        SyncedProviderSummary(draft = draft, dateTime = dateTime)
                    }
                    item {
                        TransferStatusToggle(
                            isTransfer = draft.effectiveFlowKind == FlowKind.TRANSFER,
                            onTransferChange = ::updateTransferStatus,
                        )
                    }
                    item {
                        Column(
                            modifier =
                                Modifier.animateContentSize(
                                    animationSpec =
                                        tween(
                                            durationMillis = PennyMotion.DurationMedium,
                                            easing = PennyMotion.StandardEasing,
                                        ),
                                ),
                        ) {
                            SectionLabel("Category")
                            Spacer(Modifier.height(8.dp))
                            FlowRow(
                                modifier = Modifier.selectableGroup(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                categoryPickerOptions.visible.forEach { option ->
                                    SuggestionChip(
                                        label = option.label,
                                        selected = draft.category == option.label,
                                        testTag = "category_chip_${option.label.toCategoryChipTagSuffix()}",
                                        onClick = { onDraftChange(draft.copy(category = option.label)) },
                                    )
                                }
                            }
                            if (categoryPickerOptions.hiddenCount > 0 || showAllCategories) {
                                Spacer(Modifier.height(8.dp))
                                SuggestionChip(
                                    label = if (showAllCategories) "Less" else "More (${categoryPickerOptions.hiddenCount})",
                                    testTag = "category_more_button",
                                    isSelection = false,
                                    onClick = { showAllCategories = !showAllCategories },
                                )
                            }
                        }
                    }
                    item {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .clickable(role = Role.Checkbox) { useForFuture = !useForFuture }
                                    .semantics { stateDescription = if (useForFuture) "Selected" else "Not selected" }
                                    .padding(horizontal = 10.dp)
                                    .testTag("synced_use_future"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = useForFuture, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Use for future", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Save a rule for this normalized provider merchant.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = draft.merchantOverride.orEmpty(),
                            onValueChange = { value ->
                                val override = value.takeIf { it.isNotBlank() }
                                onDraftChange(
                                    draft.copy(
                                        merchantOverride = override,
                                        merchant = override ?: draft.providerMerchant ?: draft.merchant,
                                    ),
                                )
                            },
                            label = { Text("Merchant display override (optional)") },
                            placeholder = { Text(draft.providerMerchant ?: "Provider merchant") },
                            singleLine = true,
                            keyboardOptions =
                                KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Words,
                                    imeAction = ImeAction.Next,
                                ),
                            colors = flowTextFieldColors(),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth().testTag("merchant_override_field"),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = draft.note,
                            onValueChange = { onDraftChange(draft.copy(note = it)) },
                            label = { Text("Note") },
                            minLines = 2,
                            keyboardOptions =
                                KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences,
                                    imeAction = ImeAction.Done,
                                ),
                            colors = flowTextFieldColors(),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth().testTag("note_field"),
                        )
                    }
                    item {
                        SyncedAdvancedDisclosure(
                            expanded = syncedAdvancedExpanded,
                            onExpandedChange = { syncedAdvancedExpanded = it },
                        )
                    }
                    if (syncedAdvancedExpanded) {
                        item {
                            Text(
                                "Amount, date, and transaction type come from your bank and may refresh on the next sync.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag("synced_advanced_warning"),
                            )
                        }
                        item {
                            TransactionTypeToggle(
                                isExpense = draft.isExpense,
                                onExpenseChange = {
                                    val updatedCategories = CategoryCatalog.categoriesFor(it)
                                    showAllCategories = false
                                    onDraftChange(
                                        draft.copy(
                                            isExpense = it,
                                            category =
                                                draft.category.takeIf { category -> category in updatedCategories }
                                                    ?: updatedCategories.first(),
                                        ),
                                    )
                                },
                            )
                        }
                        item {
                            AmountPad(
                                amount = amountInput,
                                isExpense = draft.isExpense,
                                onKey = ::applyAmountKey,
                            )
                        }
                        item {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                DateTimeField(
                                    "Date",
                                    dateTime.toLocalDate().format(DateFormatter),
                                    Modifier.widthIn(min = 150.dp).weight(1f).testTag("date_picker_button"),
                                ) { showDatePicker = true }
                                DateTimeField(
                                    "Time",
                                    dateTime.toLocalTime().format(TimeFormatter),
                                    Modifier.widthIn(min = 150.dp).weight(1f).testTag("time_picker_button"),
                                ) { showTimePicker = true }
                            }
                        }
                    }
                } else {
                    item {
                        AmountPad(amount = amountInput, isExpense = draft.isExpense, onKey = ::applyAmountKey)
                    }
                    item {
                        TransactionTypeToggle(
                            isExpense = draft.isExpense,
                            onExpenseChange = {
                                val updatedCategories = CategoryCatalog.categoriesFor(it)
                                showAllCategories = false
                                onDraftChange(
                                    draft.copy(
                                        isExpense = it,
                                        category =
                                            draft.category.takeIf { category -> category in updatedCategories }
                                                ?: updatedCategories.first(),
                                    ),
                                )
                            },
                        )
                    }
                    item {
                        TransferStatusToggle(
                            isTransfer = draft.effectiveFlowKind == FlowKind.TRANSFER,
                            onTransferChange = ::updateTransferStatus,
                        )
                    }

                    item {
                        SectionLabel("Where")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = draft.merchant,
                            onValueChange = { onDraftChange(draft.copy(merchant = it)) },
                            placeholder = { Text("Merchant or place") },
                            singleLine = true,
                            keyboardOptions =
                                KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Words,
                                    imeAction = ImeAction.Next,
                                ),
                            colors = flowTextFieldColors(),
                            shape = MaterialTheme.shapes.large,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("merchant_field"),
                        )
                        Spacer(Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SuggestionChip(
                                label = "Near me",
                                selected = locationStatus == "Finding nearby address...",
                                isSelection = false,
                                onClick = {
                                    if (hasLocationPermission(context)) {
                                        loadNearbySuggestion()
                                    } else {
                                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                                    }
                                },
                            )
                            locationSuggestion?.let { suggestion ->
                                SuggestionChip(
                                    label = suggestion,
                                    selected = draft.merchant == suggestion,
                                    onClick = {
                                        onDraftChange(draft.copy(merchant = suggestion))
                                    },
                                )
                            }
                            merchantSuggestions.forEach { suggestion ->
                                SuggestionChip(
                                    label = suggestion,
                                    selected = draft.merchant == suggestion,
                                    onClick = {
                                        onDraftChange(draft.copy(merchant = suggestion))
                                    },
                                )
                            }
                        }
                        locationStatus?.let { status ->
                            Spacer(Modifier.height(6.dp))
                            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(14.dp))
                        Column(
                            modifier =
                                Modifier.animateContentSize(
                                    animationSpec =
                                        tween(
                                            durationMillis = PennyMotion.DurationMedium,
                                            easing = PennyMotion.StandardEasing,
                                        ),
                                ),
                        ) {
                            SectionLabel("Category")
                            Spacer(Modifier.height(8.dp))
                            FlowRow(
                                modifier = Modifier.selectableGroup(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                categoryPickerOptions.visible.forEach { option ->
                                    SuggestionChip(
                                        label = option.label,
                                        selected = draft.category == option.label,
                                        testTag = "category_chip_${option.label.toCategoryChipTagSuffix()}",
                                        onClick = {
                                            onDraftChange(draft.copy(category = option.label))
                                        },
                                    )
                                }
                            }
                            if (categoryPickerOptions.hiddenCount > 0 || showAllCategories) {
                                Spacer(Modifier.height(8.dp))
                                SuggestionChip(
                                    label = if (showAllCategories) "Less" else "More (${categoryPickerOptions.hiddenCount})",
                                    testTag = "category_more_button",
                                    isSelection = false,
                                    onClick = { showAllCategories = !showAllCategories },
                                )
                            }
                        }
                    }

                    item {
                        MoreDetailsDisclosure(
                            dateTime = dateTime,
                            recurringInterval = draft.recurringIntervalName.toRecurrenceIntervalOrNull(),
                            expanded = moreDetailsExpanded,
                            onExpandedChange = { moreDetailsExpanded = it },
                        )
                    }

                    if (moreDetailsExpanded) {
                        item {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                DateTimeField(
                                    "Date",
                                    dateTime.toLocalDate().format(DateFormatter),
                                    Modifier.widthIn(min = 150.dp).weight(1f).testTag("date_picker_button"),
                                ) { showDatePicker = true }
                                DateTimeField(
                                    "Time",
                                    dateTime.toLocalTime().format(TimeFormatter),
                                    Modifier.widthIn(min = 150.dp).weight(1f).testTag("time_picker_button"),
                                ) { showTimePicker = true }
                            }
                        }

                        item {
                            SuggestionSection(title = "Recurring") {
                                SuggestionChip(
                                    label = "Off",
                                    selected = draft.recurringIntervalName.isBlank(),
                                    testTag = "recurring_chip_off",
                                    onClick = {
                                        onDraftChange(draft.copy(recurringIntervalName = ""))
                                    },
                                )
                                RecurrenceInterval.entries.forEach { interval ->
                                    SuggestionChip(
                                        label = interval.label,
                                        selected = draft.recurringIntervalName == interval.name,
                                        testTag = "recurring_chip_${interval.name.lowercase(Locale.US)}",
                                        onClick = {
                                            onDraftChange(draft.copy(recurringIntervalName = interval.name))
                                        },
                                    )
                                }
                            }
                        }

                        item {
                            OutlinedTextField(
                                value = draft.note,
                                onValueChange = { onDraftChange(draft.copy(note = it)) },
                                label = { Text("Note") },
                                minLines = 2,
                                keyboardOptions =
                                    KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences,
                                        imeAction = ImeAction.Done,
                                    ),
                                colors = flowTextFieldColors(),
                                shape = MaterialTheme.shapes.large,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag("note_field"),
                            )
                        }
                    }
                }
            }

            if (error != null) {
                Text(error.orEmpty(), color = LocalFinanceColors.current.expense, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        enabled = !persistenceBusy,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = LocalFinanceColors.current.expense,
                            disabledContentColor = IconButtonDefaults.iconButtonColors().disabledContentColor,
                        ),
                        modifier = Modifier.size(48.dp).testTag("delete_transaction_button").semantics { contentDescription = "Delete transaction" },
                    ) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                    }
                }
                Button(
                    onClick = {
                        if (MoneyFormatter.parseAmountToCents(draft.amount).absoluteValue == 0) {
                            error = "Enter an amount"
                        } else {
                            val transactionToSave = draft.toTransaction()
                            if (isSynced && useForFuture && onSaveWithFutureRule != null) {
                                onSaveWithFutureRule(transactionToSave)
                            } else {
                                onSave(transactionToSave)
                            }
                        }
                    },
                    enabled = !persistenceBusy,
                    colors = ButtonDefaults.buttonColors(),
                    shape = MaterialTheme.shapes.large,
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .testTag("save_transaction_button"),
                ) {
                    BasicText(
                        text = when {
                            draft.effectiveFlowKind == FlowKind.TRANSFER -> "Save transfer"
                            draft.isExpense -> "Save expense"
                            else -> "Save income"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelLarge.copy(color = LocalContentColor.current, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 14.sp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = draft.localDateAtUtcStartOfDay())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        val date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                        onDraftChange(draft.copy(occurredAtEpochMillis = LocalDateTime.of(date, dateTime.toLocalTime()).toEpochMillis()))
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }
    if (showTimePicker) {
        PennyRichTimePickerDialog(
            initialHour = dateTime.hour,
            initialMinute = dateTime.minute,
            is24Hour = true,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                onDraftChange(
                    draft.copy(
                        occurredAtEpochMillis =
                            LocalDateTime
                                .of(
                                    dateTime.toLocalDate(),
                                    LocalTime.of(hour, minute),
                                ).toEpochMillis(),
                    ),
                )
                showTimePicker = false
            },
        )
    }
}

@Composable
private fun SyncedProviderSummary(
    draft: EditorDraft,
    dateTime: LocalDateTime,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().testTag("synced_provider_summary"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Bank details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            StatusLine("Account", draft.accountName ?: draft.accountKey ?: "Unknown account")
            StatusLine("Posted", dateTime.format(ListDateFormatter))
            if (draft.transactedAtEpochMillis != null &&
                draft.transactedAtEpochMillis != draft.occurredAtEpochMillis
            ) {
                StatusLine(
                    "Transacted",
                    Instant
                        .ofEpochMilli(draft.transactedAtEpochMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()
                        .format(ListDateFormatter),
                    Modifier.testTag("synced_transacted_at"),
                )
            }
            StatusLine(
                "Amount",
                MoneyFormatter.formatUsd(
                    TransactionSuggestions.signedCents(
                        MoneyFormatter.parseAmountToCents(draft.amount).absoluteValue,
                        draft.isExpense,
                    ),
                ),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "Raw provider description",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                draft.providerDescription ?: draft.providerMerchant ?: "Unavailable",
                modifier = Modifier.testTag("synced_provider_description"),
            )
        }
    }
}

@Composable
private fun SyncedAdvancedDisclosure(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(role = Role.Button) { onExpandedChange(!expanded) }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("synced_advanced_toggle"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(if (expanded) "Hide advanced" else "Advanced", fontWeight = FontWeight.SemiBold)
            Text(
                "Edit provider-owned amount, date, or type",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(if (expanded) "−" else "+", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun MoreDetailsDisclosure(
    dateTime: LocalDateTime,
    recurringInterval: RecurrenceInterval?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .animateContentSize(
                    animationSpec =
                        tween(
                            durationMillis = PennyMotion.DurationMedium,
                            easing = PennyMotion.StandardEasing,
                        ),
                ).clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(role = Role.Button) { onExpandedChange(!expanded) }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("more_details_toggle"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (expanded) "Hide details" else "More details",
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${dateTime.format(ListDateFormatter)} · ${recurringInterval?.label ?: "Does not repeat"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (expanded) "−" else "+",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun DateTimeField(
    label: String,
    value: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun String.toRecurrenceIntervalOrNull(): RecurrenceInterval? = RecurrenceInterval.entries.firstOrNull { it.name == this }

@Composable
private fun TransferStatusToggle(
    isTransfer: Boolean,
    onTransferChange: (Boolean) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("transfer_status_control")
                .semantics {
                    stateDescription = if (isTransfer) "Treat as transfer" else "Not a transfer"
                },
    ) {
        SectionLabel("Transfer status")
        Spacer(Modifier.height(8.dp))
        PennyBinaryChoice(
            firstLabel = "Not a transfer",
            firstSelected = !isTransfer,
            onFirstClick = { onTransferChange(false) },
            firstModifier = Modifier.testTag("transfer_status_not_transfer"),
            secondLabel = "Treat as transfer",
            secondSelected = isTransfer,
            onSecondClick = { onTransferChange(true) },
            secondModifier = Modifier.testTag("transfer_status_transfer"),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp),
        )
    }
}

@Composable
private fun TransactionTypeToggle(
    isExpense: Boolean,
    onExpenseChange: (Boolean) -> Unit,
) {
    PennyBinaryChoice(
        firstLabel = "Expense",
        firstSelected = isExpense,
        onFirstClick = { onExpenseChange(true) },
        firstModifier = Modifier,
        secondLabel = "Income",
        secondSelected = !isExpense,
        onSecondClick = { onExpenseChange(false) },
        secondModifier = Modifier,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(52.dp),
    )
}

@Composable
private fun AmountPad(
    amount: String,
    isExpense: Boolean,
    onKey: (String) -> Unit,
) {
    val parsedAmount = MoneyFormatter.parseAmountToCents(amount).absoluteValue
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (isExpense) "Amount paid" else "Income received",
                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${if (isExpense) "-" else "+"}${MoneyFormatter.formatUsd(parsedAmount)}",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"),
                letterSpacing = 0.sp,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("amount_display"),
                textAlign = TextAlign.End,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf(".", "0", "back"),
                ).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { key ->
                            KeypadButton(
                                key = key,
                                label = if (key == "back") "<" else key,
                                onClick = { onKey(key) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadButton(
    key: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    Box(
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .testTag("amount_key_${key.toAmountKeyTagSuffix()}")
                .pennyPressScale(interactionSource)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.08f))
                .clickable(
                    interactionSource = interactionSource,
                    indication = indication,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics {
                    if (key == "back") contentDescription = "Delete last digit"
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.inverseOnSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Modifier.pennyPressScale(interactionSource: MutableInteractionSource): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleFactor by
        animateFloatAsState(
            targetValue = if (isPressed) 0.97f else 1f,
            animationSpec = tween(durationMillis = PennyMotion.DurationShort, easing = PennyMotion.StandardEasing),
            label = "Penny press scale",
        )
    return scale(scaleFactor)
}

private fun String.toAmountKeyTagSuffix(): String =
    when (this) {
        "." -> "decimal"
        "back" -> "back"
        else -> this
    }

private fun String.toCategoryChipTagSuffix(): String = lowercase(Locale.US).filter { it.isLetterOrDigit() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionSection(
    title: String,
    content: @Composable FlowRowScope.() -> Unit,
) {
    SectionLabel(title)
    Spacer(Modifier.height(8.dp))
    FlowRow(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun SuggestionChip(
    label: String,
    selected: Boolean = false,
    testTag: String? = null,
    isSelection: Boolean = true,
    enabled: Boolean = true,
    accessibilityLabel: String? = null,
    onClick: () -> Unit,
) {
    val tagModifier = if (testTag == null) Modifier else Modifier.testTag(testTag)
    if (!isSelection) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = tagModifier.heightIn(min = 48.dp).widthIn(max = 220.dp).then(
                if (accessibilityLabel == null) Modifier else Modifier.semantics { contentDescription = accessibilityLabel },
            ),
        ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        return
    }
    val interactionModifier =
        if (isSelection) {
            Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        } else {
            Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        }
    Box(
        modifier =
            tagModifier
                .heightIn(min = 48.dp)
                .widthIn(max = 220.dp)
                .clip(MaterialTheme.shapes.large)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                .then(interactionModifier)
                .then(
                    if (accessibilityLabel == null) {
                        Modifier
                    } else {
                        Modifier.semantics { contentDescription = accessibilityLabel }
                    },
                ).padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun flowTextFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        cursorColor = MaterialTheme.colorScheme.primary,
    )

private data class LocationSuggestion(
    val displayName: String,
    val addressLine: String?,
)

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

private fun resolveLocationSuggestion(context: Context): LocationSuggestion? {
    if (!hasLocationPermission(context)) return null
    val location = lastKnownLocation(context) ?: return null
    val address = reverseGeocode(context, location)
    val addressLine = address?.getAddressLine(0)
    val displayName =
        address?.bestDisplayName()
            ?: "Near ${"%.4f".format(Locale.US, location.latitude)}, ${"%.4f".format(Locale.US, location.longitude)}"

    return LocationSuggestion(
        displayName = displayName,
        addressLine = addressLine,
    )
}

@SuppressLint("MissingPermission")
private fun lastKnownLocation(context: Context): Location? {
    val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

    return locationManager
        .getProviders(true)
        .mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
}

@Suppress("DEPRECATION")
private fun reverseGeocode(
    context: Context,
    location: Location,
): Address? {
    if (!Geocoder.isPresent()) return null
    return runCatching {
        Geocoder(context, Locale.getDefault())
            .getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()
    }.getOrNull()
}

private fun Address.bestDisplayName(): String? {
    val feature =
        featureName
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
    val street =
        listOfNotNull(
            subThoroughfare?.trim()?.takeIf { it.isNotBlank() },
            thoroughfare?.trim()?.takeIf { it.isNotBlank() },
        ).joinToString(" ").takeIf { it.isNotBlank() }
    val localityLabel =
        listOfNotNull(
            locality?.trim()?.takeIf { it.isNotBlank() },
            adminArea?.trim()?.takeIf { it.isNotBlank() },
        ).joinToString(", ").takeIf { it.isNotBlank() }

    return feature ?: street ?: localityLabel ?: getAddressLine(0)?.trim()?.takeIf { it.isNotBlank() }
}

private fun Transaction.occurredAtDateTime(): LocalDateTime =
    Instant
        .ofEpochMilli(occurredAtEpochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()

private fun EditorDraft.occurredAtDateTime(): LocalDateTime =
    Instant
        .ofEpochMilli(occurredAtEpochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()

private fun Transaction.rowMetadata(showDate: Boolean): Triple<String, String, String> {
    val categoryLabel = if (effectiveFlowKind == FlowKind.TRANSFER) "Transfer" else category.trim().ifBlank { "Other" }
    val location = listOfNotNull(locationCity?.trim()?.takeIf { it.isNotEmpty() }, locationState?.trim()?.takeIf { it.isNotEmpty() })
        .joinToString(", ").ifBlank { accountName?.trim().orEmpty() }
    val timestamp = transactedAtEpochMillis ?: occurredAtEpochMillis.takeIf { source != "simplefin" }
    val whenLabel = if (showDate) {
        val posted = occurredAtDateTime().toLocalDate()
        val purchase = transactedAtEpochMillis?.takeIf { source == "simplefin" }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        if (purchase != null && purchase != posted) "${purchase.format(ShortDateFormatter)} · posted ${posted.format(ShortDateFormatter)}" else posted.format(ShortDateFormatter)
    } else {
        timestamp?.let {
            val dateTime = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
            val time = dateTime.format(RowTimeFormatter)
            if (transactedAtEpochMillis != null && dateTime.toLocalDate() != occurredAtDateTime().toLocalDate()) {
                "${dateTime.format(ShortDateFormatter)}, $time"
            } else {
                time
            }
        }.orEmpty()
    }
    return Triple(categoryLabel, location, whenLabel)
}

private fun Transaction.rowAccessibility(): String {
    val timestamp = transactedAtEpochMillis
    val date = when {
        timestamp != null -> {
            val purchase = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
            "Purchase ${purchase.format(AccessibilityDateTimeFormatter)}" +
                if (source == "simplefin" && purchase.toLocalDate() != occurredAtDateTime().toLocalDate()) ", Posted ${occurredAtDateTime().format(AccessibilityDateFormatter)}" else ""
        }
        source == "simplefin" -> "Posted ${occurredAtDateTime().format(AccessibilityDateFormatter)}"
        else -> "Occurred ${occurredAtDateTime().format(AccessibilityDateTimeFormatter)}"
    }
    val categoryLabel = if (effectiveFlowKind == FlowKind.TRANSFER) "Transfer" else category.trim().ifBlank { "Other" }
    val place = listOfNotNull(locationCity?.trim()?.takeIf { it.isNotEmpty() }, locationState?.trim()?.takeIf { it.isNotEmpty() }).joinToString(", ").takeIf { it.isNotBlank() }
    return listOfNotNull(merchant.ifBlank { "Untitled" }, MoneyFormatter.formatUsd(cents), categoryLabel, place, accountName?.trim()?.takeIf { it.isNotEmpty() }, date, recurringInterval?.let { "Recurring ${it.label}" }).joinToString(", ")
}

private fun Transaction.deleteDescription(): String {
    val direction = if (cents < 0) "expense" else "income"
    val place =
        merchant.takeIf { it.isNotBlank() }?.let { name ->
            category.takeIf { it.isNotBlank() }?.let { "$name, $it" } ?: name
        } ?: category.ifBlank { "Untitled" }
    return "$direction of ${MoneyFormatter.formatUsd(cents.absoluteValue)} at $place on ${occurredAtDateTime().format(ListDateFormatter)}"
}

private fun Long?.toSyncTime(): String =
    this?.let {
        Instant
            .ofEpochMilli(it)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(ListDateFormatter)
    } ?: "Never"

private fun automaticSyncFrequencyLabel(count: Int): String = "$count ${if (count == 1) "sync" else "syncs"} per day"

internal fun automaticSyncScheduleUpdateFailureMessage(failure: Throwable): String =
    if (failure is SimpleFinAutomaticSchedulingException) {
        "Schedule saved, but automatic scheduling could not be updated. Try again."
    } else {
        "Could not update automatic sync schedule"
    }

internal fun SimpleFinSyncResult.connectionSnackbarMessage(): String =
    if (this is SimpleFinSyncResult.Success) "SimpleFIN connected" else snackbarMessage()

private fun SimpleFinSyncResult.snackbarMessage(): String =
    when (this) {
        is SimpleFinSyncResult.Success -> {
            "Synced $inserted new ${if (inserted == 1) "transaction" else "transactions"}, " +
                "$updated updated ${if (updated == 1) "transaction" else "transactions"}"
        }

        is SimpleFinSyncResult.Failure -> {
            message
        }

        SimpleFinSyncResult.Throttled -> {
            "Sync recently run. Try again later."
        }
    }

private fun LocalDateTime.toEpochMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

internal fun localDateAtUtcStartOfDay(date: LocalDate): Long = date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

private fun EditorDraft.localDateAtUtcStartOfDay(): Long = localDateAtUtcStartOfDay(occurredAtDateTime().toLocalDate())

private fun parseDateTime(
    date: String,
    time: String,
): LocalDateTime? =
    try {
        LocalDateTime.of(LocalDate.parse(date, DateFormatter), LocalTime.parse(time, TimeFormatter))
    } catch (_: DateTimeParseException) {
        null
    }

private val DateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val TimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val ListDateFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")
private val ShortDateFormatter = DateTimeFormatter.ofPattern("MMM d")
private val RowTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val AccessibilityDateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val AccessibilityDateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a")
private val DayHeaderFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")
private val MonthLabelFormatter = DateTimeFormatter.ofPattern("MMM", Locale.US)
private const val SimpleFinCreateUrl = "https://bridge.simplefin.org/simplefin/create"
private val PagePadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
private val NavigationBreakpoint = 600.dp
