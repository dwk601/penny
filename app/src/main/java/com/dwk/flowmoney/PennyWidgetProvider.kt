package com.dwk.flowmoney

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.coroutines.EmptyCoroutineContext

open class PennyWidgetProvider : AppWidgetProvider() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (isRefreshBroadcast(intent.action)) {
            launchBroadcastUpdate { refreshAll(context.applicationContext) }
        } else {
            // APPWIDGET_UPDATE and APPWIDGET_OPTIONS_CHANGED are dispatched exactly once by the base provider.
            super.onReceive(context, intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        launchBroadcastUpdate {
            updateWidgets(context.applicationContext, appWidgetManager, appWidgetIds)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        if (shouldUpdateForOptionsChange(Build.VERSION.SDK_INT)) {
            launchBroadcastUpdate {
                updateWidget(context.applicationContext, appWidgetManager, appWidgetId, newOptions)
            }
        }
    }

    /**
     * Runs a suspending update without an application-global or otherwise unowned CoroutineScope.
     * The dispatched work is structured by runBlocking, and the broadcast remains pending until it returns.
     */
    internal open fun launchBroadcastUpdate(update: suspend () -> Unit) {
        val pendingResult = goAsync()
        Dispatchers.IO.dispatch(EmptyCoroutineContext) {
            runWidgetBroadcastUpdateAtRunnableBoundary(update) { pendingResult.finish() }
        }
    }

    companion object {
        const val EXTRA_OPEN_ADD_TRANSACTION = "com.dwk.flowmoney.OPEN_ADD_TRANSACTION"
        internal const val OVERVIEW_REQUEST_CODE = 1
        internal const val ADD_REQUEST_CODE = 2

        private const val COMPACT_WIDTH_DP = 110
        private const val COMPACT_HEIGHT_DP = 48
        private const val STANDARD_HEIGHT_DP = 110
        private const val WIDE_WIDTH_DP = 220
        private const val COMPACT_HEIGHT_THRESHOLD_DP = 90

        private val refreshBroadcasts =
            setOf(
                Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_LOCALE_CHANGED,
            )

        internal fun isRefreshBroadcast(action: String?): Boolean = action in refreshBroadcasts

        internal fun shouldUpdateForOptionsChange(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.S

        suspend fun refreshAll(context: Context) =
            withContext(Dispatchers.IO) {
                val appContext = context.applicationContext
                val manager = AppWidgetManager.getInstance(appContext)
                val ids = manager.getAppWidgetIds(ComponentName(appContext, PennyWidgetProvider::class.java))
                if (ids.isNotEmpty()) updateWidgets(appContext, manager, ids)
            }

        private suspend fun updateWidgets(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
        ) {
            val summary = summaryOrFallback(context)
            ids.forEach { id ->
                updateWidget(context, manager, id, manager.getAppWidgetOptions(id), summary)
            }
        }

        private suspend fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
            options: Bundle,
            loadedSummary: WidgetSummary? = null,
        ) {
            val summary = loadedSummary ?: summaryOrFallback(context)
            manager.updateAppWidget(id, viewsFor(context, summary, options))
        }

        internal suspend fun summaryOrFallback(
            context: Context,
            summaryLoader: suspend (Context) -> WidgetSummary = { loadSummary(it) },
        ): WidgetSummary =
            try {
                summaryLoader(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val unavailable = context.getString(R.string.widget_unavailable)
                WidgetSummary(
                    label = context.getString(R.string.widget_spent_this_month),
                    amount = unavailable,
                    count = context.getString(R.string.widget_open_app),
                    topCategory = unavailable,
                    compactAmount = context.getString(R.string.widget_unavailable_compact),
                )
            }

        internal suspend fun loadSummary(
            context: Context,
            today: LocalDate = LocalDate.now(),
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): WidgetSummary {
            val range =
                DashboardAnalytics.rangeFor(
                    mode = ChartRangeMode.Month,
                    selectedMonth = YearMonth.from(today),
                    today = today,
                )
            val startInclusiveEpochMillis =
                range.startInclusive
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val endExclusiveEpochMillis =
                range.endExclusive
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val transactions =
                FlowMoneyDatabase
                    .get(context)
                    .transactionDao()
                    .getInRange(startInclusiveEpochMillis, endExclusiveEpochMillis)
                    .map { it.toTransaction() }
            return summaryForTransactions(context, transactions)
        }

        internal fun summaryForTransactions(
            context: Context,
            transactions: List<Transaction>,
        ): WidgetSummary {
            val stats = calculateWidgetTransactionStats(transactions)
            val categoryRows =
                stats.categoryTotals.map {
                    context.getString(
                        R.string.widget_top_category,
                        it.category,
                        MoneyFormatter.formatUsd(it.cents),
                    )
                }
            val categoryFallback =
                if (stats.pendingReviewCount > 0 && stats.metrics.spentCents > 0) {
                    context.getString(R.string.widget_no_reviewed_spend)
                } else {
                    context.getString(R.string.widget_no_spend)
                }
            return WidgetSummary(
                label = context.getString(R.string.widget_spent_this_month),
                amount = MoneyFormatter.formatUsd(stats.metrics.spentCents),
                count =
                    context.resources.getQuantityString(
                        R.plurals.widget_transaction_count,
                        stats.metrics.transactionCount,
                        stats.metrics.transactionCount,
                    ),
                topCategory = categoryRows.firstOrNull() ?: categoryFallback,
                topCategories = categoryRows.ifEmpty { listOf(categoryFallback) },
                compactAmount = formatCompactWidgetUsd(stats.metrics.spentCents),
                pendingReviewCount = stats.pendingReviewCount,
            )
        }

        private fun viewsFor(
            context: Context,
            summary: WidgetSummary,
            options: Bundle,
        ): RemoteViews =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RemoteViews(
                    mapOf(
                        SizeF(COMPACT_WIDTH_DP.toFloat(), COMPACT_HEIGHT_DP.toFloat()) to
                            viewsForLayout(context, summary, R.layout.widget_penny_compact),
                        SizeF(COMPACT_WIDTH_DP.toFloat(), STANDARD_HEIGHT_DP.toFloat()) to
                            viewsForLayout(context, summary, R.layout.widget_penny_summary),
                        SizeF(WIDE_WIDTH_DP.toFloat(), STANDARD_HEIGHT_DP.toFloat()) to
                            viewsForLayout(context, summary, R.layout.widget_penny_wide),
                    ),
                )
            } else {
                orientationViewsFor(context, summary, options)
            }

        internal fun orientationViewsFor(
            context: Context,
            summary: WidgetSummary,
            options: Bundle,
        ): RemoteViews =
            RemoteViews(
                viewsForLayout(context, summary, landscapeLayoutForOptions(options)),
                viewsForLayout(context, summary, portraitLayoutForOptions(options)),
            )

        internal fun landscapeLayoutForOptions(options: Bundle): Int =
            layoutForOptionBounds(
                options,
                AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
                AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
            )

        internal fun portraitLayoutForOptions(options: Bundle): Int =
            layoutForOptionBounds(
                options,
                AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
            )

        private fun layoutForOptionBounds(
            options: Bundle,
            widthKey: String,
            heightKey: String,
        ): Int {
            val width = options.getInt(widthKey).takeIf { it > 0 } ?: COMPACT_WIDTH_DP
            val height = options.getInt(heightKey).takeIf { it > 0 } ?: STANDARD_HEIGHT_DP
            return layoutForSize(width, height)
        }

        internal fun layoutForSize(
            widthDp: Int,
            heightDp: Int,
        ): Int =
            when {
                heightDp < COMPACT_HEIGHT_THRESHOLD_DP -> R.layout.widget_penny_compact
                widthDp >= WIDE_WIDTH_DP -> R.layout.widget_penny_wide
                else -> R.layout.widget_penny_summary
            }

        internal fun viewsForLayout(
            context: Context,
            summary: WidgetSummary,
            layoutId: Int,
        ): RemoteViews {
            require(
                layoutId == R.layout.widget_penny_compact ||
                    layoutId == R.layout.widget_penny_summary ||
                    layoutId == R.layout.widget_penny_wide,
            ) { "Unknown Penny widget layout" }

            val reviewText =
                summary.pendingReviewCount.takeIf { it > 0 }?.let { count ->
                    context.resources.getQuantityString(
                        R.plurals.widget_pending_review_count,
                        count,
                        count,
                    )
                }
            val labelText =
                if (layoutId == R.layout.widget_penny_compact && reviewText != null) {
                    reviewText
                } else {
                    summary.label
                }
            val countText =
                if (reviewText != null) {
                    context.getString(R.string.widget_supporting_metrics, reviewText, summary.count)
                } else {
                    summary.count
                }

            val views = RemoteViews(context.packageName, layoutId)
            views.setTextViewText(R.id.widget_spent_label, labelText)
            if (layoutId == R.layout.widget_penny_compact && reviewText != null) {
                views.setContentDescription(
                    R.id.widget_spent_label,
                    context.getString(R.string.widget_supporting_metrics, summary.label, reviewText),
                )
            }
            views.setTextViewText(
                R.id.widget_amount,
                if (layoutId == R.layout.widget_penny_compact) summary.compactAmount else summary.amount,
            )
            views.setContentDescription(R.id.widget_amount, summary.amount)
            views.setTextViewText(R.id.widget_add_button, context.getString(R.string.widget_quick_add))
            views.setContentDescription(R.id.widget_add_button, context.getString(R.string.widget_quick_add_description))
            when (layoutId) {
                R.layout.widget_penny_summary -> {
                    val supportingText =
                        context.getString(R.string.widget_supporting_metrics, countText, summary.topCategory)
                    views.setTextViewText(R.id.widget_count, supportingText)
                    views.setContentDescription(R.id.widget_count, supportingText)
                }

                R.layout.widget_penny_wide -> {
                    views.setTextViewText(R.id.widget_count, countText)
                    views.setContentDescription(R.id.widget_count, countText)
                    views.setTextViewText(R.id.widget_categories, summary.topCategories.joinToString("\n"))
                }
            }

            val overview =
                PendingIntent.getActivity(
                    context,
                    OVERVIEW_REQUEST_CODE,
                    overviewIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val quickAdd =
                PendingIntent.getActivity(
                    context,
                    ADD_REQUEST_CODE,
                    addTransactionIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            views.setOnClickPendingIntent(R.id.widget_root, overview)
            views.setOnClickPendingIntent(R.id.widget_add_button, quickAdd)
            return views
        }

        internal fun overviewIntent(context: Context): Intent =
            activityIntent(context)
                .putExtra(EXTRA_OPEN_OVERVIEW, true)

        internal fun addTransactionIntent(context: Context): Intent =
            activityIntent(context)
                .putExtra(EXTRA_OPEN_ADD_TRANSACTION, true)

        private fun activityIntent(context: Context) =
            Intent(context, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
    }
}

internal fun runWidgetBroadcastUpdateAtRunnableBoundary(
    update: suspend () -> Unit,
    finish: () -> Unit,
) {
    try {
        runWidgetBroadcastUpdate(update, finish)
    } catch (_: Throwable) {
        // Dispatchers.IO has no exception channel for this Runnable. Contain failures from both
        // the update and PendingResult.finish() after making exactly one finish attempt.
    }
}

internal fun runWidgetBroadcastUpdate(
    update: suspend () -> Unit,
    finish: () -> Unit,
) {
    try {
        runBlocking { update() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // A later periodic or system-triggered update will retry this local-only refresh.
    } finally {
        finish()
    }
}

private data class CompactUsdMagnitude(
    val dollars: BigDecimal,
    val suffix: String,
)

private val compactUsdMagnitudes =
    listOf(
        CompactUsdMagnitude(BigDecimal("1000"), "K"),
        CompactUsdMagnitude(BigDecimal("1000000"), "M"),
        CompactUsdMagnitude(BigDecimal("1000000000"), "B"),
        CompactUsdMagnitude(BigDecimal("1000000000000"), "T"),
        CompactUsdMagnitude(BigDecimal("1000000000000000"), "Q"),
    )

/**
 * Abbreviates only the amount shown by the narrow compact widget. Values below $1,000 remain
 * exact; larger values use about two significant digits and locale-independent half-up rounding.
 * Standard and wide widgets deliberately continue to bind [WidgetSummary.amount].
 */
internal fun formatCompactWidgetUsd(cents: Long): String {
    val absoluteDollars = BigDecimal.valueOf(cents).movePointLeft(2).abs()
    var magnitudeIndex = compactUsdMagnitudes.indexOfLast { absoluteDollars >= it.dollars }
    if (magnitudeIndex < 0) return MoneyFormatter.formatUsd(cents)

    while (true) {
        val magnitude = compactUsdMagnitudes[magnitudeIndex]
        val scaled = absoluteDollars.divide(magnitude.dollars)
        val decimalPlaces = if (scaled < BigDecimal.TEN) 1 else 0
        val rounded = scaled.setScale(decimalPlaces, RoundingMode.HALF_UP).stripTrailingZeros()
        if (rounded >= BigDecimal("1000") && magnitudeIndex < compactUsdMagnitudes.lastIndex) {
            magnitudeIndex++
            continue
        }
        val sign = if (cents < 0) "-" else ""
        return "$sign\$${rounded.toPlainString()}${magnitude.suffix}"
    }
}

internal data class WidgetTransactionStats(
    val metrics: DashboardMetrics,
    val categoryTotals: List<CategoryTotal>,
    val pendingReviewCount: Int,
)

internal fun calculateWidgetTransactionStats(transactions: List<Transaction>): WidgetTransactionStats =
    WidgetTransactionStats(
        metrics = DashboardAnalytics.metrics(transactions),
        categoryTotals = DashboardAnalytics.categoryTotals(transactions.filterNot { it.isUnreviewed }, limit = 3),
        pendingReviewCount = transactions.count { it.isUnreviewed },
    )

internal data class WidgetSummary(
    val label: String,
    val amount: String,
    val count: String,
    val topCategory: String,
    val topCategories: List<String> = listOf(topCategory),
    val compactAmount: String,
    val pendingReviewCount: Int = 0,
)
