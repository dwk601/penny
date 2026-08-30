package com.dwk.flowmoney

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class SimpleFinPlatformTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun applicationDisallowsBackup() {
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun keystoreCredentialPersistsAcrossStoreRecreationAndRejectsWrongIdentityAndCorruption() {
        val file = File(context.noBackupFilesDir, SimpleFinCredentialStore.CREDENTIAL_FILE_NAME)
        file.deleteRecursively()
        val first = SimpleFinCredentialStore(context)
        first.save("connection-a", ACCESS_URL)

        val recreated = SimpleFinCredentialStore(context)
        assertEquals(ACCESS_URL, recreated.read("connection-a"))
        assertNull(recreated.read("connection-b"))

        file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        assertNull(recreated.read("connection-a"))

        recreated.delete()
        assertTrue(!file.exists())
    }

    @Test
    fun keystoreCredentialDeleteReportsFilesystemFailure() {
        val file = File(context.noBackupFilesDir, SimpleFinCredentialStore.CREDENTIAL_FILE_NAME)
        file.deleteRecursively()
        try {
            assertTrue(file.mkdir())
            File(file, "prevents-directory-delete").writeText("x")

            assertThrows(IllegalStateException::class.java) {
                SimpleFinCredentialStore(context).delete()
            }
        } finally {
            file.deleteRecursively()
        }
    }

    @Test
    fun pendingCredentialIsEncryptedPromotedWithoutEarlyDeletionAndExplicitlyDeleted() {
        val credentialFile = File(context.noBackupFilesDir, SimpleFinCredentialStore.CREDENTIAL_FILE_NAME)
        val pendingFile = File(context.noBackupFilesDir, SimpleFinCredentialStore.PENDING_FILE_NAME)
        credentialFile.deleteRecursively()
        pendingFile.deleteRecursively()
        val store = SimpleFinCredentialStore(context)
        try {
            store.stage("pending-connection", ACCESS_URL)

            assertTrue(pendingFile.isFile)
            assertFalse(pendingFile.readBytes().containsSubsequence(ACCESS_URL.toByteArray(StandardCharsets.UTF_8)))
            assertEquals(
                SimpleFinPendingCredential("pending-connection", ACCESS_URL),
                SimpleFinCredentialStore(context).readPending(),
            )
            assertNull(store.read("pending-connection"))

            store.promotePending("pending-connection", previousConnectionId = null, previousAccessUrl = null)

            assertTrue(pendingFile.exists())
            assertEquals(ACCESS_URL, store.read("pending-connection"))
            assertFalse(credentialFile.readBytes().containsSubsequence(ACCESS_URL.toByteArray(StandardCharsets.UTF_8)))

            val stagedBytes = pendingFile.readBytes()
            pendingFile.writeBytes(credentialFile.readBytes())
            assertNull(store.readPending())
            pendingFile.writeBytes(stagedBytes)
            assertEquals("pending-connection", store.readPending()?.connectionId)

            store.deletePending()
            assertFalse(pendingFile.exists())
            assertEquals(ACCESS_URL, store.read("pending-connection"))

            store.delete()
            assertFalse(credentialFile.exists())
        } finally {
            credentialFile.deleteRecursively()
            pendingFile.deleteRecursively()
        }
    }

    @Test
    fun rollbackCredentialSurvivesPromotionAndStoreRecreationEncrypted() {
        val credentialFile = File(context.noBackupFilesDir, SimpleFinCredentialStore.CREDENTIAL_FILE_NAME)
        val pendingFile = File(context.noBackupFilesDir, SimpleFinCredentialStore.PENDING_FILE_NAME)
        val rollbackFile = File(context.noBackupFilesDir, SimpleFinCredentialStore.ROLLBACK_FILE_NAME)
        credentialFile.deleteRecursively()
        pendingFile.deleteRecursively()
        rollbackFile.deleteRecursively()
        val store = SimpleFinCredentialStore(context)
        try {
            store.save("published-connection", OLD_ACCESS_URL)
            store.stage("pending-connection", ACCESS_URL)
            store.promotePending("pending-connection", "published-connection", OLD_ACCESS_URL)

            assertEquals(ACCESS_URL, store.read("pending-connection"))
            assertTrue(pendingFile.isFile)
            assertTrue(rollbackFile.isFile)
            assertFalse(rollbackFile.readBytes().containsSubsequence(OLD_ACCESS_URL.toByteArray(StandardCharsets.UTF_8)))

            val recreated = SimpleFinCredentialStore(context)
            assertTrue(recreated.restoreRollback("published-connection"))
            assertEquals(OLD_ACCESS_URL, recreated.read("published-connection"))
            assertEquals(SimpleFinPendingCredential("pending-connection", ACCESS_URL), recreated.readPending())

            recreated.deleteRollback()
            recreated.deletePending()
            recreated.delete()
            assertFalse(rollbackFile.exists())
            assertFalse(pendingFile.exists())
            assertFalse(credentialFile.exists())
        } finally {
            credentialFile.deleteRecursively()
            pendingFile.deleteRecursively()
            rollbackFile.deleteRecursively()
        }
    }

    @Test
    fun setupTokenBoundsBase64Utf8AndHttpsValidationAreStrict() {
        val client = SimpleFinClient()
        val claimUrl = "https://example.com/claim?token=~aa"
        val bytes = claimUrl.toByteArray(StandardCharsets.UTF_8)
        val standard = Base64.getEncoder().encodeToString(bytes)
        val urlSafe = Base64.getUrlEncoder().encodeToString(bytes)
        assertTrue(standard.contains('+'))
        assertTrue(urlSafe.contains('-'))
        assertEquals(claimUrl, client.decodeSetupToken(standard))
        assertEquals(claimUrl, client.decodeSetupToken(urlSafe))

        val exactLimitUrl =
            "https://example.com/" +
                "x".repeat(SimpleFinClient.MAX_SETUP_CLAIM_URL_BYTES - "https://example.com/".length)
        val exactLimitToken = Base64.getEncoder().encodeToString(exactLimitUrl.toByteArray(StandardCharsets.UTF_8))
        assertEquals(SimpleFinClient.MAX_SETUP_TOKEN_ENCODED_CHARS, exactLimitToken.length)
        assertEquals(exactLimitUrl, client.decodeSetupToken(exactLimitToken))

        fun assertMalformed(token: String) {
            val failure = assertThrows(SimpleFinException::class.java) { client.decodeSetupToken(token) }
            assertEquals(SimpleFinFailureKind.MALFORMED_TOKEN, failure.kind)
        }

        assertMalformed("A".repeat(SimpleFinClient.MAX_SETUP_TOKEN_ENCODED_CHARS + 1))
        assertMalformed("%%%not-base64%%%")
        assertMalformed(Base64.getEncoder().encodeToString(byteArrayOf(0xC3.toByte())))
        assertMalformed(Base64.getEncoder().encodeToString("http://example.com/claim".toByteArray()))
        assertMalformed(Base64.getEncoder().encodeToString("https:///missing-host".toByteArray()))
        assertMalformed(Base64.getEncoder().encodeToString("https://user:pass@example.com/claim".toByteArray()))

        assertThrows(SimpleFinException::class.java) {
            SimpleFinClient.validateAccessUrl("http://user:pass@example.com/simplefin")
        }
        assertThrows(SimpleFinException::class.java) {
            SimpleFinClient.validateAccessUrl("https://example.com/simplefin")
        }
    }

    @Test
    fun parsesBridgeV2StructuredErrorsWithoutDiscardingAccounts() {
        val result =
            SimpleFinClient().parseAccounts(
                """
                {
                    "errors":[],
                    "errlist":[{"type":"act.failed","account":"broken"},{"type":"con.auth"}],
                    "accounts":[{
                        "conn_id":"provider-connection","id":"working","name":"Working","currency":"USD",
                        "transactions":[{"id":"tx","posted":1700000000,"amount":"-1.00","description":"Shop"}]
                    }]
                }
                """.trimIndent(),
            )

        assertEquals(1, result.accounts.size)
        assertEquals("provider-connection", result.accounts.single().providerConnectionId)
        assertEquals(
            "tx",
            result.accounts
                .single()
                .transactions
                .single()
                .id,
        )
        assertEquals(2, result.errors.size)
        assertTrue(result.errors.joinToString().contains("act.failed"))
        assertTrue(result.errors.joinToString().contains("con.auth"))
    }

    /**
     * Lives here rather than in the JVM `SimpleFinClientTest` because `org.json` is stubbed on the
     * unit-test classpath; `parseAccounts` coverage has always been instrumented.
     */
    @Test
    fun parsesTransactedAtAndBalanceDateAndTreatsAbsentZeroOrNegativeAsNull() {
        val result =
            SimpleFinClient().parseAccounts(
                """
                {
                    "accounts":[
                        {
                            "conn_id":"connection","id":"acct-1","name":"Checking","currency":"USD",
                            "balance":"12.34","available-balance":"10.00","balance-date":1766145600,
                            "transactions":[
                                {"id":"present","posted":1766145600,"amount":"-1.00",
                                 "description":"Store","transacted_at":1766059200},
                                {"id":"absent","posted":1766145601,"amount":"-2.00","description":"Store"},
                                {"id":"zero","posted":1766145602,"amount":"-3.00",
                                 "description":"Store","transacted_at":0},
                                {"id":"negative","posted":1766145603,"amount":"-4.00",
                                 "description":"Store","transacted_at":-5}
                            ]
                        },
                        {"conn_id":"connection","id":"acct-absent","name":"Absent","transactions":[]},
                        {"conn_id":"connection","id":"acct-zero","name":"Zero","balance-date":0,"transactions":[]},
                        {"conn_id":"connection","id":"acct-negative","name":"Negative","balance-date":-1,
                         "transactions":[]}
                    ]
                }
                """.trimIndent(),
            )

        val accounts = result.accounts.associateBy { it.id }
        assertEquals(1766145600L, accounts.getValue("acct-1").balanceDate)
        assertNull(accounts.getValue("acct-absent").balanceDate)
        assertNull(accounts.getValue("acct-zero").balanceDate)
        assertNull(accounts.getValue("acct-negative").balanceDate)

        val transactions = accounts.getValue("acct-1").transactions.associateBy { it.id }
        assertEquals(1766059200L, transactions.getValue("present").transactedAt)
        assertEquals(1766145600L, transactions.getValue("present").posted)
        assertNull(transactions.getValue("absent").transactedAt)
        assertNull(transactions.getValue("zero").transactedAt)
        assertNull(transactions.getValue("negative").transactedAt)
    }

    @Test
    fun accountsAndNewProviderFieldsStillParseAlongsideAPopulatedErrlist() {
        val advisory =
            "Requested date range exceeds recommended range of 45 days. In the future, this may be capped."
        val result =
            SimpleFinClient().parseAccounts(
                """
                {
                    "errlist":["$advisory"],
                    "accounts":[{
                        "conn_id":"connection","id":"acct-1","name":"Checking","currency":"USD",
                        "balance-date":1766145600,
                        "transactions":[{"id":"kept","posted":1766145600,"amount":"-1.00",
                                         "description":"Store","transacted_at":1766059200}]
                    }]
                }
                """.trimIndent(),
            )

        assertEquals(listOf(advisory), result.errors)
        val account = result.accounts.single()
        assertEquals(1766145600L, account.balanceDate)
        assertEquals(1766059200L, account.transactions.single().transactedAt)
        assertTrue(partitionProviderErrors(result.errors).fatal.isEmpty())
        assertEquals(listOf(advisory), partitionProviderErrors(result.errors).advisory)
    }

    @Test
    fun parseAccountsEnforcesProviderAccountErrorAndStringLimits() {
        val client = SimpleFinClient()
        val account = """{"id":"${"i".repeat(16_384)}","conn_id":"connection"}"""
        assertEquals(1, client.parseAccounts("""{"accounts":[$account]}""").accounts.size)
        assertThrows(SimpleFinException::class.java) {
            client.parseAccounts("""{"accounts":[{"id":"${"i".repeat(16_385)}","conn_id":"connection"}]}""")
        }

        fun accounts(count: Int) = """{"accounts":[${List(count) { "{\"id\":\"id$it\",\"conn_id\":\"connection\"}" }.joinToString()}]}"""
        assertEquals(1_000, client.parseAccounts(accounts(1_000)).accounts.size)
        assertThrows(SimpleFinException::class.java) { client.parseAccounts(accounts(1_001)) }

        fun errors(count: Int) = """{"errors":[${List(count) { "\"error\"" }.joinToString()}]}"""
        assertEquals(100, client.parseAccounts(errors(100)).errors.size)
        assertThrows(SimpleFinException::class.java) { client.parseAccounts(errors(101)) }

        val countError = assertThrows(SimpleFinException::class.java) { client.parseErrors(errors(101)) }
        assertEquals("SimpleFIN response exceeds allowed limits", countError.message)
        val stringError =
            assertThrows(SimpleFinException::class.java) {
                client.parseErrors("""{"errors":["${"e".repeat(16_385)}"]}""")
            }
        assertEquals("SimpleFIN response exceeds allowed limits", stringError.message)
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return (0..size - candidate.size).any { start ->
            candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
        }
    }

    private companion object {
        const val ACCESS_URL = "https://user:password@bridge.simplefin.org/simplefin"
        const val OLD_ACCESS_URL = "https://old-user:old-password@bridge.simplefin.org/simplefin"
    }
}
