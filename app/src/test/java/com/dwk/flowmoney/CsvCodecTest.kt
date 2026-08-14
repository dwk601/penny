package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CsvCodecTest {
    private val sample = Transaction(
        id = "tx-1",
        occurredAtEpochMillis = 1766145600000,
        merchant = "Coffee, Inc.",
        category = "Food",
        note = "latte \"oat\"",
        cents = -625,
    )

    @Test fun csvRoundTripPreservesTransactionsAndEscapesFields() {
        val csv = CsvCodec.encode(listOf(sample))
        assertThat(csv).contains("\"Coffee, Inc.\"")
        assertThat(csv).contains("\"latte \"\"oat\"\"\"")

        val decoded = CsvCodec.decode(csv)

        assertThat(decoded).containsExactly(sample)
    }

    @Test fun csvRoundTripPreservesRecurringInterval() {
        val recurring = sample.copy(recurringInterval = RecurrenceInterval.Weekly)

        val decoded = CsvCodec.decode(CsvCodec.encode(listOf(recurring)))

        assertThat(decoded.single().recurringInterval).isEqualTo(RecurrenceInterval.Weekly)
    }

    @Test fun oldFlowMoneyCsvDecodesAsNonRecurring() {
        val csv = """
            id,occurredAtEpochMillis,merchant,category,note,cents
            tx-legacy,1766145600000,Rent,Home,, -120000
        """.trimIndent()

        val decoded = CsvCodec.decode(csv)

        assertThat(decoded.single().recurringInterval).isNull()
    }

    @Test fun headerlessFlowMoneyRowContainingMonarchHeaderWordsRemainsData() {
        val decoded = CsvCodec.decode("tx-legacy,1766145600000,date,amount,note,-120000")

        assertThat(decoded).containsExactly(
            sample.copy(id = "tx-legacy", merchant = "date", category = "amount", note = "note", cents = -120000),
        )
    }

    @Test fun rejectsMalformedQuotesAndMixedInvalidRows() {
        assertThat(runCatching { CsvCodec.decode("id,occurredAtEpochMillis,merchant,category,note,cents\na\"b,1,x,y,z,1") }.isFailure).isTrue()
        assertThat(runCatching { CsvCodec.decode("id,occurredAtEpochMillis,merchant,category,note,cents\nok,1,x,y,z,1\nbad,nope,x,y,z,1") }.isFailure).isTrue()
    }

    @Test fun exportNeutralizesFormulaPrefixesAfterInvisibleCharacters() {
        val csv = CsvCodec.encode(listOf(sample.copy(id = "\uFEFF =id", merchant = "\t+merchant", category = "\n-category", note = "\u0000@note")))
        assertThat(csv).contains("'\uFEFF =id")
        assertThat(csv).contains("'\t+merchant")
        assertThat(csv).contains("'\n-category")
        assertThat(csv).contains("'\u0000@note")
    }

    @Test fun monarchCsvParsesFriendlyDatesSignedAmountsAndKoreanPerson() {
        val csv = """
            "Account name","Category","Description","Person","Date","Amount","Recurring"
            "DW Kim's account","Gas","","동욱","June 19, 2026",-36.16,"No"
            "DW Kim's account","Car","Kia Finance ","동욱","June 3, 2026",-565.19,"Yes"
        """.trimIndent()

        val decoded = CsvCodec.decode(csv)

        assertThat(decoded).hasSize(2)
        assertThat(decoded[0].id).startsWith("monarch:")
        assertThat(decoded[0].merchant).isEqualTo("Gas")
        assertThat(decoded[0].category).isEqualTo("Gas")
        assertThat(decoded[0].note).contains("동욱")
        assertThat(decoded[0].cents).isEqualTo(-3616)
        assertThat(decoded[0].recurringInterval).isNull()
        assertThat(CsvCodec.localDate(decoded[0])).isEqualTo("2026-06-19")
        assertThat(decoded[1].merchant).isEqualTo("Kia Finance")
        assertThat(decoded[1].cents).isEqualTo(-56519)
        assertThat(decoded[1].recurringInterval).isEqualTo(RecurrenceInterval.Monthly)
    }

    @Test fun monarchCsvUsesStableIdsAcrossRepeatedImports() {
        val csv = """
            "Account name","Category","Description","Person","Date","Amount","Recurring"
            "DW Kim's account","Housing","Rent","동욱","June 1, 2026",-1778.0,"Yes"
        """.trimIndent()

        val first = CsvCodec.decode(csv).single()
        val second = CsvCodec.decode(csv).single()

        assertThat(second.id).isEqualTo(first.id)
    }
}
