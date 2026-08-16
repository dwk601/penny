package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.TimeZone

class CsvCodecTest {
    private val sample =
        Transaction(
            id = "tx-1",
            occurredAtEpochMillis = 1766145600000,
            merchant = "Coffee, Inc.",
            category = "Food",
            note = "latte \"oat\"",
            cents = -625,
        )

    @Test
    fun csvV2RoundTripPreservesEffectiveFlowKindButReplacesIncomingIds() {
        val overridden =
            sample.copy(
                id = "tx-override",
                flowKind = FlowKind.NORMAL,
                flowKindOverride = FlowKind.TRANSFER,
            )
        val csv = CsvCodec.encode(listOf(sample, overridden))
        assertThat(csv.lineSequence().first())
            .isEqualTo("id,occurredAtEpochMillis,merchant,category,note,cents,recurring,flowKind")
        assertThat(csv).doesNotContain("flowKindOverride")
        assertThat(csv).contains("\"Coffee, Inc.\"")
        assertThat(csv).contains("\"latte \"\"oat\"\"\"")

        val decoded = CsvCodec.decode(csv)

        assertThat(decoded[0]).isEqualTo(sample.copy(id = decoded[0].id))
        assertThat(decoded[1]).isEqualTo(
            overridden.copy(
                id = decoded[1].id,
                flowKind = FlowKind.TRANSFER,
                flowKindOverride = null,
            ),
        )
        decoded.forEach { transaction ->
            assertCsvId(transaction.id)
            assertThat(transaction.id).isNotEqualTo(sample.id)
        }
    }

    @Test
    fun csvRoundTripPreservesRecurringInterval() {
        val recurring = sample.copy(recurringInterval = RecurrenceInterval.Weekly)

        val decoded = CsvCodec.decode(CsvCodec.encode(listOf(recurring))).single()

        assertThat(decoded).isEqualTo(recurring.copy(id = decoded.id))
        assertCsvId(decoded.id)
    }

    @Test
    fun v1CurrentCsvRemainsCompatibleAndKeepsItsStableImportId() {
        val csv =
            """
            id,occurredAtEpochMillis,merchant,category,note,cents,recurring
            ignored,1766145600000,AUTOPAY PYMT,Other,legacy v1,500,Weekly
            """.trimIndent()

        val decoded = CsvCodec.decode(csv).single()

        assertThat(decoded.recurringInterval).isEqualTo(RecurrenceInterval.Weekly)
        assertThat(decoded.flowKind).isEqualTo(FlowKind.TRANSFER)
        assertThat(decoded.flowKindOverride).isNull()
        assertThat(decoded.id)
            .isEqualTo("csv:8b1a1c85a305d2e07bef77943b38d0f35ed03023a6f911cc05445558d866afd4")
    }

    @Test
    fun legacyAndMonarchRowsClassifySupportedStatementPaymentSigns() {
        val legacyCsv =
            """
            id,occurredAtEpochMillis,merchant,category,note,cents
            ignored,1766145600000,CREDIT CARD PAYMENT,Other,,-500
            """.trimIndent()
        val monarchCsv =
            """
            "Account name","Category","Description","Person","Date","Amount","Recurring"
            "Checking","Other","PAYMENT - THANK YOU","","June 1, 2026",5.00,"No"
            "Checking","Other","PAYMENT - THANK YOU","","June 2, 2026",-5.00,"No"
            """.trimIndent()

        val legacy = CsvCodec.decode(legacyCsv).single()
        val monarch = CsvCodec.decode(monarchCsv)

        assertThat(legacy.flowKind).isEqualTo(FlowKind.TRANSFER)
        assertThat(legacy.id)
            .isEqualTo("csv:59a791499168bb7684f7a5e80f2b28442e613f2b551207b5e8ba0e7006674d97")
        assertThat(monarch.map { it.flowKind })
            .containsExactly(FlowKind.TRANSFER, FlowKind.NORMAL)
            .inOrder()
    }

    @Test
    fun v2RejectsInvalidFlowKindsInsteadOfFallingBackToClassification() {
        val prefix =
            "id,occurredAtEpochMillis,merchant,category,note,cents,recurring,flowKind\n" +
                "ignored,1,PAYMENT - THANK YOU,Other,note,100,,"

        listOf("", "transfer", "UNKNOWN").forEach { invalidFlowKind ->
            assertThat(runCatching { CsvCodec.decode(prefix + invalidFlowKind) }.isFailure).isTrue()
        }
        assertThat(CsvCodec.decode(prefix + "NORMAL").single().flowKind).isEqualTo(FlowKind.NORMAL)
    }

