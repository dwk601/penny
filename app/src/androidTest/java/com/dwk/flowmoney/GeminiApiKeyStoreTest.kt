package com.dwk.flowmoney

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GeminiApiKeyStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val keyFile = File(context.noBackupFilesDir, GeminiApiKeyStore.KEY_FILE_NAME)
    private val simpleFinFiles =
        listOf(
            SimpleFinCredentialStore.CREDENTIAL_FILE_NAME,
            SimpleFinCredentialStore.PENDING_FILE_NAME,
            SimpleFinCredentialStore.ROLLBACK_FILE_NAME,
        ).map { File(context.noBackupFilesDir, it) }

    @Before fun clearKeyFile() {
        GeminiApiKeyStore(context).delete()
    }

    @After fun removeKeyFile() {
        GeminiApiKeyStore(context).delete()
    }

    @Test
    fun saveReadDeleteRoundTripsAcrossStoreInstances() {
        val store = GeminiApiKeyStore(context)
        assertNull(store.read())
        assertFalse(store.hasKey())

        store.save(API_KEY)

        val recreated = GeminiApiKeyStore(context)
        assertEquals(API_KEY, recreated.read())
        assertTrue(recreated.hasKey())

        recreated.save(ROTATED_API_KEY)
        assertEquals(ROTATED_API_KEY, GeminiApiKeyStore(context).read())

        recreated.delete()
        assertNull(GeminiApiKeyStore(context).read())
        assertFalse(GeminiApiKeyStore(context).hasKey())
    }

    @Test
    fun theKeyLivesInAnEncryptedNoBackupFileWithAStableName() {
        GeminiApiKeyStore(context).save(API_KEY)

        assertEquals("gemini_api_key.bin", GeminiApiKeyStore.KEY_FILE_NAME)
        assertTrue(keyFile.isFile)
        assertEquals(context.noBackupFilesDir, keyFile.parentFile)
        // The plaintext key is never on disk, and the temporary write file is cleaned up.
        val bytes = keyFile.readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(API_KEY))
        assertFalse(File(context.noBackupFilesDir, "${GeminiApiKeyStore.KEY_FILE_NAME}.new").exists())

        GeminiApiKeyStore(context).delete()
        assertFalse(keyFile.exists())
    }

    @Test
    fun savingTheKeyNeverTouchesSimpleFinCredentialFiles() {
        val before = simpleFinFiles.map { it.exists() to it.length() }

        GeminiApiKeyStore(context).save(API_KEY)

        assertEquals(before, simpleFinFiles.map { it.exists() to it.length() })
        assertTrue(simpleFinFiles.none { it.name == GeminiApiKeyStore.KEY_FILE_NAME })

        GeminiApiKeyStore(context).delete()
        assertEquals(before, simpleFinFiles.map { it.exists() to it.length() })
    }

    @Test
    fun readReturnsNullAfterDeletionAndAfterCorruption() {
        val store = GeminiApiKeyStore(context)
        store.save(API_KEY)

        store.delete()
        assertNull(store.read())

        store.save(API_KEY)
        keyFile.writeBytes(keyFile.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        assertNull(store.read())
        assertFalse(store.hasKey())

        keyFile.writeBytes(ByteArray(0))
        assertNull(store.read())

        keyFile.writeBytes(ByteArray(4) { 1 })
        assertNull(store.read())
    }

    @Test
    fun blankAndOversizedKeysAreRejectedWithoutWritingAFile() {
        val store = GeminiApiKeyStore(context)

        assertThrows(IllegalArgumentException::class.java) { store.save("   ") }
        assertThrows(IllegalArgumentException::class.java) { store.save("a".repeat(513)) }

        assertFalse(keyFile.exists())
        assertNull(store.read())
    }

    private companion object {
        const val API_KEY = "test-gemini-api-key-fixture"
        const val ROTATED_API_KEY = "rotated-gemini-api-key-fixture"
    }
}
