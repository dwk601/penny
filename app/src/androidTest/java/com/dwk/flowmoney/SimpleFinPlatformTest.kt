package com.dwk.flowmoney

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimpleFinPlatformTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun applicationDisallowsBackup() {
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun keystoreCredentialPersistsAcrossStoreRecreationAndRejectsWrongIdentityAndCorruption() {
        val file = File(context.noBackupFilesDir, "simplefin_access_url.bin")
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
        val file = File(context.noBackupFilesDir, "simplefin_access_url.bin")
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
    fun parsesBridgeV2StructuredErrorsWithoutDiscardingAccounts() {
        val result = SimpleFinClient().parseAccounts(
            """{
                "errors":[],
                "errlist":[{"type":"act.failed","account":"broken"},{"type":"con.auth"}],
                "accounts":[{
                    "conn_id":"provider-connection","id":"working","name":"Working","currency":"USD",
                    "transactions":[{"id":"tx","posted":1700000000,"amount":"-1.00","description":"Shop"}]
                }]
            }""".trimIndent(),
        )

        assertEquals(1, result.accounts.size)
        assertEquals("provider-connection", result.accounts.single().providerConnectionId)
        assertEquals("tx", result.accounts.single().transactions.single().id)
        assertEquals(2, result.errors.size)
        assertTrue(result.errors.joinToString().contains("act.failed"))
        assertTrue(result.errors.joinToString().contains("con.auth"))
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
        val stringError = assertThrows(SimpleFinException::class.java) {
            client.parseErrors("""{"errors":["${"e".repeat(16_385)}"]}""")
        }
        assertEquals("SimpleFIN response exceeds allowed limits", stringError.message)
    }

    private companion object {
        const val ACCESS_URL = "https://user:password@bridge.simplefin.org/simplefin"
    }
}
