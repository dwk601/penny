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
    fun csvRoundTripPreservesEffectiveFlowKindButReplacesIncomingIds() {
        val overridden =
            sample.copy(
                id = "tx-override",
                flowKind = FlowKind.NORMAL,
                flowKindOverride = FlowKind.TRANSFER,
            )
        val csv = CsvCodec.encode(listOf(sample, overridden))
        // Export is v4 now: the three location columns plus the four provider/detail columns follow flowKind.
        assertThat(csv.lineSequence().first())
            .isEqualTo(
                "id,occurredAtEpochMillis,merchant,category,note,cents,recurring,flowKind," +
                    "locationCity,locationState,locationCountry," +
                    "providerDescription,accountName,transactedAtEpochMillis,reviewedAtEpochMillis",
            )
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

    @Test
    fun csvV3RoundTripPreservesLocationsWithoutInventingACountry() {
        val located =
            sample.copy(
                id = "tx-located",
                locationCity = "Salt Lake City",
                locationState = "UT",
            )
        val fullyLocated = sample.copy(id = "tx-full", locationCity = "Paris", locationState = null, locationCountry = "FR")
        val unlocated = sample.copy(id = "tx-bare")

        val csv = CsvCodec.encode(listOf(located, fullyLocated, unlocated))
        val decoded = CsvCodec.decode(csv)

        assertThat(csv).contains("Salt Lake City,UT,")
        assertThat(decoded).hasSize(3)
        assertThat(decoded[0]).isEqualTo(located.copy(id = decoded[0].id))
        assertThat(decoded[1]).isEqualTo(fullyLocated.copy(id = decoded[1].id))
        assertThat(decoded[2]).isEqualTo(unlocated.copy(id = decoded[2].id))
        assertThat(decoded[2].locationCity).isNull()
        assertThat(decoded[2].locationState).isNull()
        assertThat(decoded[2].locationCountry).isNull()
        assertThat(decoded.map { it.id }.toSet()).hasSize(3)
        decoded.forEach { assertCsvId(it.id) }

        // A second pass over the same export reproduces exactly the same identities.
        assertThat(CsvCodec.decode(csv).map { it.id })
            .containsExactlyElementsIn(decoded.map { it.id })
            .inOrder()
    }

    @Test
    fun syncedExportThenLocalImportKeepsStableIdsAcrossRepeatedRoundTrips() {
        val synced =
            listOf(
                sample.copy(
                    id = "simplefin:origin:account:one",
                    source = "simplefin",
                    accountKey = "simplefin:v2:account",
                    accountName = "Checking",
                    providerDescription = "TRADER JOES #123, PORTLAND OR",
                    providerMerchant = "TRADER JOES",
                    reviewedAtEpochMillis = 42L,
                    locationCity = "PORTLAND",
                    locationState = "OR",
                ),
                sample.copy(
                    id = "simplefin:origin:account:two",
                    source = "simplefin",
                    providerDescription = "STARBUCKS STORE SEATTLE WA",
                    note = "no parseable location",
                ),
            )

        val firstExport = CsvCodec.encode(synced)
        val firstImport = CsvCodec.decode(firstExport)
        val secondExport = CsvCodec.encode(firstImport)
        val secondImport = CsvCodec.decode(secondExport)

        // Everything except the id column survives the synced -> CSV -> local round trip untouched.
        assertThat(secondExport.withoutIdColumn()).isEqualTo(firstExport.withoutIdColumn())
        assertThat(secondImport.map { it.id }).containsExactlyElementsIn(firstImport.map { it.id }).inOrder()
        assertThat(firstImport[0].locationCity).isEqualTo("PORTLAND")
        assertThat(firstImport[0].locationState).isEqualTo("OR")
        assertThat(firstImport[0].locationCountry).isNull()
        assertThat(firstImport[1].locationCity).isNull()
        firstImport.forEach { transaction ->
            assertCsvId(transaction.id)
            assertThat(transaction.id).isNotEqualTo("simplefin:origin:account:one")
        }
    }

    @Test
    fun addingLocationColumnsLeftEveryOlderImportIdentityByteForByteIdentical() {
        val v1Csv =
            """
            id,occurredAtEpochMillis,merchant,category,note,cents,recurring
            ignored,1766145600000,AUTOPAY PYMT,Other,legacy v1,500,Weekly
            """.trimIndent()
        val v2Csv =
            """
            id,occurredAtEpochMillis,merchant,category,note,cents,recurring,flowKind
            ignored,1766145600000,AUTOPAY PYMT,Other,legacy v2,500,Weekly,TRANSFER
            """.trimIndent()
        val legacyCsv =
            """
            id,occurredAtEpochMillis,merchant,category,note,cents
            ignored,1766145600000,CREDIT CARD PAYMENT,Other,,-500
            """.trimIndent()
        val monarchCsv =
            """
            "Account name","Category","Description","Person","Date","Amount","Recurring"
            "Checking","Other","PAYMENT - THANK YOU","","June 1, 2026",5.00,"No"
            """.trimIndent()

        val v1 = CsvCodec.decode(v1Csv).single()
        val v2 = CsvCodec.decode(v2Csv).single()
        val legacy = CsvCodec.decode(legacyCsv).single()
        val monarch = CsvCodec.decode(monarchCsv).single()

        assertThat(v1.id).isEqualTo("csv:8b1a1c85a305d2e07bef77943b38d0f35ed03023a6f911cc05445558d866afd4")
        assertThat(v2.id).isEqualTo("csv:9bb0ee5924e20651889e6dd67e9ded57b42b0d990dff41d80f58fe7b856e2d0b")
        assertThat(legacy.id).isEqualTo("csv:59a791499168bb7684f7a5e80f2b28442e613f2b551207b5e8ba0e7006674d97")
        assertThat(monarch.id).isEqualTo("csv:6e2ab23479f34195cd8c5b3c5750f7083dcb111fc3caaf1b8ccca74a114714da")

        listOf(v1, v2, legacy, monarch).forEach { transaction ->
            assertThat(transaction.locationCity).isNull()
            assertThat(transaction.locationState).isNull()
            assertThat(transaction.locationCountry).isNull()
        }
        assertThat(v2.flowKind).isEqualTo(FlowKind.TRANSFER)
        assertThat(v2.recurringInterval).isEqualTo(RecurrenceInterval.Weekly)
    }

    @Test
    fun v3IdentityIsVersionedSoBlankLocationCellsNeverCollideWithV2Rows() {
        val v3Csv =
            """
            id,occurredAtEpochMillis,merchant,category,note,cents,recurring,flowKind,locationCity,locationState,locationCountry
            ignored,1766145600000,AUTOPAY PYMT,Other,legacy v3,500,Weekly,TRANSFER,Portland,OR,
            """.trimIndent()
        val v3BlankCsv =
            """
            id,occurredAtEpochMillis,merchant,category,note,cents,recurring,flowKind,locationCity,locationState,locationCountry
            ignored,1766145600000,AUTOPAY PYMT,Other,legacy v2,500,Weekly,TRANSFER,,,
            """.trimIndent()

        val located = CsvCodec.decode(v3Csv).single()
        val blank = CsvCodec.decode(v3BlankCsv).single()

        assertThat(located.locationCity).isEqualTo("Portland")
        assertThat(located.locationState).isEqualTo("OR")
        assertThat(located.locationCountry).isNull()
        assertThat(located.id).isEqualTo("csv:f55e00dd6622f4ca05e12b3556ba102f590c1fc300bbfbea2fe597cea6fe8440")

        assertThat(blank.locationCity).isNull()
        assertThat(blank.id).isEqualTo("csv:adffde07895f0186321969b33077b7f6274e8b7889e02912d7f8e11e455674c3")
        // Same visible fields as the pinned v2 row above, different identity version.
        assertThat(blank.id).isNotEqualTo("csv:9bb0ee5924e20651889e6dd67e9ded57b42b0d990dff41d80f58fe7b856e2d0b")
    }

    @Test
    fun v3RowsWithTheWrongColumnCountAreRejected() {
        val header =
            "id,occurredAtEpochMillis,merchant,category,note,cents,recurring,flowKind," +
                "locationCity,locationState,locationCountry"

        assertThat(
            runCatching { CsvCodec.decode("$header\nignored,1,Coffee,Food,note,-1,,NORMAL,Portland,OR") }.isFailure,
        ).isTrue()
        assertThat(
            runCatching { CsvCodec.decode("$header\nignored,1,Coffee,Food,note,-1,,NORMAL,Portland,OR,US,extra") }.isFailure,
        ).isTrue()
        assertThat(
            runCatching { CsvCodec.decode("$header\nignored,1,Coffee,Food,note,-1,,NOPE,Portland,OR,US") }.isFailure,
        ).isTrue()
    }

    @Test
    fun exportNeutralizesFormulaPrefixesInsideLocationCells() {
        val csv =
            CsvCodec.encode(
                listOf(
                    sample.copy(
                        locationCity = "=HYPERLINK(\"http://evil\")",
                        locationState = "+OR",
                        locationCountry = "-US",
                    ),
                    sample.copy(id = "tx-2", locationCity = "@Portland", locationState = "\uFEFF =WA", locationCountry = "US"),
                ),
            )

        assertThat(csv).contains("'=HYPERLINK")
        assertThat(csv).contains("'+OR")
        assertThat(csv).contains("'-US")
        assertThat(csv).contains("'@Portland")
        assertThat(csv).contains("'\uFEFF =WA")
        assertThat(csv).doesNotContain(",=HYPERLINK")
        assertThat(csv).doesNotContain(",+OR")
        assertThat(csv).doesNotContain(",@Portland")

        val decoded = CsvCodec.decode(csv)
        assertThat(decoded[0].locationCity).isEqualTo("'=HYPERLINK(\"http://evil\")")
        assertThat(decoded[0].locationState).isEqualTo("'+OR")
        assertThat(decoded[1].locationCity).isEqualTo("'@Portland")
    }

    @Test
    fun csvV4RoundTripPreservesProviderDetailsAndReviewTimestamps() {
        val detailed =
            sample.copy(
                id = "tx-detailed",
                providerDescription = "TRADER JOES #123, PORTLAND OR",
                accountName = "Checking",
                transactedAtEpochMillis = 1766142000000,
                reviewedAtEpochMillis = 1766149200000,
            )
        val bare = sample.copy(id = "tx-bare")

        val csv = CsvCodec.encode(listOf(detailed, bare))
        val decoded = CsvCodec.decode(csv)

        assertThat(decoded).hasSize(2)
        assertThat(decoded[0].providerDescription).isEqualTo("TRADER JOES #123, PORTLAND OR")
        assertThat(decoded[0].accountName).isEqualTo("Checking")
        assertThat(decoded[0].transactedAtEpochMillis).isEqualTo(1766142000000)
        assertThat(decoded[0].reviewedAtEpochMillis).isEqualTo(1766149200000)
        assertThat(decoded[0]).isEqualTo(detailed.copy(id = decoded[0].id))

        assertThat(decoded[1].providerDescription).isNull()
        assertThat(decoded[1].accountName).isNull()
        assertThat(decoded[1].transactedAtEpochMillis).isNull()
        assertThat(decoded[1].reviewedAtEpochMillis).isNull()
        assertThat(decoded[1]).isEqualTo(bare.copy(id = decoded[1].id))

        assertThat(decoded.map { it.id }.toSet()).hasSize(2)
        decoded.forEach { assertCsvId(it.id) }
        // A second pass over the same export reproduces exactly the same identities.
        assertThat(CsvCodec.decode(csv).map { it.id }).containsExactlyElementsIn(decoded.map { it.id }).inOrder()
    }

    @Test
    fun v4StableIdsDistinguishRowsThatDifferOnlyByProviderDescription() {
        val csv =
            CsvCodec.encode(
                listOf(
                    sample.copy(id = "tx-a", providerDescription = "SQ *COFFEE INC 001"),
                    sample.copy(id = "tx-b", providerDescription = "SQ *COFFEE INC 002"),
                ),
            )

        val firstDecode = CsvCodec.decode(csv)
        val secondDecode = CsvCodec.decode(csv)

        assertThat(firstDecode.map { it.providerDescription })
            .containsExactly("SQ *COFFEE INC 001", "SQ *COFFEE INC 002")
            .inOrder()
        assertThat(firstDecode.map { it.id }.toSet()).hasSize(2)
        assertThat(secondDecode.map { it.id }).containsExactlyElementsIn(firstDecode.map { it.id }).inOrder()
        firstDecode.forEach { assertCsvId(it.id) }
    }

    @Test
    fun v4StableIdsIgnoreTheReviewTimestampSoAFilledExportKeepsItsIdentity() {
        val unreviewed = sample.copy(id = "tx-unreviewed", providerDescription = "SQ *COFFEE", reviewedAtEpochMillis = null)
        val reviewed = unreviewed.copy(id = "tx-reviewed", reviewedAtEpochMillis = 1766149200000)

        val unreviewedDecoded = CsvCodec.decode(CsvCodec.encode(listOf(unreviewed))).single()
        val reviewedDecoded = CsvCodec.decode(CsvCodec.encode(listOf(reviewed))).single()

        // Review state is user-owned bookkeeping, not row identity: filling it must not forge a new row.
        assertThat(reviewedDecoded.id).isEqualTo(unreviewedDecoded.id)
        assertThat(unreviewedDecoded.reviewedAtEpochMillis).isNull()
        assertThat(reviewedDecoded.reviewedAtEpochMillis).isEqualTo(1766149200000)
        assertCsvId(unreviewedDecoded.id)
        // Every other detail column still participates in identity.
        assertThat(CsvCodec.decode(CsvCodec.encode(listOf(reviewed.copy(providerDescription = "SQ *TEA")))).single().id)
            .isNotEqualTo(unreviewedDecoded.id)
        assertThat(CsvCodec.decode(CsvCodec.encode(listOf(reviewed.copy(accountName = "Savings")))).single().id)
            .isNotEqualTo(unreviewedDecoded.id)
        assertThat(CsvCodec.decode(CsvCodec.encode(listOf(reviewed.copy(transactedAtEpochMillis = 1766142000000)))).single().id)
            .isNotEqualTo(unreviewedDecoded.id)
    }

    /** Drops the leading id cell; every fixture id in this file is comma-free. */
    private fun String.withoutIdColumn(): String = lineSequence().joinToString("\n") { it.substringAfter(',') }

    private fun assertCsvId(id: String) {
        assertThat(id).matches("csv:[0-9a-f]{64}")
        assertThat(CsvCodec.isImportId(id)).isTrue()
    }
}