    @Test
    fun v2StableIdsDistinguishOtherwiseIdenticalFlowKinds() {
        val csv =
            """
            id,occurredAtEpochMillis,merchant,category,note,cents,recurring,flowKind
            ignored,1,Same merchant,Other,same note,-100,,NORMAL
            ignored,1,Same merchant,Other,same note,-100,,TRANSFER
            """.trimIndent()

        val firstDecode = CsvCodec.decode(csv)
        val secondDecode = CsvCodec.decode(csv)

        assertThat(firstDecode.map { it.flowKind })
            .containsExactly(FlowKind.NORMAL, FlowKind.TRANSFER)
            .inOrder()
        assertThat(firstDecode.map { it.id }.toSet()).hasSize(2)
        assertThat(secondDecode.map { it.id }).containsExactlyElementsIn(firstDecode.map { it.id }).inOrder()
        firstDecode.forEach { assertCsvId(it.id) }
    }

    @Test
    fun oldAndHeaderlessPennyCsvDecodeWithGeneratedIds() {
        val oldCsv =
            """
            id,occurredAtEpochMillis,merchant,category,note,cents
            tx-legacy,1766145600000,Rent,Home,, -120000
            """.trimIndent()
        val headerlessCsv = "tx-forged,1766145600000,date,amount,note,-120000"

        val old = CsvCodec.decode(oldCsv).single()
        val headerless = CsvCodec.decode(headerlessCsv).single()

        assertThat(old.recurringInterval).isNull()
        assertThat(old.id).isNotEqualTo("tx-legacy")
        assertCsvId(old.id)
        assertThat(headerless).isEqualTo(
            sample.copy(
                id = headerless.id,
                merchant = "date",
                category = "amount",
                note = "note",
                cents = -120000,
            ),
        )
        assertThat(headerless.id).isNotEqualTo("tx-forged")
        assertCsvId(headerless.id)
    }

    @Test
    fun incomingTrustedLookingIdsNeverAffectGeneratedIdentity() {
        fun csv(id: String) =
            """
            id,occurredAtEpochMillis,merchant,category,note,cents,recurring
            $id,1766145600000,Coffee,Food,Same row,-625,Monthly
            """.trimIndent()

        val providerLooking = CsvCodec.decode(csv("simplefin:account:transaction")).single()
        val csvLookingInput = "csv:${"a".repeat(64)}"
        val csvLooking = CsvCodec.decode(csv(csvLookingInput)).single()

        assertThat(providerLooking.id).isEqualTo(csvLooking.id)
        assertThat(providerLooking.id).isNotEqualTo("simplefin:account:transaction")
        assertThat(csvLooking.id).isNotEqualTo(csvLookingInput)
        assertCsvId(providerLooking.id)
    }

    @Test
    fun exactDuplicateRowsReceiveStablePerFileOccurrenceIds() {
        val csv =
            """
            id,occurredAtEpochMillis,merchant,category,note,cents,recurring
            forged,1766145600000,Coffee,Food,Same row,-625,Monthly
            forged,1766145600000,Coffee,Food,Same row,-625,Monthly
            """.trimIndent()

        val firstIds = CsvCodec.decode(csv).map { it.id }
        val secondIds = CsvCodec.decode(csv).map { it.id }

        assertThat(firstIds).containsExactlyElementsIn(secondIds).inOrder()
        assertThat(firstIds.toSet()).hasSize(2)
        firstIds.forEach(::assertCsvId)
    }

    @Test
    fun canonicalEncodingNormalizesNumbersAndKeepsDecodedFieldBoundaries() {
        fun singleRow(
            timestamp: String,
            cents: String,
        ) = """
            id,occurredAtEpochMillis,merchant,category,note,cents
            ignored,$timestamp,Coffee,Food,note,$cents
            """.trimIndent()

        val paddedNumbers = CsvCodec.decode(singleRow("0001", "-001")).single()
        val canonicalNumbers = CsvCodec.decode(singleRow("1", "-1")).single()
        assertThat(paddedNumbers.id).isEqualTo(canonicalNumbers.id)

        val separator = '\u001F'
        val boundaryCsv =
            """
            id,occurredAtEpochMillis,merchant,category,note,cents
            first,1,alpha${separator}beta,gamma,note,-1
            second,1,alpha,beta${separator}gamma,note,-1
            """.trimIndent()
        val boundaryRows = CsvCodec.decode(boundaryCsv)

        assertThat(boundaryRows[0].merchant).isNotEqualTo(boundaryRows[1].merchant)
        assertThat(boundaryRows[0].category).isNotEqualTo(boundaryRows[1].category)
        assertThat(boundaryRows[0].id).isNotEqualTo(boundaryRows[1].id)
    }

