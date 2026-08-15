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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

internal enum class DataOperation(
    val label: String,
) {
    Import("Importing CSV"),
    Export("Exporting CSV"),
    Connect("Connecting bank"),
    StartOver("Starting over"),
    Sync("Syncing bank"),
    UpdateAutomaticSyncs("Saving sync frequency"),
    Disconnect("Disconnecting bank"),
}

internal fun canDismissDataSheet(
    isHiding: Boolean,
    operation: DataOperation?,
): Boolean = !isHiding || operation == null

internal fun pickerResultOperation(
    uri: Uri?,
    operation: DataOperation,
): DataOperation? = uri?.let { operation }

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
)

private val EditorDraftSaver =
    androidx.compose.runtime.saveable.listSaver<EditorDraft, Any>(
        save = { draft ->
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
            )
        },
        restore = { values ->
            EditorDraft(
                id = values[0] as String? ?: "",
                occurredAtEpochMillis = values[1] as Long,
                merchant = values[2] as String,
                amount = values[3] as String,
                isExpense = values[4] as Boolean,
                category = values[5] as String,
                note = values[6] as String,
                recurringIntervalName = values[7] as String,
                source = values[8] as String,
                accountKey = (values[9] as String).ifBlank { null },
                accountName = (values[10] as String).ifBlank { null },
            ).let { draft -> draft.copy(id = draft.id?.ifBlank { null }) }
        },
    )

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

private fun Transaction.toEditorDraft() =
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
    )

private fun EditorDraft.toTransaction(): Transaction {
    val parsedAmount = MoneyFormatter.parseAmountToCents(amount).absoluteValue
    return Transaction(
        id = id ?: UUID.randomUUID().toString(),
        occurredAtEpochMillis = occurredAtEpochMillis,
        merchant = merchant.trim().ifBlank { category },
        category = category,
        note = note.trim(),
        cents = TransactionSuggestions.signedCents(parsedAmount, isExpense),
        recurringInterval = recurringIntervalName.toRecurrenceIntervalOrNull(),
        source = source,
        accountKey = accountKey,
        accountName = accountName,
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var showDataSheet by rememberSaveable { mutableStateOf(false) }
    var selectedTabName by rememberSaveable { mutableStateOf(DashboardTab.Overview.name) }
    var editorDraft by rememberSaveable(stateSaver = EditorDraftSaver) { mutableStateOf(newEditorDraft()) }
    var originalEditorDraft by rememberSaveable(stateSaver = EditorDraftSaver) { mutableStateOf(newEditorDraft()) }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var persistenceBusy by remember { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var closeEditorAfterDelete by rememberSaveable { mutableStateOf(false) }
    var pendingWidgetQuickAddAfterDiscard by rememberSaveable { mutableStateOf(false) }
    var lastHandledOpenAddSheetRequest by remember { mutableStateOf(0) }
    var dataOperation by remember { mutableStateOf<DataOperation?>(null) }
    var showDisconnectConfirmation by rememberSaveable { mutableStateOf(false) }
    val selectedTab = remember(selectedTabName) { DashboardTab.valueOf(selectedTabName) }
    val pendingDelete = pendingDeleteId?.let { id -> uiState.sortedTransactions.firstOrNull { it.id == id } }
    val editorDirty = editorDraft != originalEditorDraft
    val latestEditorDirty by rememberUpdatedState(editorDirty)
    val latestPersistenceBusy by rememberUpdatedState(persistenceBusy)
    val editorSheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { target ->
                if (target == androidx.compose.material3.SheetValue.Hidden && latestEditorDirty && !latestPersistenceBusy) {
                    showDiscardDialog = true
                    false
                } else {
                    !latestPersistenceBusy
                }
            },
        )

    LaunchedEffect(openOverviewRequest) {
        if (openOverviewRequest > 0) selectedTabName = DashboardTab.Overview.name
    }

    LaunchedEffect(viewModel) {
        viewModel.initializationErrorEvents.collect { snackbarHostState.showSnackbar(it) }
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

    fun deleteWithUndo(
        transaction: Transaction,
        onDeleted: () -> Unit = {},
    ) {
        val description = transaction.deleteDescription()
        scope.launch {
            try {
                try {
                    viewModel.delete(transaction.id)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Throwable) {
                    persistenceBusy = false
                    snackbarHostState.showSnackbar("Could not delete $description")
                    return@launch
                }

                onDeleted()
                persistenceBusy = false
                bestEffortWidgetRefresh { transactionWidgetRefresh(context) }
                val result =
                    snackbarHostState.showSnackbar(
                        message = "Deleted $description",
                        actionLabel = "Undo",
                        withDismissAction = true,
                    )
                if (result == SnackbarResult.ActionPerformed) {
                    val restored =
                        try {
                            persistenceBusy = true
                            viewModel.upsert(transaction)
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
        showSheet = true
    }

    fun openTransactionEditor(transaction: Transaction) {
        editorId = transaction.id
        editorDraft = transaction.toEditorDraft()
        originalEditorDraft = editorDraft
        showSheet = true
    }

    fun requestEditorDismissal() {
        if (persistenceBusy) return
        if (editorDirty) showDiscardDialog = true else showSheet = false
    }

    fun requestDelete(
        transaction: Transaction,
        closeEditor: Boolean = false,
    ) {
        if (persistenceBusy) return
        pendingDeleteId = transaction.id
        closeEditorAfterDelete = closeEditor
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
                snackbarHostState.showSnackbar(message)
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
                snackbarHostState.showSnackbar(message)
            }
        }

    AdaptiveFlowMoneyShell(
        selectedTab = selectedTab,
        snackbarHostState = snackbarHostState,
        onTabSelected = { selectedTabName = it.name },
        onAddTransaction = ::openNewTransactionEditor,
        onData = { showDataSheet = true },
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
            onViewAllTransactions = { selectedTabName = DashboardTab.Transactions.name },
            onDelete = { requestDelete(it) },
            onAddTransaction = ::openNewTransactionEditor,
            onData = { showDataSheet = true },
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding)
                    .consumeWindowInsets(padding),
        )
    }

    if (showSheet) {
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
                    if (!persistenceBusy) {
                        val candidateId = editorDraft.id ?: transaction.id
                        val candidateDraft = editorDraft.copy(id = candidateId)
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
                onDelete =
                    editorId?.let { id ->
                        uiState.sortedTransactions.firstOrNull { it.id == id }?.let { transaction ->
                            { requestDelete(transaction, closeEditor = true) }
                        }
                    },
                onCancel = ::requestEditorDismissal,
                persistenceBusy = persistenceBusy,
                modifier = Modifier,
            )
        }
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

    pendingDelete?.let { transaction ->
        AlertDialog(
            onDismissRequest = {
                pendingDeleteId = null
                closeEditorAfterDelete = false
            },
            title = { Text("Delete transaction?") },
            text = { Text("Delete ${transaction.deleteDescription()}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (persistenceBusy) return@TextButton
                        pendingDeleteId = null
                        persistenceBusy = true
                        deleteWithUndo(transaction) {
                            if (closeEditorAfterDelete) showSheet = false
                            closeEditorAfterDelete = false
                        }
                    },
                    enabled = !persistenceBusy,
                    modifier = Modifier.testTag("confirm_delete_button"),
                ) { Text("Delete", color = LocalFinanceColors.current.expense) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingDeleteId = null
                        closeEditorAfterDelete = false
                    },
                ) { Text("Cancel") }
            },
        )
    }

    if (showDataSheet) {
        DataSheetModal(
            operation = dataOperation,
            onDismissRequest = { showDataSheet = false },
        ) {
            DataSheet(
                simpleFin = uiState.simpleFin,
                operation = dataOperation,
                onOpenSetup = {
                    if (dataOperation != null) return@DataSheet
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SimpleFinCreateUrl))
                    runCatching { context.startActivity(intent) }
                        .onFailure { scope.launch { snackbarHostState.showSnackbar("Could not open SimpleFIN") } }
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
                        snackbarHostState.showSnackbar(message)
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
                        snackbarHostState.showSnackbar(message)
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
                        snackbarHostState.showSnackbar(message)
                    }
                },
                onSync = {
                    if (dataOperation != null) return@DataSheet
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
                        }
                        snackbarHostState.showSnackbar(message)
                    }
                },
                onAutomaticSyncsPerDayChange = { count ->
                    if (dataOperation != null) return@DataSheet
                    dataOperation = DataOperation.UpdateAutomaticSyncs
                    scope.launch {
                        var message = "Could not update automatic sync frequency"
                        try {
                            viewModel.updateAutomaticSyncsPerDay(count)
                            message = "Automatic syncs set to $count per day"
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Throwable) {
                            message = automaticSyncFrequencyUpdateFailureMessage(failure)
                        } finally {
                            dataOperation = null
                        }
                        snackbarHostState.showSnackbar(message)
                    }
                },
                onImport = {
                    if (dataOperation == null) {
                        dataOperation = DataOperation.Import
                        runCatching {
                            importLauncher.launch(arrayOf("text/*", "text/csv", "application/csv"))
                        }.onFailure {
                            dataOperation = null
                            scope.launch { snackbarHostState.showSnackbar("Import failed") }
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
                            scope.launch { snackbarHostState.showSnackbar("Export failed") }
                        }
                    }
                },
                onDisconnect = {
                    if (dataOperation == null) showDisconnectConfirmation = true
                },
                onClose = {
                    if (dataOperation == null) showDataSheet = false
                },
                modifier = Modifier.imePadding(),
            )
        }
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
                            snackbarHostState.showSnackbar(message)
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
        content = content,
    )
}

