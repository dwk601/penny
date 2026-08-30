package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Bucketing rules for the Insights timeline. Week mode is 1:1 with the daily list; Month mode
 * chunks the ordered daily list into weeks anchored at the range start, with the trailing chunk
 * clipped by the range end.
 */
class DashboardAnalyticsSpendBucketsTest {
    @Test fun weekBucketsAreOneToOneWithDailySpending() {
        val range =
            DashboardDateRange(
                startInclusive = LocalDate.of(2026, 7, 5),
                endExclusive = LocalDate.of(2026, 7, 12),
                label = "Last 7 days",
            )
        val daily =
            (0L until 7L).map { offset ->
                DailySpend(range.startInclusive.plusDays(offset), 100L * (offset + 1))
            }

        val buckets = DashboardAnalytics.spendBuckets(daily, ChartRangeMode.Week, range)

        assertThat(buckets).hasSize(daily.size)
        assertThat(buckets.map { it.key }).isEqualTo(daily.map { it.date.toString() })
        assertThat(buckets.map { it.startInclusive }).isEqualTo(daily.map { it.date })
        assertThat(buckets.map { it.cents }).isEqualTo(daily.map { it.cents })
        buckets.forEach { bucket ->
            assertThat(bucket.endExclusive).isEqualTo(bucket.startInclusive.plusDays(1))
        }
        assertThat(buckets.first().label).isEqualTo("Sun 5")
        assertThat(buckets.last().label).isEqualTo("Sat 11")
    }

    @Test fun weekBucketEndIsClippedByTheRangeEnd() {
        val range =
            DashboardDateRange(
                startInclusive = LocalDate.of(2026, 7, 10),
                endExclusive = LocalDate.of(2026, 7, 11),
                label = "Last 7 days",
            )

        val buckets =
            DashboardAnalytics.spendBuckets(
                listOf(DailySpend(LocalDate.of(2026, 7, 10), 500L)),
                ChartRangeMode.Week,
                range,
            )

        assertThat(buckets).hasSize(1)
        assertThat(buckets.single().endExclusive).isEqualTo(LocalDate.of(2026, 7, 11))
    }

    @Test fun monthBucketsChunkJulyIntoFiveWeeksAnchoredAtTheRangeStart() {
        val buckets = DashboardAnalytics.spendBuckets(julyDaily(), ChartRangeMode.Month, JULY)

        assertThat(buckets.map { it.startInclusive })
            .containsExactly(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 8),
                LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 7, 22),
                LocalDate.of(2026, 7, 29),
            ).inOrder()
        assertThat(buckets.map { it.key })
            .containsExactly(
                "2026-07-01",
                "2026-07-08",
                "2026-07-15",
                "2026-07-22",
                "2026-07-29",
            ).inOrder()
        assertThat(buckets.map { it.endExclusive })
            .containsExactly(
                LocalDate.of(2026, 7, 8),
                LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 7, 22),
                LocalDate.of(2026, 7, 29),
                LocalDate.of(2026, 8, 1),
            ).inOrder()
        // The trailing chunk holds only Jul 29-31 and is clipped by the range end.
        val last = buckets.last()
        assertThat(ChronoUnit.DAYS.between(last.startInclusive, last.endExclusive)).isEqualTo(3L)
        assertThat(last.endExclusive).isEqualTo(JULY.endExclusive)
        assertThat(buckets.map { it.label })
            .containsExactly("Jul 1–7", "Jul 8–14", "Jul 15–21", "Jul 22–28", "Jul 29–31")
            .inOrder()
    }

    @Test fun monthBucketTotalsSumToTheDailyTotal() {
        val daily = julyDaily()

        val buckets = DashboardAnalytics.spendBuckets(daily, ChartRangeMode.Month, JULY)

        assertThat(buckets.sumOf { it.cents }).isEqualTo(daily.sumOf { it.cents })
        assertThat(buckets.map { it.cents })
            .containsExactly(
                daily.subList(0, 7).sumOf { it.cents },
                daily.subList(7, 14).sumOf { it.cents },
                daily.subList(14, 21).sumOf { it.cents },
                daily.subList(21, 28).sumOf { it.cents },
                daily.subList(28, 31).sumOf { it.cents },
            ).inOrder()
    }

    @Test fun monthBucketOfASingleDayLabelsThatDay() {
        val range =
            DashboardDateRange(
                startInclusive = LocalDate.of(2026, 7, 1),
                endExclusive = LocalDate.of(2026, 7, 2),
                label = "July 2026",
            )

        val buckets =
            DashboardAnalytics.spendBuckets(
                listOf(DailySpend(LocalDate.of(2026, 7, 1), 1_250L)),
                ChartRangeMode.Month,
                range,
            )

        assertThat(buckets).hasSize(1)
        assertThat(buckets.single().label).isEqualTo("Jul 1")
        assertThat(buckets.single().endExclusive).isEqualTo(LocalDate.of(2026, 7, 2))
    }

    @Test fun emptyDailySpendingProducesNoBuckets() {
        assertThat(DashboardAnalytics.spendBuckets(emptyList(), ChartRangeMode.Month, JULY)).isEmpty()
        assertThat(DashboardAnalytics.spendBuckets(emptyList(), ChartRangeMode.Week, JULY)).isEmpty()
    }

    private fun julyDaily(): List<DailySpend> =
        (0L until 31L).map { offset ->
            DailySpend(JULY.startInclusive.plusDays(offset), 100L * (offset + 1))
        }

    private companion object {
        val JULY =
            DashboardDateRange(
                startInclusive = LocalDate.of(2026, 7, 1),
                endExclusive = LocalDate.of(2026, 8, 1),
                label = "July 2026",
            )
    }
}
