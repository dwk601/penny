package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The provider's `errlist` mixes a non-fatal range advisory with genuinely fatal account errors.
 * Misclassifying the advisory as fatal throws away a whole sync; misclassifying a fatal entry as
 * advisory writes a partial payload. Both directions are pinned here.
 */
class SimpleFinProviderErrorTest {
    @Test fun theExactDumpAdvisoryMessageIsClassifiedAsAdvisoryNotFatal() {
        val partition = partitionProviderErrors(listOf(DUMP_ADVISORY))

        assertThat(partition.advisory).containsExactly(DUMP_ADVISORY)
        assertThat(partition.fatal).isEmpty()
    }

    @Test fun accountLevelFailuresStayFatal() {
        val partition =
            partitionProviderErrors(
                listOf("auth failure", "Connection to institution failed"),
            )

        assertThat(partition.fatal)
            .containsExactly("auth failure", "Connection to institution failed")
            .inOrder()
        assertThat(partition.advisory).isEmpty()
    }

    @Test fun mixedErrorListsSplitWithoutLosingOrDuplicatingEntries() {
        val partition =
            partitionProviderErrors(
                listOf("auth failure", DUMP_ADVISORY, "Connection to institution failed"),
            )

        assertThat(partition.advisory).containsExactly(DUMP_ADVISORY)
        assertThat(partition.fatal)
            .containsExactly("auth failure", "Connection to institution failed")
            .inOrder()
    }

    @Test fun emptyErrorListProducesNeitherAdvisoryNorFatal() {
        val partition = partitionProviderErrors(emptyList())

        assertThat(partition.advisory).isEmpty()
        assertThat(partition.fatal).isEmpty()
        assertThat(joinAdvisoryErrors(partition.advisory)).isEmpty()
    }

    @Test fun advisoryJoinKeepsAtMostThreeEntriesEachTruncatedToTwoHundredChars() {
        val advisories = (1..4).map { "$it exceeds recommended range ${"x".repeat(400)}" }

        val joined = joinAdvisoryErrors(advisories)

        val parts = joined.split("; ")
        assertThat(parts).hasSize(3)
        parts.forEach { assertThat(it).hasLength(200) }
        assertThat(joined).hasLength(3 * 200 + 2 * 2)
        assertThat(parts.map { it.first() }).containsExactly('1', '2', '3').inOrder()
        assertThat(joined).doesNotContain("4 exceeds")
    }

    @Test fun advisoriesShorterThanTheCapAreJoinedVerbatim() {
        assertThat(joinAdvisoryErrors(listOf(DUMP_ADVISORY))).isEqualTo(DUMP_ADVISORY)
        assertThat(DUMP_ADVISORY.length).isLessThan(200)
    }

    private companion object {
        /** Copied byte-for-byte from a real SimpleFIN response dump (error_code `gen.api`). */
        const val DUMP_ADVISORY =
            "Requested date range exceeds recommended range of 45 days. " +
                "In the future, this may be capped."
    }
}