internal enum class DashboardTab(
    val label: String,
    val iconRes: Int,
) {
    Overview("Overview", R.drawable.ic_nav_overview),
    Transactions("Transactions", R.drawable.ic_nav_transactions),
    Insights("Insights", R.drawable.ic_nav_insights),
}

@Composable
internal fun AdaptiveFlowMoneyShell(
    selectedTab: DashboardTab,
    snackbarHostState: SnackbarHostState,
    onTabSelected: (DashboardTab) -> Unit,
    onAddTransaction: () -> Unit,
    onData: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val isWide = maxWidth >= NavigationBreakpoint
        if (isWide) {
            Row(modifier = Modifier.fillMaxSize()) {
                FlowMoneyNavigationRail(selectedTab = selectedTab, onTabSelected = onTabSelected)
                FlowMoneyScaffold(
                    selectedTab = selectedTab,
                    isWide = true,
                    snackbarHostState = snackbarHostState,
                    onTabSelected = onTabSelected,
                    onAddTransaction = onAddTransaction,
                    onData = onData,
                    modifier = Modifier.weight(1f),
                    content = content,
                )
            }
        } else {
            FlowMoneyScaffold(
                selectedTab = selectedTab,
                isWide = false,
                snackbarHostState = snackbarHostState,
                onTabSelected = onTabSelected,
                onAddTransaction = onAddTransaction,
                onData = onData,
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
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FlowMoneyTopAppBar(
                selectedTab = selectedTab,
                onData = onData,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (!isWide) {
                FlowMoneyNavigationBar(selectedTab = selectedTab, onTabSelected = onTabSelected)
            }
        },
        floatingActionButton = {
            Box(
                modifier =
                    if (isWide) {
                        Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    } else {
                        Modifier
                    },
            ) {
                FloatingActionButton(
                    onClick = onAddTransaction,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier =
                        Modifier
                            .testTag("add_transaction_fab")
                            .semantics { contentDescription = "Add transaction" },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                    )
                }
            }
        },
        contentWindowInsets =
            if (isWide) {
                WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
            } else {
                WindowInsets(0.dp)
            },
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FlowMoneyTopAppBar(
    selectedTab: DashboardTab,
    onData: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    PennyOverviewTopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.penny_logo),
                    contentDescription = "Penny logo",
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("Penny", style = MaterialTheme.typography.titleLarge)
            }
        },
        subtitle = {
            Text(
                text = selectedTab.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onData = onData,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun FlowMoneyNavigationBar(
    selectedTab: DashboardTab,
    onTabSelected: (DashboardTab) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        windowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
        modifier = Modifier.testTag("compact_navigation"),
    ) {
        DashboardTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(painter = painterResource(tab.iconRes), contentDescription = null)
                },
                label = { Text(tab.label, maxLines = 1) },
                modifier = Modifier.testTag("tab_${tab.name.lowercase(Locale.US)}"),
            )
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
        DashboardTab.entries.forEach { tab ->
            NavigationRailItem(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(painter = painterResource(tab.iconRes), contentDescription = null)
                },
                label = { Text(tab.label, maxLines = 1) },
                modifier = Modifier.testTag("tab_${tab.name.lowercase(Locale.US)}"),
            )
        }
    }
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
                    recentTransactions = uiState.recentTransactions,
                    rangeTransactions = uiState.rangeTransactions,
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
                    modifier = Modifier.fillMaxSize(),
                )
            }

            DashboardTab.Transactions -> {
                TransactionsPage(
                    sortedTransactions = uiState.sortedTransactions,
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
    recentTransactions: List<Transaction>,
    rangeTransactions: List<Transaction>,
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
    modifier: Modifier = Modifier,
) {
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
            if (sortedTransactions.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateRangeControls(
                            mode = chartRangeMode,
                            selectedMonth = selectedMonth,
                            dateRange = dateRange,
                            availableMonths = availableMonths,
                            onModeSelected = onChartRangeModeSelected,
                            onSelectedMonthChange = onSelectedMonthChange,
                        )
                        EmptyState(
                            onAddTransaction = onAddTransaction,
                            onData = onData,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 260.dp),
                        )
                    }
                }
            } else {
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
                item {
                    SummaryBand(
                        rangeLabel = dateRange.label,
                        metrics = metrics,
                    )
                }
                item {
                    RecentTransactionsHeader(
                        title = "Recent",
                        count = sortedTransactions.size,
                        detail = "${rangeTransactions.size} in range",
                        actionText = "View all",
                        onAction = onViewAllTransactions,
                    )
                }
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            recentTransactions.forEachIndexed { index, transaction ->
                                key(transaction.id) {
                                    SwipeTransactionRow(
                                        transaction = transaction,
                                        onEdit = { onEdit(transaction) },
                                        onDelete = { onDelete(transaction) },
                                    )
                                }
                                if (index < recentTransactions.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 68.dp),
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
            TransactionDayGroup(
                date = date,
                transactions = dayTransactions,
                spentCents = dayTransactions.filter { it.cents < 0 }.sumOf { it.cents.toLong().absoluteValue },
                receivedCents = dayTransactions.filter { it.cents > 0 }.sumOf { it.cents.toLong() },
            )
        }

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
private fun TransactionsPage(
    sortedTransactions: List<Transaction>,
    onEdit: (Transaction) -> Unit,
    onDelete: (Transaction) -> Unit,
    onAddTransaction: () -> Unit,
    onData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var timeFilterName by rememberSaveable { mutableStateOf(TransactionTimeFilter.All.name) }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var whereFilter by rememberSaveable { mutableStateOf("") }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    val timeFilter = remember(timeFilterName) { TransactionTimeFilter.valueOf(timeFilterName) }
    val categories = remember(sortedTransactions) { TransactionFilters.categories(sortedTransactions) }
    val filter =
        remember(timeFilter, selectedCategory, whereFilter) {
            TransactionFilter(time = timeFilter, category = selectedCategory, where = whereFilter)
        }
    val filteredTransactions =
        remember(sortedTransactions, filter) {
            TransactionFilters.apply(sortedTransactions, filter)
        }

    LaunchedEffect(categories) {
        if (selectedCategory != null && categories.none { it == selectedCategory }) selectedCategory = null
    }
    val dayGroups = remember(filteredTransactions) { transactionDayGroups(filteredTransactions) }

    Box(modifier = modifier) {
        LazyColumn(
            modifier =
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .testTag("transactions_list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PagePadding,
        ) {
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
            } else {
                item {
                    RecentTransactionsHeader(
                        title = "Transactions",
                        count = filteredTransactions.size,
                        detail =
                            if (filteredTransactions.size ==
                                sortedTransactions.size
                            ) {
                                "All time"
                            } else {
                                "${sortedTransactions.size} total"
                            },
                    )
                }
                item {
                    TransactionFilterBar(
                        timeFilter = timeFilter,
                        selectedCategory = selectedCategory,
                        categories = categories,
                        whereFilter = whereFilter,
                        isExpanded = filtersExpanded,
                        onExpandedChange = { filtersExpanded = it },
                        onTimeFilterChange = { timeFilterName = it.name },
                        onCategoryChange = { selectedCategory = it },
                        onWhereFilterChange = { whereFilter = it },
                        onClear = {
                            timeFilterName = TransactionTimeFilter.All.name
                            selectedCategory = null
                            whereFilter = ""
                            filtersExpanded = false
                        },
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
                    items(group.transactions, key = { it.id }) { transaction ->
                        SwipeTransactionRow(
                            transaction = transaction,
                            onEdit = { onEdit(transaction) },
                            onDelete = { onDelete(transaction) },
                        )
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
        color = MaterialTheme.colorScheme.background,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("transaction_day_header_${group.date}"),
    ) {
        Column(
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = group.date.format(DayHeaderFormatter),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Spent ${MoneyFormatter.formatUsd(group.spentCents)}",
                    color = LocalFinanceColors.current.expense,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("transaction_day_spent_${group.date}"),
                )
                Text(
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
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onTimeFilterChange: (TransactionTimeFilter) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onWhereFilterChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val hasActiveFilter = timeFilter != TransactionTimeFilter.All || selectedCategory != null || whereFilter.isNotBlank()
    val summary =
        buildList {
            if (timeFilter != TransactionTimeFilter.All) add(timeFilter.label)
            selectedCategory?.let { add(it) }
            if (whereFilter.isNotBlank()) add("“${whereFilter.trim()}”")
        }.joinToString(" · ").ifBlank { "All transactions" }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(role = Role.Button) { onExpandedChange(!isExpanded) }
                    .semantics { stateDescription = if (isExpanded) "Expanded" else "Collapsed" }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("transaction_filter_toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Filters", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hasActiveFilter) {
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.testTag("transaction_filter_clear_compact"),
                ) {
                    Text("Clear", fontSize = 12.sp)
                }
            }
            Text(if (isExpanded) "Hide" else "Show", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }

        if (isExpanded) {
            OutlinedTextField(
                value = whereFilter,
                onValueChange = onWhereFilterChange,
                placeholder = { Text("Where or merchant") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = flowTextFieldColors(),
                shape = MaterialTheme.shapes.large,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("transaction_where_filter"),
            )
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
            if (hasActiveFilter) {
                SuggestionChip(
                    label = "Clear",
                    testTag = "transaction_filter_clear",
                    isSelection = false,
                    onClick = onClear,
                )
            }
        }
    }
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
    modifier: Modifier = Modifier,
) {
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dailySpending, categoryTotals) {
        if (selectedDay != null && dailySpending.none { it.date == selectedDay }) selectedDay = null
        if (selectedCategory != null && categoryTotals.none { it.category == selectedCategory }) selectedCategory = null
    }

    val selectedDailySpend =
        remember(dailySpending, selectedDay) {
            selectedDay?.let { day -> dailySpending.firstOrNull { it.date == day } }
        }
    val selectedCategoryTotal =
        remember(categoryTotals, selectedCategory) {
            selectedCategory?.let { category -> categoryTotals.firstOrNull { it.category == category } }
        }
    val selectedTransactions =
        remember(rangeTransactions, selectedDailySpend, selectedCategoryTotal) {
            when {
                selectedDailySpend != null -> {
                    rangeTransactions.filter { it.cents < 0 && it.localDate() == selectedDailySpend.date }
                }

                selectedCategoryTotal != null -> {
                    rangeTransactions.filter {
                        it.cents < 0 && it.insightCategory() == selectedCategoryTotal.category
                    }
                }

                else -> {
                    emptyList()
                }
            }
        }
    val insightTitle =
        selectedDailySpend?.let { it.date.format(ShortDateFormatter) }
            ?: selectedCategoryTotal?.let { "${it.category} spending" }
    val insightAmount =
        selectedDailySpend?.let { MoneyFormatter.formatUsd(it.cents) }
            ?: selectedCategoryTotal?.let { MoneyFormatter.formatUsd(it.cents) }

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
                DateRangeControls(
                    mode = chartRangeMode,
                    selectedMonth = selectedMonth,
                    dateRange = dateRange,
                    availableMonths = availableMonths,
                    onModeSelected = onChartRangeModeSelected,
                    onSelectedMonthChange = onSelectedMonthChange,
                )
            }
            if (categoryTotals.isEmpty()) {
                item { InsightsEmptyState(onAddTransaction = onAddTransaction) }
            } else {
                item {
                    SpendingTimelineCard(
                        dailySpending = dailySpending,
                        range = dateRange,
                        selectedDay = selectedDay,
                        onDaySelected = { day ->
                            selectedDay = day
                            selectedCategory = null
                        },
                    )
                }
                item {
                    CategoryBreakdownCard(
                        totals = categoryTotals,
                        selectedCategory = selectedCategory,
                        onCategorySelected = { category ->
                            selectedCategory = category
                            selectedDay = null
                        },
                    )
                }
                if (insightTitle != null && insightAmount != null) {
                    item {
                        InsightSelectionHeader(
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
                        )
                    }
                }
            }
        }
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
    onOpenSetup: () -> Unit,
    onConnect: (String) -> Unit,
    onSync: () -> Unit,
    onAutomaticSyncsPerDayChange: (Int) -> Unit = {},
    onImport: () -> Unit,
    onExport: () -> Unit,
    onDisconnect: () -> Unit,
    onClose: () -> Unit,
    onRetryConnection: () -> Unit,
    onCancelPendingConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = simpleFin.profile
    var setupToken by remember { mutableStateOf("") }
    var automaticSyncsExpanded by rememberSaveable(profile?.connectionId, profile?.isPaused) { mutableStateOf(false) }
    val isBusy = operation != null
    val localDataCard: @Composable () -> Unit = {
        DataCard(modifier = Modifier.testTag("local_data_card")) {
            SectionLabel("Import and export")
            Spacer(Modifier.height(8.dp))
            Text(
                "Move records in or out with CSV.",
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

            SimpleFinConnectionStatus(
                simpleFin = simpleFin,
                modifier = Modifier.padding(top = 10.dp),
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                when {
                    simpleFin.isConnectionPending -> {
                        item {
                            DataCard(
                                modifier = Modifier.testTag("simplefin_pending_card"),
                                backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                SectionLabel("Bank connection pending")
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "SimpleFIN accepted your setup token, but the first sync did not finish. " +
                                        "Retry connection to finish without another setup token.",
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
                        item { localDataCard() }
                    }

                    profile == null -> {
                        item { localDataCard() }
                        item {
                            DataCard(modifier = Modifier.testTag("simplefin_setup_card")) {
                                SectionLabel("Bank sync")
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "1. Open SimpleFIN and copy a setup token.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = onOpenSetup, enabled = !isBusy) { Text("Open setup") }
                                Text(
                                    "2. Paste the token below, then connect.",
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
                                    colors = flowTextFieldColors(),
                                    shape = MaterialTheme.shapes.medium,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag("simplefin_setup_token"),
                                )
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        val token = setupToken.trim()
                                        if (token.isNotBlank()) {
                                            setupToken = ""
                                            onConnect(token)
                                        }
                                    },
                                    enabled = !isBusy && setupToken.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(),
                                    shape = MaterialTheme.shapes.large,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Connect") }
                            }
                        }
                    }

                    profile.isPaused -> {
                        item {
                            DataCard(
                                modifier = Modifier.testTag("simplefin_setup_card"),
                                backgroundColor = MaterialTheme.colorScheme.errorContainer,
                            ) {
                                SectionLabel("Bank sync needs attention")
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Reconnect SimpleFIN to resume bank syncing. Your local data and CSV are still available.",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("simplefin_reconnect_message"),
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "1. Open SimpleFIN and copy a new setup token.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                )
                                TextButton(onClick = onOpenSetup, enabled = !isBusy) { Text("Open setup") }
                                Text(
                                    "2. Paste the token below, then reconnect.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                )
                                OutlinedTextField(
                                    value = setupToken,
                                    onValueChange = { setupToken = it },
                                    label = { Text("New setup token") },
                                    singleLine = true,
                                    enabled = !isBusy,
                                    colors = flowTextFieldColors(),
                                    shape = MaterialTheme.shapes.medium,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag("simplefin_setup_token"),
                                )
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        val token = setupToken.trim()
                                        if (token.isNotBlank()) {
                                            setupToken = ""
                                            onConnect(token)
                                        }
                                    },
                                    enabled = !isBusy && setupToken.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(),
                                    shape = MaterialTheme.shapes.large,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Reconnect") }
                                TextButton(
                                    onClick = onDisconnect,
                                    enabled = !isBusy,
                                    modifier = Modifier.testTag("simplefin_disconnect_button"),
                                ) {
                                    Text("Disconnect", color = LocalFinanceColors.current.expense)
                                }
                            }
                        }
                        item { localDataCard() }
                    }

                    else -> {
                        item {
                            DataCard(modifier = Modifier.testTag("bank_sync_card")) {
                                SectionLabel("Bank sync")
                                Spacer(Modifier.height(8.dp))
                                StatusLine("Last sync", profile.lastSuccessfulSyncAtEpochMillis.toSyncTime())
                                StatusLine("Last error", profile.lastError?.takeIf { it.isNotBlank() } ?: "None")
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(Modifier.height(10.dp))
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
                                Text(
                                    "Choose 1–12 automatic syncs per day. Timing is approximate. SimpleFIN expects 24 or fewer requests daily; redirects, retries, and setup can use extra requests, so this setting cannot guarantee that limit.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                Spacer(Modifier.height(10.dp))
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
                                SectionLabel("Connected accounts")
                                Spacer(Modifier.height(8.dp))
                                if (simpleFin.accounts.isEmpty()) {
                                    Text("No accounts synced yet", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                } else {
                                    simpleFin.accounts.forEach { account ->
                                        Text(account.name, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            account.institutionName ?: account.currency.orEmpty(),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 13.sp,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }
                            }
                        }
                        item { localDataCard() }
                    }
                }
            }
        }
    }
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
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
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(backgroundColor ?: MaterialTheme.colorScheme.surfaceContainer)
                .padding(14.dp),
    ) { content() }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
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
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(14.dp)
                .testTag("date_range_controls"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val rangeButtons: @Composable () -> Unit = {
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
            if (maxWidth < 420.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Date range", fontWeight = FontWeight.SemiBold)
                    Text(dateRange.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    rangeButtons()
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Date range", fontWeight = FontWeight.SemiBold)
                        Text(dateRange.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    rangeButtons()
                }
            }
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
private fun SummaryBand(
    rangeLabel: String,
    metrics: DashboardMetrics,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Spending in $rangeLabel",
                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium,
            )
            AnimatedContent(
                targetState = MoneyFormatter.formatUsd(metrics.spentCents),
                transitionSpec = {
                    fadeIn(
                        tween(
                            durationMillis = PennyMotion.DurationMedium,
                            easing = PennyMotion.StandardDecelerateEasing,
                        ),
                    ).togetherWith(
                        fadeOut(
                            tween(
                                durationMillis = PennyMotion.DurationShort,
                                easing = PennyMotion.StandardAccelerateEasing,
                            ),
                        ),
                    )
                },
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier.fillMaxWidth(),
                label = "summary_spending_amount",
            ) { amount ->
                Text(
                    text = amount,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"),
                    letterSpacing = 0.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SummaryPill("Transactions", metrics.transactionCount.toString(), Modifier.widthIn(min = 132.dp).weight(1f))
                SummaryPill("Income", MoneyFormatter.formatUsd(metrics.incomeCents), Modifier.widthIn(min = 132.dp).weight(1f))
                SummaryPill("Net", MoneyFormatter.formatUsd(metrics.netCents), Modifier.widthIn(min = 132.dp).weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.06f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.64f),
            style = MaterialTheme.typography.labelSmall,
        )
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                fadeIn(
                    tween(
                        durationMillis = PennyMotion.DurationMedium,
                        easing = PennyMotion.StandardDecelerateEasing,
                    ),
                ).togetherWith(
                    fadeOut(
                        tween(
                            durationMillis = PennyMotion.DurationShort,
                            easing = PennyMotion.StandardAccelerateEasing,
                        ),
                    ),
                )
            },
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier.fillMaxWidth(),
            label = "summary_$label",
        ) { animatedValue ->
            Text(
                animatedValue,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SpendingTimelineCard(
    dailySpending: List<DailySpend>,
    range: DashboardDateRange,
    selectedDay: LocalDate?,
    onDaySelected: (LocalDate) -> Unit,
) {
    val values = remember(dailySpending) { dailySpending.map { it.cents } }
    val total = remember(values) { values.sum() }
    val peak = remember(values) { values.maxOrNull() ?: 0L }
    val scalePeak = peak.coerceAtLeast(1L)
    val selectedIndex = dailySpending.indexOfFirst { it.date == selectedDay }
    val lineColor = MaterialTheme.colorScheme.primary
    val selectionColor = LocalFinanceColors.current.expense
    val chartSurface = MaterialTheme.colorScheme.surfaceContainerLow
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val chartSummary =
        buildString {
            append("Spending timeline for ${range.label}. Total ${MoneyFormatter.formatUsd(total)}. ")
            append("Peak ${MoneyFormatter.formatUsd(peak)}.")
            selectedDay?.let { day ->
                val amount = dailySpending.firstOrNull { it.date == day }?.cents ?: 0
                append(" Selected ${day.format(ShortDateFormatter)}, ${MoneyFormatter.formatUsd(amount)}.")
            }
        }

    fun selectAtX(
        x: Float,
        width: Float,
    ) {
        if (dailySpending.isEmpty() || width <= 0f) return
        val index =
            if (dailySpending.lastIndex == 0) {
                0
            } else {
                ((x.coerceIn(0f, width) / width) * dailySpending.lastIndex).roundToInt()
            }
        onDaySelected(dailySpending[index.coerceIn(0, dailySpending.lastIndex)].date)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Spending timeline", fontWeight = FontWeight.SemiBold)
                    Text(range.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Text(
                    text = MoneyFormatter.formatUsd(total),
                    color = selectionColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Canvas(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .semantics { contentDescription = chartSummary }
                        .testTag("spending_timeline_chart")
                        .pointerInput(dailySpending, onDaySelected) {
                            detectTapGestures { offset -> selectAtX(offset.x, size.width.toFloat()) }
                        },
            ) {
                val topPadding = 8.dp.toPx()
                val bottomPadding = 12.dp.toPx()
                val chartHeight = size.height - topPadding - bottomPadding
                val step = if (values.size > 1) size.width / (values.size - 1) else size.width
                repeat(3) { index ->
                    val y = topPadding + chartHeight * (index / 2f)
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                val points =
                    values.mapIndexed { index, cents ->
                        val x = step * index
                        val y = topPadding + (1f - cents.toFloat() / scalePeak.toFloat()) * chartHeight
                        Offset(x, y)
                    }
                val path =
                    Path().apply {
                        points.firstOrNull()?.let { moveTo(it.x, it.y) }
                        points.drop(1).forEach { point -> lineTo(point.x, point.y) }
                    }
                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
                points.forEachIndexed { index, point ->
                    val selected = index == selectedIndex
                    drawCircle(color = chartSurface, radius = if (selected) 7.dp.toPx() else 4.dp.toPx(), center = point)
                    drawCircle(
                        color = if (selected) selectionColor else lineColor,
                        radius = if (selected) 4.dp.toPx() else 2.5.dp.toPx(),
                        center = point,
                    )
                }
            }
            val firstDate = dailySpending.firstOrNull()?.date ?: range.startInclusive
            val lastDate = dailySpending.lastOrNull()?.date ?: range.endExclusive.minusDays(1)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(firstDate.format(ShortDateFormatter), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text("Peak ${MoneyFormatter.formatUsd(peak)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text(lastDate.format(ShortDateFormatter), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Text("Select a day", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.selectableGroup(),
            ) {
                items(dailySpending, key = { it.date }) { day ->
                    InsightDayOption(
                        day = day,
                        selected = selectedDay == day.date,
                        onClick = { onDaySelected(day.date) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InsightDayOption(
    day: DailySpend,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val amount = MoneyFormatter.formatUsd(day.cents)
    Box(
        modifier =
            Modifier
                .testTag("insight_day_${day.date}")
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.large)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick,
                ).semantics(mergeDescendants = true) {
                    contentDescription = "${day.date.format(ShortDateFormatter)}, $amount"
                    stateDescription = if (selected) "Selected" else "Not selected"
                }.padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${day.date.format(ShortDateFormatter)} $amount",
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryBreakdownCard(
    totals: List<CategoryTotal>,
    selectedCategory: String?,
    onCategorySelected: (String) -> Unit,
) {
    val peak = totals.maxOfOrNull { it.cents }?.coerceAtLeast(1L) ?: 1L
    val chartColors =
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.secondary,
            LocalFinanceColors.current.expense,
        )
    val chartSelectionOutline = MaterialTheme.colorScheme.onSurface
    val chartSummary =
        totals.joinToString(
            prefix = "Top categories. ",
            separator = ". ",
        ) { "${it.category} ${MoneyFormatter.formatUsd(it.cents)}" }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Top categories", fontWeight = FontWeight.SemiBold)
            if (totals.isEmpty()) {
                Text(
                    text = "Add your first transaction to start tracking where money goes.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Canvas(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .semantics { contentDescription = chartSummary }
                            .testTag("category_breakdown_chart"),
                ) {
                    val gap = 10.dp.toPx()
                    val barWidth = (size.width - gap * (totals.size - 1)) / totals.size
                    totals.forEachIndexed { index, entry ->
                        val barHeight = (entry.cents.toFloat() / peak.toFloat()) * size.height
                        val left = index * (barWidth + gap)
                        val topLeft = Offset(left, size.height - barHeight)
                        val barSize = Size(barWidth, barHeight)
                        drawRoundRect(
                            color = chartColors[index % chartColors.size],
                            topLeft = topLeft,
                            size = barSize,
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                        )
                        if (selectedCategory == entry.category) {
                            drawRoundRect(
                                color = chartSelectionOutline,
                                topLeft = topLeft,
                                size = barSize,
                                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }
                    }
                }
                // ponytail: legend tap target is enough; custom bar hit-testing can wait.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 2,
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                ) {
                    totals.forEachIndexed { index, entry ->
                        val selected = selectedCategory == entry.category
                        Column(
                            modifier =
                                Modifier
                                    .testTag("insight_category_${entry.category.toCategoryChipTagSuffix()}")
                                    .weight(1f)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .heightIn(min = 48.dp)
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        onClick = { onCategorySelected(entry.category) },
                                    ).semantics(mergeDescendants = true) {
                                        contentDescription = "${entry.category}, ${MoneyFormatter.formatUsd(entry.cents)}"
                                        stateDescription = if (selected) "Selected" else "Not selected"
                                    }.padding(6.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(chartColors[index % chartColors.size]),
                            )
                            Text(
                                text = entry.category,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                            )
                            Text(
                                text = MoneyFormatter.formatUsd(entry.cents),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightSelectionHeader(
    title: String,
    amount: String,
    isEmpty: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Selected insight", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
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
}

@Composable
private fun RecentTransactionsHeader(
    title: String,
    count: Int,
    detail: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        Text("$count total · $detail", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (actionText != null && onAction != null) {
            Spacer(Modifier.width(6.dp))
            TextButton(
                onClick = onAction,
                modifier = Modifier.testTag("view_all_transactions"),
            ) {
                Text(actionText, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun EmptyState(
    onAddTransaction: () -> Unit,
    onData: () -> Unit,
    modifier: Modifier = Modifier,
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
                "Add your first transaction, or import a CSV from Data.",
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
                        colors = ButtonDefaults.buttonColors(),
                        shape = MaterialTheme.shapes.large,
                        modifier = buttonModifier.heightIn(min = 48.dp).testTag("empty_add_transaction"),
                    ) { Text("Add transaction") }
                }
                val dataButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                    Button(
                        onClick = onData,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        shape = MaterialTheme.shapes.large,
                        modifier = buttonModifier.heightIn(min = 48.dp).testTag("empty_data_action"),
                    ) { Text("Import from Data") }
                }
                if (maxWidth < 480.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        addButton(Modifier.fillMaxWidth())
                        dataButton(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        addButton(Modifier.weight(1f))
                        dataButton(Modifier.weight(1f))
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
) {
    val haptic = LocalHapticFeedback.current
    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        onEdit()
                        false
                    }

                    SwipeToDismissBoxValue.EndToStart -> {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDelete()
                        false
                    }

                    SwipeToDismissBoxValue.Settled -> {
                        false
                    }
                }
            },
        )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { SwipeActionBackground() },
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("transaction_row"),
    ) {
        TransactionRow(transaction = transaction, onClick = onEdit, onDelete = onDelete)
    }
}

@Composable
private fun SwipeActionBackground() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Text(
            text = "Edit",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 18.dp),
        )
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
}

@Composable
private fun TransactionRow(
    transaction: Transaction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("transaction_content_${transaction.id}")
                .clickable(onClick = onClick)
                .semantics {
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
        val shouldStack = maxWidth < 420.dp || fontScale >= 1.3f
        if (shouldStack) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TransactionCategoryBadge(transaction.category)
                    Spacer(Modifier.width(12.dp))
                    TransactionIdentity(transaction, Modifier.weight(1f))
                }
                TransactionAmount(
                    transaction = transaction,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 54.dp, top = 2.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransactionCategoryBadge(transaction.category)
                Spacer(Modifier.width(12.dp))
                TransactionIdentity(transaction, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                TransactionAmount(transaction, Modifier.widthIn(min = 96.dp))
            }
        }
    }
}

@Composable
private fun TransactionCategoryBadge(category: String) {
    val badgeColors = categoryBadgeColors(category)
    Box(
        modifier =
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(badgeColors.container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = category.firstOrNull()?.uppercase() ?: "$",
            fontWeight = FontWeight.Bold,
            color = badgeColors.content,
        )
    }
}

@Composable
private fun TransactionIdentity(
    transaction: Transaction,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = transaction.merchant.ifBlank { "Untitled" },
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = transaction.rowSubtitle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            transaction.recurringInterval?.let { recurringInterval ->
                Spacer(Modifier.width(6.dp))
                RecurringPill(recurringInterval = recurringInterval)
            }
        }
    }
}

@Composable
private fun TransactionAmount(
    transaction: Transaction,
    modifier: Modifier = Modifier,
) {
    Text(
        text = MoneyFormatter.formatUsd(transaction.cents),
        color = if (transaction.cents < 0) LocalFinanceColors.current.expense else LocalFinanceColors.current.income,
        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
        textAlign = TextAlign.End,
        modifier = modifier,
    )
}

@Composable
private fun RecurringPill(recurringInterval: RecurrenceInterval) {
    Box(
        modifier =
            Modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .semantics { contentDescription = "Recurring ${recurringInterval.label}" }
                .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = recurringInterval.label,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
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
    val amountSuggestions =
        remember(suggestionHistory, draft.merchant, draft.category, draft.isExpense) {
            TransactionSuggestions.amountSuggestions(
                history = suggestionHistory,
                merchant = draft.merchant,
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

            TransactionTypeToggle(
                isExpense = draft.isExpense,
                onExpenseChange = {
                    val updatedCategories = CategoryCatalog.categoriesFor(it)
                    showAllCategories = false
                    onDraftChange(
                        draft.copy(
                            isExpense = it,
                            category =
                                draft.category.takeIf { category -> category in updatedCategories } ?: updatedCategories.first(),
                        ),
                    )
                },
            )

            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("transaction_editor_form"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 12.dp),
            ) {
                item {
                    AmountPad(
                        amount = amountInput,
                        isExpense = draft.isExpense,
                        onKey = ::applyAmountKey,
                    )
                    if (amountSuggestions.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        SectionLabel("Suggested amounts")
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            modifier = Modifier.selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            amountSuggestions.forEachIndexed { index, suggestion ->
                                SuggestionChip(
                                    label = "${MoneyFormatter.formatUsd(suggestion.cents)} · ${suggestion.source.shortLabel()}",
                                    selected = MoneyFormatter.parseAmountToCents(amountInput).absoluteValue == suggestion.cents,
                                    testTag = "amount_suggestion_$index",
                                    onClick = {
                                        val suggestedAmount = MoneyFormatter.formatAmountText(suggestion.cents)
                                        amountInput = suggestedAmount
                                        onDraftChange(draft.copy(amount = suggestedAmount))
                                    },
                                )
                            }
                        }
                    }
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
                }

                item {
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

            if (error != null) {
                Text(error.orEmpty(), color = LocalFinanceColors.current.expense, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        enabled = !persistenceBusy,
                        modifier = Modifier.testTag("delete_transaction_button"),
                    ) {
                        Text("Delete", color = LocalFinanceColors.current.expense)
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (MoneyFormatter.parseAmountToCents(draft.amount).absoluteValue == 0) {
                            error = "Enter an amount"
                        } else {
                            onSave(draft.toTransaction())
                        }
                    },
                    enabled = !persistenceBusy,
                    colors = ButtonDefaults.buttonColors(),
                    shape = MaterialTheme.shapes.large,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .testTag("save_transaction_button"),
                ) {
                    Text(
                        text = if (draft.isExpense) "Save expense" else "Save income",
                        fontWeight = FontWeight.SemiBold,
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
                .clip(MaterialTheme.shapes.medium)
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
    Box(
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .testTag("amount_key_${key.toAmountKeyTagSuffix()}")
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.08f))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    if (key == "back") contentDescription = "Delete last digit"
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.inverseOnSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
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
    onClick: () -> Unit,
) {
    val tagModifier = if (testTag == null) Modifier else Modifier.testTag(testTag)
    val interactionModifier =
        if (isSelection) {
            Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        } else {
            Modifier.clickable(role = Role.Button, onClick = onClick)
        }
    Box(
        modifier =
            tagModifier
                .heightIn(min = 48.dp)
                .widthIn(max = 220.dp)
                .clip(MaterialTheme.shapes.large)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                .then(interactionModifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
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

private fun AmountSuggestionSource.shortLabel(): String =
    when (this) {
        AmountSuggestionSource.LastMatch -> "Last"
        AmountSuggestionSource.FrequentMatch -> "Usual"
        AmountSuggestionSource.Category -> "Category"
        AmountSuggestionSource.Recent -> "Recent"
    }

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

private fun Transaction.rowSubtitle(): String {
    val categoryLabel = category.ifBlank { "Other" }
    val date = occurredAtDateTime()
    val account = accountName?.takeIf { source == "simplefin" && it.isNotBlank() }
    return if (account == null) {
        "$categoryLabel • ${date.format(ListDateFormatter)}"
    } else {
        "$categoryLabel • $account • ${date.format(ShortDateFormatter)}"
    }
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

internal fun automaticSyncFrequencyUpdateFailureMessage(failure: Throwable): String =
    if (failure is SimpleFinAutomaticSchedulingException) {
        "Frequency saved, but automatic scheduling could not be updated. Try again."
    } else {
        "Could not update automatic sync frequency"
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

private fun Transaction.insightCategory(): String = category.trim().ifBlank { "Other" }

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

private data class CategoryBadgeColors(
    val container: Color,
    val content: Color,
)

@Composable
private fun categoryBadgeColors(category: String): CategoryBadgeColors {
    val colors = MaterialTheme.colorScheme
    return when (category) {
        "Food", "Groceries", "Restaurants", "Coffee",
        "Healthcare", "Pharmacy", "Fitness", "Education", "Charity",
        -> {
            CategoryBadgeColors(colors.secondaryContainer, colors.onSecondaryContainer)
        }

        "Transit", "Gas", "Ride share", "Car", "Parking", "Travel",
        "Salary", "Bonus", "Freelance", "Business", "Deposit", "Refund", "Interest",
        "Dividends", "Investments", "Rental", "Reimbursement", "Transfer", "Gift", "Sale",
        -> {
            CategoryBadgeColors(colors.primaryContainer, colors.onPrimaryContainer)
        }

        "Home", "Rent", "Mortgage", "Utilities", "Phone", "Internet" -> {
            CategoryBadgeColors(colors.tertiaryContainer, colors.onTertiaryContainer)
        }

        "Insurance", "Taxes", "Fees", "Debt", "Subscriptions" -> {
            CategoryBadgeColors(colors.surfaceContainerHighest, colors.onSurface)
        }

        "Entertainment", "Shopping", "Clothing", "Gifts", "Kids", "Pets" -> {
            CategoryBadgeColors(colors.errorContainer, colors.onErrorContainer)
        }

        else -> {
            CategoryBadgeColors(colors.surfaceContainerHighest, colors.onSurface)
        }
    }
}

private val DateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val TimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val ListDateFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")
private val ShortDateFormatter = DateTimeFormatter.ofPattern("MMM d")
private val DayHeaderFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")
private val MonthLabelFormatter = DateTimeFormatter.ofPattern("MMM", Locale.US)
private const val SimpleFinCreateUrl = "https://bridge.simplefin.org/simplefin/create"
private val PagePadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
private val NavigationBreakpoint = 600.dp
