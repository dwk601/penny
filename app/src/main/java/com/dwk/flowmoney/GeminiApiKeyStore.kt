package com.dwk.flowmoney

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class GeminiApiKeyStore(
    private val context: Context,
) {
    private val keyFile get() = File(context.noBackupFilesDir, KEY_FILE_NAME)

    fun save(apiKey: String) {
        require(apiKey.isNotBlank()) { "Gemini API key must not be blank" }
        require(apiKey.length <= MAX_API_KEY_CHARS) { "Gemini API key exceeds maximum length" }
        val payload = apiKey.toByteArray(StandardCharsets.UTF_8)
        val cipher =
            Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.ENCRYPT_MODE, key())
                updateAAD(keyFile.name.toByteArray(StandardCharsets.UTF_8))
            }
        writeSafely(keyFile, cipher.iv + cipher.doFinal(payload))
        check(read() == apiKey) { "Gemini API key save failed verification" }
    }

    fun read(): String? {
        val source = keyFile
        if (!source.isFile || source.length() !in (IV_BYTES + 1L)..MAX_ENCRYPTED_FILE_BYTES) return null
        val bytes = runCatching { source.readBytes() }.getOrNull() ?: return null
        return decrypt(bytes, source.name)
    }

    fun delete() {
        deleteFile(keyFile, "Gemini API key deletion failed")
    }

    fun hasKey(): Boolean = read() != null

    private fun decrypt(
        bytes: ByteArray,
        aadName: String,
    ): String? =
        runCatching {
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val ciphertext = bytes.copyOfRange(IV_BYTES, bytes.size)
            val cipher =
                Cipher.getInstance(TRANSFORM).apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                    updateAAD(aadName.toByteArray(StandardCharsets.UTF_8))
                }
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrNull()

    private fun writeSafely(
        target: File,
        bytes: ByteArray,
    ) {
        val temporary = File(target.parentFile, "${target.name}.new")
        check(!temporary.exists() || temporary.delete()) { "Gemini API key temporary file cleanup failed" }
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun deleteFile(
        target: File,
        failureMessage: String,
    ) {
        check(!target.exists() || target.delete()) { failureMessage }
        val temporary = File(target.parentFile, "${target.name}.new")
        check(!temporary.exists() || temporary.delete()) { failureMessage }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
    }

    internal companion object {
        const val KEY_FILE_NAME = "gemini_api_key.bin"
        private const val KEY_ALIAS = "flowmoney_gemini_api_key"
        private const val MAX_API_KEY_CHARS = 512
        private const val MAX_ENCRYPTED_FILE_BYTES = 8 * 1024L
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
