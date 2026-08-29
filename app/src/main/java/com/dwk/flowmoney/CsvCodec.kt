package com.dwk.flowmoney

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

object CsvCodec {
    private val legacyHeader = listOf("id", "occurredAtEpochMillis", "merchant", "category", "note", "cents")
    private val v1Header = legacyHeader + "recurring"
    private val v2Header = v1Header + "flowKind"
    private val v3Header = v2Header + listOf("locationCity", "locationState", "locationCountry")
    private val header = v3Header
    private val monarchRequiredColumns = setOf("account name", "category", "description", "person", "date", "amount", "recurring")
    private val monarchDateFormatters =
        listOf(
            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US),
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US),
            DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US),
            DateTimeFormatter.ISO_LOCAL_DATE,
        )

    fun encode(transactions: List<Transaction>) =
        buildString {
            appendLine(header.joinToString(","))
            transactions.forEach { transaction ->
                appendLine(
                    listOf(
                        escape(transaction.id),
                        transaction.occurredAtEpochMillis.toString(),
                        escape(transaction.merchant),
                        escape(transaction.category),
                        escape(transaction.note),
                        transaction.cents.toString(),
                        escape(transaction.recurringInterval?.label.orEmpty()),
                        transaction.effectiveFlowKind.name,
                        escape(transaction.locationCity.orEmpty()),
                        escape(transaction.locationState.orEmpty()),
                        escape(transaction.locationCountry.orEmpty()),
                    ).joinToString(","),
                )
            }
        }

    fun decode(csv: String): List<Transaction> {
        val rows = parseRows(csv)
        if (rows.isEmpty()) return emptyList()
        val first = rows.first().map { it.trim() }
        val isMonarch = first.map(::normalizeHeader).containsAll(monarchRequiredColumns)
        val dataRows =
            if (first == v3Header || first == v2Header || first == v1Header || first == legacyHeader || isMonarch) {
                rows.drop(1)
            } else {
                rows
            }
        require(dataRows.size <= MAX_TRANSACTIONS) { "CSV has too many transactions" }
        val decodedRows =
            when {
                first == v3Header ->
                    decodeFlowMoney(dataRows, v3Header.size, hasRecurring = true, hasFlowKind = true, hasLocation = true)
                first == v2Header -> decodeFlowMoney(dataRows, v2Header.size, hasRecurring = true, hasFlowKind = true)
                first == v1Header -> decodeFlowMoney(dataRows, v1Header.size, hasRecurring = true)
                first == legacyHeader -> decodeFlowMoney(dataRows, legacyHeader.size)
                isMonarch -> decodeMonarch(first, dataRows)
                else -> decodeFlowMoney(dataRows, legacyHeader.size)
            }
        return assignStableImportIds(decodedRows)
    }

    fun localDate(transaction: Transaction) =
        Instant
            .ofEpochMilli(transaction.occurredAtEpochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()

    private fun decodeFlowMoney(
        rows: List<List<String>>,
        columns: Int,
        hasRecurring: Boolean = false,
        hasFlowKind: Boolean = false,
        hasLocation: Boolean = false,
    ) = rows.map { row ->
        require(row.size == columns) { "Malformed FlowMoney row" }
        val occurredAtEpochMillis = row[1].trim().toLongOrNull() ?: error("Malformed FlowMoney timestamp")
        val merchant = row[2]
        val cents = row[5].trim().toIntOrNull() ?: error("Malformed FlowMoney amount")
        val flowKind =
            if (hasFlowKind) {
                parseFlowKind(row[7])
            } else {
                TransactionClassifier.classify(merchant, cents)
            }
        val locationCity = if (hasLocation) row[8].trim().ifBlank { null } else null
        val locationState = if (hasLocation) row[9].trim().ifBlank { null } else null
        val locationCountry = if (hasLocation) row[10].trim().ifBlank { null } else null
        DecodedCsvRow(
            transaction =
                Transaction(
                    id = "",
                    occurredAtEpochMillis = occurredAtEpochMillis,
                    merchant = merchant,
                    category = row[3],
                    note = row[4],
                    cents = cents,
                    recurringInterval = if (hasRecurring) parseRecurring(row[6]) else null,
                    flowKind = flowKind,
                    locationCity = locationCity,
                    locationState = locationState,
                    locationCountry = locationCountry,
                ),
            temporalKind = "epoch-millis",
            temporalValue = occurredAtEpochMillis.toString(),
            identityFlowKind = flowKind.takeIf { hasFlowKind },
            hasLocation = hasLocation,
        )
    }

    private fun decodeMonarch(
        headerRow: List<String>,
        rows: List<List<String>>,
    ): List<DecodedCsvRow> {
        val normalized = headerRow.map(::normalizeHeader)
        require(
            normalized.containsAll(monarchRequiredColumns) && normalized.distinct().size == normalized.size,
        ) { "Invalid Monarch header" }
        val indexes = normalized.withIndex().associate { it.value to it.index }
        return rows.map { row ->
            require(row.size == headerRow.size) { "Malformed Monarch row" }

            fun column(name: String) = row[indexes.getValue(name)].trim()
            val account = column("account name")
            val category = column("category").ifBlank { "Other" }
            val description = column("description")
            val person = column("person")
            val date = column("date")
            val amount = column("amount")
            val recurring = column("recurring")
            val localDate = parseMonarchDate(date) ?: error("Malformed Monarch date")
            require(MONARCH_AMOUNT.matches(amount)) { "Malformed Monarch amount" }
            val merchant = description.ifBlank { category }
            val cents = MoneyFormatter.parseAmountToCents(amount)
            DecodedCsvRow(
                transaction =
                    Transaction(
                        id = "",
                        occurredAtEpochMillis = localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        merchant = merchant,
                        category = category,
                        note =
                            listOfNotNull(
                                account.takeIf(String::isNotBlank)?.let {
                                    "Account: $it"
                                },
                                person.takeIf(String::isNotBlank)?.let {
                                    "Person: $it"
                                },
                                recurring.takeIf(String::isNotBlank)?.let { "Recurring: $it" },
                            ).joinToString("; "),
                        cents = cents,
                        recurringInterval = parseRecurring(recurring),
                        flowKind = TransactionClassifier.classify(merchant, cents),
                    ),
                // Keep the source-local date stable even though the displayed epoch uses the device zone.
                temporalKind = "local-date",
                temporalValue = localDate.toString(),
            )
        }
    }

    private fun parseMonarchDate(value: String): LocalDate? =
        monarchDateFormatters.firstNotNullOfOrNull { formatter ->
            try {
                LocalDate.parse(value, formatter)
            } catch (_: DateTimeParseException) {
                null
            }
        }

    private fun parseRecurring(value: String) =
        when (value.trim().lowercase(Locale.US)) {
            "weekly", "week" -> RecurrenceInterval.Weekly
            "yes", "monthly", "month", "recurring" -> RecurrenceInterval.Monthly
            "yearly", "year", "annual", "annually" -> RecurrenceInterval.Yearly
            else -> null
        }

    private fun parseFlowKind(value: String) =
        FlowKind.entries.firstOrNull { it.name == value.trim() } ?: error("Malformed FlowMoney flow kind")

    private fun normalizeHeader(value: String) = value.trim().lowercase(Locale.US)

    private fun assignStableImportIds(rows: List<DecodedCsvRow>): List<Transaction> {
        val occurrences = mutableMapOf<CsvRowIdentity, Int>()
        return rows.map { row ->
            val identity = row.identity()
            val occurrenceOrdinal = occurrences.getOrDefault(identity, 0) + 1
            occurrences[identity] = occurrenceOrdinal
            row.transaction.copy(id = stableImportId(identity, occurrenceOrdinal))
        }
    }

    private fun stableImportId(
        identity: CsvRowIdentity,
        occurrenceOrdinal: Int,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            when {
                identity.hasLocation -> CSV_ID_VERSION_V3
                identity.flowKindName != null -> CSV_ID_VERSION_V2
                else -> CSV_ID_VERSION_V1
            },
            identity.temporalKind,
            identity.temporalValue,
            identity.merchant,
            identity.category,
            identity.note,
            identity.cents.toString(),
            identity.recurringIntervalName,
        ).forEach { value -> digest.updateField(value) }
        identity.flowKindName?.let { digest.updateField(it) }
        if (identity.hasLocation) {
            digest.updateField(identity.locationCity)
            digest.updateField(identity.locationState)
            digest.updateField(identity.locationCountry)
        }
        digest.updateField(occurrenceOrdinal.toString())
        return CSV_ID_PREFIX + digest.digest().toLowerHex()
    }

    private fun MessageDigest.updateField(value: String?) {
        if (value == null) {
            update(0.toByte())
            return
        }
        update(1.toByte())
        val bytes = value.toByteArray(Charsets.UTF_8)
        val size = bytes.size
        update(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
        update(bytes)
    }

    private fun ByteArray.toLowerHex(): String =
        buildString(size * 2) {
            this@toLowerHex.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0f])
            }
        }

    internal fun isImportId(id: String): Boolean = CSV_ID.matches(id)

    private fun escape(value: String): String {
        val safe =
            if (value.firstOrNull { !it.isWhitespace() && !Character.isSpaceChar(it) && !it.isISOControl() && it != '\uFEFF' } in
                FORMULA_PREFIXES
            ) {
                "'$value"
            } else {
                value
            }
        val escaped = safe.replace("\"", "\"\"")
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
    }

    private fun parseRows(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var state = FieldState.Unquoted
        var fieldStarted = false
        var i = 0

        fun append(c: Char) {
            require(cell.length < MAX_CELL_CHARS) { "CSV cell too large" }
            cell.append(c)
        }

        fun addCell() {
            require(row.size < MAX_COLUMNS) { "CSV row has too many columns" }
            row += cell.toString()
            cell.clear()
            fieldStarted =
                false
            state = FieldState.Unquoted
        }

        fun finishRow() {
            addCell()
            if (row.any { it.isNotBlank() }) {
                require(rows.size < MAX_NONBLANK_ROWS) { "CSV has too many rows" }
                rows +=
                    row.toList()
            }
            row.clear()
        }
        while (i < csv.length) {
            when (val c = csv[i]) {
                '"' -> {
                    when (state) {
                        FieldState.Unquoted -> {
                            require(!fieldStarted && cell.isEmpty()) { "Quote inside unquoted CSV field" }
                            state =
                                FieldState.Quoted
                            fieldStarted = true
                        }

                        FieldState.Quoted -> {
                            if (i + 1 < csv.length &&
                                csv[i + 1] == '"'
                            ) {
                                append('"')
                                i++
                            } else {
                                state = FieldState.AfterQuote
                            }
                        }

                        FieldState.AfterQuote -> {
                            error("Characters after closing CSV quote")
                        }
                    }
                }

                ',' -> {
                    if (state == FieldState.Quoted) append(c) else addCell()
                }

                '\n' -> {
                    if (state == FieldState.Quoted) append(c) else finishRow()
                }

                '\r' -> {
                    if (state == FieldState.Quoted) {
                        append(c)
                    } else {
                        finishRow()
                        if (i + 1 < csv.length && csv[i + 1] == '\n') i++
                    }
                }

                else -> {
                    require(state != FieldState.AfterQuote) { "Characters after closing CSV quote" }
                    append(c)
                    fieldStarted = true
                }
            }
            i++
        }
        require(state != FieldState.Quoted) { "Unterminated CSV quote" }
        if (cell.isNotEmpty() || row.isNotEmpty() || fieldStarted) finishRow()
        return rows
    }

    private data class DecodedCsvRow(
        val transaction: Transaction,
        val temporalKind: String,
        val temporalValue: String,
        val identityFlowKind: FlowKind? = null,
        val hasLocation: Boolean = false,
    ) {
        fun identity() =
            CsvRowIdentity(
                temporalKind = temporalKind,
                temporalValue = temporalValue,
                merchant = transaction.merchant,
                category = transaction.category,
                note = transaction.note,
                cents = transaction.cents,
                recurringIntervalName = transaction.recurringInterval?.name,
                flowKindName = identityFlowKind?.name,
                hasLocation = hasLocation,
                locationCity = transaction.locationCity,
                locationState = transaction.locationState,
                locationCountry = transaction.locationCountry,
            )
    }

    private data class CsvRowIdentity(
        val temporalKind: String,
        val temporalValue: String,
        val merchant: String,
        val category: String,
        val note: String,
        val cents: Int,
        val recurringIntervalName: String?,
        val flowKindName: String?,
        val hasLocation: Boolean = false,
        val locationCity: String? = null,
        val locationState: String? = null,
        val locationCountry: String? = null,
    )

    private enum class FieldState { Unquoted, Quoted, AfterQuote }

    private const val MAX_NONBLANK_ROWS = 20_001
    private const val MAX_COLUMNS = 64
    private const val MAX_CELL_CHARS = 16_384
    private const val MAX_TRANSACTIONS = 20_000
    private const val CSV_ID_PREFIX = "csv:"
    private const val CSV_ID_VERSION_V1 = "penny-csv-row-v1"
    private const val CSV_ID_VERSION_V2 = "penny-csv-row-v2"
    private const val CSV_ID_VERSION_V3 = "penny-csv-row-v3"
    private val CSV_ID = Regex("csv:[0-9a-f]{64}")
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
    private val FORMULA_PREFIXES = setOf('=', '+', '-', '@')
    private val MONARCH_AMOUNT = Regex("[+-]?\\$?(?:\\d{1,3}(?:,\\d{3})*|\\d+)(?:\\.\\d{1,2})?")
}