    @Test
    fun monarchIdsAreStableAcrossDefaultTimeZonesWithoutUsingDerivedEpoch() {
        val csv =
            """
            "Account name","Category","Description","Person","Date","Amount","Recurring"
            "DW Kim's account","Housing","Rent","동욱","June 1, 2026",-1778.0,"Yes"
            """.trimIndent()
        val originalTimeZone = TimeZone.getDefault()

        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val utc = CsvCodec.decode(csv).single()
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val losAngeles = CsvCodec.decode(csv).single()

            assertThat(utc.occurredAtEpochMillis).isNotEqualTo(losAngeles.occurredAtEpochMillis)
            assertThat(utc.id).isEqualTo(losAngeles.id)
            assertCsvId(utc.id)
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun monarchCsvParsesFriendlyDatesSignedAmountsAndKoreanPerson() {
        val csv =
            """
            "Account name","Category","Description","Person","Date","Amount","Recurring"
            "DW Kim's account","Gas","","동욱","June 19, 2026",-36.16,"No"
            "DW Kim's account","Car","Kia Finance ","동욱","June 3, 2026",-565.19,"Yes"
            """.trimIndent()

        val decoded = CsvCodec.decode(csv)

        assertThat(decoded).hasSize(2)
        assertCsvId(decoded[0].id)
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

    @Test
    fun strictUtf8PipelineAcceptsExactBoundaryForSupportedCsvAndRejectsBadBytes() {
        val csv =
            """
            id,occurredAtEpochMillis,merchant,category,note,cents
            incoming,9223372036854775807,Café,Other,edge,-2147483648
            """.trimIndent()
        val bytes = csv.toByteArray(Charsets.UTF_8)

        val decoded =
            CsvCodec
                .decode(
                    StrictUtf8Reader.read(
                        ByteArrayInputStream(bytes),
                        maxBytes = bytes.size,
                        advertisedLength = bytes.size.toLong(),
                    ),
                ).single()

        assertThat(decoded.occurredAtEpochMillis).isEqualTo(Long.MAX_VALUE)
        assertThat(decoded.cents).isEqualTo(Int.MIN_VALUE)
        assertCsvId(decoded.id)
        assertThrows(InputTooLargeException::class.java) {
            StrictUtf8Reader.read(ByteArrayInputStream(bytes), maxBytes = bytes.size - 1)
        }
        assertThrows(MalformedUtf8Exception::class.java) {
            StrictUtf8Reader.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte())), maxBytes = 1)
        }
    }

    @Test
    fun rejectsMalformedQuotesInvalidRowsAndNumericOverflow() {
        assertThat(
            runCatching {
                CsvCodec.decode("id,occurredAtEpochMillis,merchant,category,note,cents\na\"b,1,x,y,z,1")
            }.isFailure,
        ).isTrue()
        assertThat(
            runCatching {
                CsvCodec.decode("id,occurredAtEpochMillis,merchant,category,note,cents\nok,1,x,y,z,1\nbad,nope,x,y,z,1")
            }.isFailure,
        ).isTrue()
        assertThat(
            runCatching {
                CsvCodec.decode("id,occurredAtEpochMillis,merchant,category,note,cents\nbad,9223372036854775808,x,y,z,1")
            }.isFailure,
        ).isTrue()
        assertThat(
            runCatching {
                CsvCodec.decode("id,occurredAtEpochMillis,merchant,category,note,cents\nbad,1,x,y,z,2147483648")
            }.isFailure,
        ).isTrue()
    }

    @Test
    fun exportNeutralizesFormulaPrefixesAfterInvisibleCharacters() {
        val csv =
            CsvCodec.encode(
                listOf(
                    sample.copy(
                        id = "\uFEFF =id",
                        merchant = "\t+merchant",
                        category = "\n-category",
                        note = "\u0000@note",
                    ),
                ),
            )
        assertThat(csv).contains("'\uFEFF =id")
        assertThat(csv).contains("'\t+merchant")
        assertThat(csv).contains("'\n-category")
        assertThat(csv).contains("'\u0000@note")
    }

    private fun assertCsvId(id: String) {
        assertThat(id).matches("csv:[0-9a-f]{64}")
        assertThat(CsvCodec.isImportId(id)).isTrue()
    }
}
