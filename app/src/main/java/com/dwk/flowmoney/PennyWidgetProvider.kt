package com.dwk.flowmoney

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PennyWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateWidgets(context.applicationContext, appWidgetManager, appWidgetIds)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_ADD_TRANSACTION = "com.dwk.flowmoney.OPEN_ADD_TRANSACTION"
        internal const val OVERVIEW_REQUEST_CODE = 1
        internal const val ADD_REQUEST_CODE = 2

        fun refreshAll(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, PennyWidgetProvider::class.java))
            if (ids.isEmpty()) return

            // ponytail: fire-and-forget refresh; WorkManager is overkill for a tiny local widget.
            CoroutineScope(Dispatchers.IO).launch {
                updateWidgets(appContext, manager, ids)
            }
        }

        private suspend fun updateWidgets(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
        ) {
            val summary = try {
                loadSummary(context)
            } catch (_: Exception) {
                WidgetSummary(
                    label = context.getString(R.string.widget_spent_this_month),
                    amount = MoneyFormatter.formatUsd(0),
                    count = context.getString(R.string.widget_open_app),
                    topCategory = context.getString(R.string.widget_unavailable),
                )
            }

            ids.forEach { id ->
                manager.updateAppWidget(id, viewsFor(context, summary))
            }
        }

        internal suspend fun loadSummary(
            context: Context,
            today: LocalDate = LocalDate.now(),
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): WidgetSummary {
            val range = DashboardAnalytics.rangeFor(
                mode = ChartRangeMode.Month,
                selectedMonth = YearMonth.from(today),
                today = today,
            )
            val startInclusiveEpochMillis = range.startInclusive.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val endExclusiveEpochMillis = range.endExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val transactions = FlowMoneyDatabase.get(context)
                .transactionDao()
                .getInRange(startInclusiveEpochMillis, endExclusiveEpochMillis)
                .map { it.toTransaction() }
            val metrics = DashboardAnalytics.metrics(transactions)
            val topCategory = DashboardAnalytics.categoryTotals(transactions, limit = 1).firstOrNull()
            return WidgetSummary(
                label = context.getString(R.string.widget_spent_this_month),
                amount = MoneyFormatter.formatUsd(metrics.spentCents),
                count = context.resources.getQuantityString(
                    R.plurals.widget_transaction_count,
                    metrics.transactionCount,
                    metrics.transactionCount,
                ),
                topCategory = topCategory?.let {
                    context.getString(
                        R.string.widget_top_category,
                        it.category,
                        MoneyFormatter.formatUsd(it.cents),
                    )
                } ?: context.getString(R.string.widget_no_spend),
            )
        }

        private fun viewsFor(context: Context, summary: WidgetSummary): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_penny_summary)
            views.setTextViewText(R.id.widget_spent_label, summary.label)
            views.setTextViewText(R.id.widget_amount, summary.amount)
            views.setTextViewText(
                R.id.widget_count,
                context.getString(R.string.widget_supporting_metrics, summary.count, summary.topCategory),
            )

            val overview = PendingIntent.getActivity(
                context,
                OVERVIEW_REQUEST_CODE,
                overviewIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val quickAdd = PendingIntent.getActivity(
                context,
                ADD_REQUEST_CODE,
                addTransactionIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, overview)
            views.setOnClickPendingIntent(R.id.widget_add_button, quickAdd)
            return views
        }

        internal fun overviewIntent(context: Context): Intent = activityIntent(context)
            .putExtra(EXTRA_OPEN_OVERVIEW, true)

        internal fun addTransactionIntent(context: Context): Intent = activityIntent(context)
            .putExtra(EXTRA_OPEN_ADD_TRANSACTION, true)

        private fun activityIntent(context: Context) = Intent(context, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
    }
}

internal data class WidgetSummary(
    val label: String,
    val amount: String,
    val count: String,
    val topCategory: String,
)
