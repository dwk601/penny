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
    private val header = legacyHeader + "recurring"
    private val monarchRequiredColumns = setOf("account name", "category", "description", "person", "date", "amount", "recurring")
    private val monarchDateFormatters = listOf(DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US), DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US), DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US), DateTimeFormatter.ISO_LOCAL_DATE)

    fun encode(transactions: List<Transaction>) = buildString {
        appendLine(header.joinToString(","))
        transactions.forEach { transaction -> appendLine(listOf(escape(transaction.id), transaction.occurredAtEpochMillis.toString(), escape(transaction.merchant), escape(transaction.category), escape(transaction.note), transaction.cents.toString(), escape(transaction.recurringInterval?.label.orEmpty())).joinToString(",")) }
    }

    fun decode(csv: String): List<Transaction> {
        val rows = parseRows(csv)
        if (rows.isEmpty()) return emptyList()
        val first = rows.first().map { it.trim() }
        val isMonarch = first.map(::normalizeHeader).containsAll(monarchRequiredColumns)
        val dataRows = if (first == header || first == legacyHeader || isMonarch) rows.drop(1) else rows
        require(dataRows.size <= MAX_TRANSACTIONS) { "CSV has too many transactions" }
        val decoded = when {
            first == header -> decodeFlowMoney(dataRows, header.size)
            first == legacyHeader -> decodeFlowMoney(dataRows, legacyHeader.size)
            isMonarch -> decodeMonarch(first, dataRows)
            else -> decodeFlowMoney(dataRows, legacyHeader.size)
        }
        return decoded
    }

    fun localDate(transaction: Transaction) = Instant.ofEpochMilli(transaction.occurredAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun decodeFlowMoney(rows: List<List<String>>, columns: Int) = rows.map { row ->
        require(row.size == columns) { "Malformed FlowMoney row" }
        Transaction(
            id = row[0],
            occurredAtEpochMillis = row[1].trim().toLongOrNull() ?: error("Malformed FlowMoney timestamp"),
            merchant = row[2], category = row[3], note = row[4],
            cents = row[5].trim().toIntOrNull() ?: error("Malformed FlowMoney amount"),
            recurringInterval = if (columns == header.size) parseRecurring(row[6]) else null,
        )
    }

    private fun decodeMonarch(headerRow: List<String>, rows: List<List<String>>): List<Transaction> {
        val normalized = headerRow.map(::normalizeHeader)
        require(normalized.containsAll(monarchRequiredColumns) && normalized.distinct().size == normalized.size) { "Invalid Monarch header" }
        val indexes = normalized.withIndex().associate { it.value to it.index }
        return rows.map { row ->
            require(row.size == headerRow.size) { "Malformed Monarch row" }
            fun column(name: String) = row[indexes.getValue(name)].trim()
            val account = column("account name"); val category = column("category").ifBlank { "Other" }; val description = column("description")
            val person = column("person"); val date = column("date"); val amount = column("amount"); val recurring = column("recurring")
            val localDate = parseMonarchDate(date) ?: error("Malformed Monarch date")
            require(MONARCH_AMOUNT.matches(amount)) { "Malformed Monarch amount" }
            Transaction(
                // ponytail: stable Monarch hash is only a decode-time placeholder; repository remaps document imports.
                id = stableImportId("monarch", listOf(account, category, description, person, date, amount, recurring)),
                occurredAtEpochMillis = localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                merchant = description.ifBlank { category }, category = category,
                note = listOfNotNull(account.takeIf(String::isNotBlank)?.let { "Account: $it" }, person.takeIf(String::isNotBlank)?.let { "Person: $it" }, recurring.takeIf(String::isNotBlank)?.let { "Recurring: $it" }).joinToString("; "),
                cents = MoneyFormatter.parseAmountToCents(amount), recurringInterval = parseRecurring(recurring),
            )
        }
    }

    private fun parseMonarchDate(value: String): LocalDate? = monarchDateFormatters.firstNotNullOfOrNull { formatter -> try { LocalDate.parse(value, formatter) } catch (_: DateTimeParseException) { null } }
    private fun parseRecurring(value: String) = when (value.trim().lowercase(Locale.US)) { "weekly", "week" -> RecurrenceInterval.Weekly; "yes", "monthly", "month", "recurring" -> RecurrenceInterval.Monthly; "yearly", "year", "annual", "annually" -> RecurrenceInterval.Yearly; else -> null }
    private fun normalizeHeader(value: String) = value.trim().lowercase(Locale.US)
    private fun stableImportId(prefix: String, values: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(values.joinToString("\u001F") { it.trim() }.toByteArray(Charsets.UTF_8))
        return "$prefix:" + digest.take(12).joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }
    private fun escape(value: String): String {
        val safe = if (value.firstOrNull { !it.isWhitespace() && !Character.isSpaceChar(it) && !it.isISOControl() && it != '\uFEFF' } in FORMULA_PREFIXES) "'$value" else value
        val escaped = safe.replace("\"", "\"\"")
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
    }

    private fun parseRows(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); val row = mutableListOf<String>(); val cell = StringBuilder()
        var state = FieldState.Unquoted; var fieldStarted = false; var i = 0
        fun append(c: Char) { require(cell.length < MAX_CELL_CHARS) { "CSV cell too large" }; cell.append(c) }
        fun addCell() { require(row.size < MAX_COLUMNS) { "CSV row has too many columns" }; row += cell.toString(); cell.clear(); fieldStarted = false; state = FieldState.Unquoted }
        fun finishRow() { addCell(); if (row.any { it.isNotBlank() }) { require(rows.size < MAX_NONBLANK_ROWS) { "CSV has too many rows" }; rows += row.toList() }; row.clear() }
        while (i < csv.length) {
            when (val c = csv[i]) {
                '"' -> when (state) {
                    FieldState.Unquoted -> { require(!fieldStarted && cell.isEmpty()) { "Quote inside unquoted CSV field" }; state = FieldState.Quoted; fieldStarted = true }
                    FieldState.Quoted -> if (i + 1 < csv.length && csv[i + 1] == '"') { append('"'); i++ } else state = FieldState.AfterQuote
                    FieldState.AfterQuote -> error("Characters after closing CSV quote")
                }
                ',' -> if (state == FieldState.Quoted) append(c) else addCell()
                '\n' -> if (state == FieldState.Quoted) append(c) else finishRow()
                '\r' -> if (state == FieldState.Quoted) append(c) else { finishRow(); if (i + 1 < csv.length && csv[i + 1] == '\n') i++ }
                else -> { require(state != FieldState.AfterQuote) { "Characters after closing CSV quote" }; append(c); fieldStarted = true }
            }; i++
        }
        require(state != FieldState.Quoted) { "Unterminated CSV quote" }
        if (cell.isNotEmpty() || row.isNotEmpty() || fieldStarted) finishRow()
        return rows
    }

    private enum class FieldState { Unquoted, Quoted, AfterQuote }
    private const val MAX_NONBLANK_ROWS = 20_001; private const val MAX_COLUMNS = 64; private const val MAX_CELL_CHARS = 16_384; private const val MAX_TRANSACTIONS = 20_000
    private val FORMULA_PREFIXES = setOf('=', '+', '-', '@')
    private val MONARCH_AMOUNT = Regex("[+-]?\\$?(?:\\d{1,3}(?:,\\d{3})*|\\d+)(?:\\.\\d{1,2})?")
}
