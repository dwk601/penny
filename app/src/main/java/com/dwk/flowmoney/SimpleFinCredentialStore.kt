package com.dwk.flowmoney

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
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

internal data class SimpleFinPendingCredential(
    val connectionId: String,
    val accessUrl: String,
)

class SimpleFinCredentialStore(
    private val context: Context,
) {
    private val credentialFile get() = File(context.noBackupFilesDir, CREDENTIAL_FILE_NAME)
    private val pendingFile get() = File(context.noBackupFilesDir, PENDING_FILE_NAME)

    fun save(
        connectionId: String,
        accessUrl: String,
    ) {
        writeCredential(credentialFile, ESTABLISHED_RECORD, connectionId, accessUrl)
        check(read(connectionId) == accessUrl) { "SimpleFIN credential save failed verification" }
    }

    fun read(expectedConnectionId: String): String? {
        val payload = readPayload(credentialFile, allowLegacyWithoutAad = true) ?: return null
        val parsed = parseCredential(payload)
        if (parsed != null) {
            if (parsed.recordType != null && parsed.recordType != ESTABLISHED_RECORD) return null
            return parsed.accessUrl.takeIf { parsed.connectionId == expectedConnectionId }
        }
        // URL-only payloads were written before connection identities existed.
        return payload.takeIf { expectedConnectionId == "legacy" }
    }

    fun delete() {
        deleteFile(credentialFile, "SimpleFIN credential deletion failed")
    }

    internal fun stage(
        connectionId: String,
        accessUrl: String,
    ) {
        writeCredential(pendingFile, PENDING_RECORD, connectionId, accessUrl)
        check(readPending()?.let { it.connectionId == connectionId && it.accessUrl == accessUrl } == true) {
            "SimpleFIN pending credential stage failed verification"
        }
    }

    internal fun readPending(): SimpleFinPendingCredential? {
        val payload = readPayload(pendingFile, allowLegacyWithoutAad = false) ?: return null
        val credential = parseCredential(payload)?.takeIf { it.recordType == PENDING_RECORD } ?: return null
        return SimpleFinPendingCredential(credential.connectionId, credential.accessUrl)
    }

    /** Copies the staged credential into the established slot without deleting the recovery record. */
    internal fun promotePending(expectedConnectionId: String) {
        val pending =
            readPending()?.takeIf { it.connectionId == expectedConnectionId }
                ?: error("SimpleFIN pending credential is unavailable")
        save(pending.connectionId, pending.accessUrl)
    }

    internal fun deletePending() {
        deleteFile(pendingFile, "SimpleFIN pending credential deletion failed")
    }

    private fun writeCredential(
        target: File,
        recordType: String,
        connectionId: String,
        accessUrl: String,
    ) {
        val payload =
            JSONObject()
                .put("recordType", recordType)
                .put("connectionId", connectionId)
                .put("accessUrl", accessUrl)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
        val cipher =
            Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.ENCRYPT_MODE, key())
                updateAAD(target.name.toByteArray(StandardCharsets.UTF_8))
            }
        writeSafely(target, cipher.iv + cipher.doFinal(payload))
    }

    private fun readPayload(
        source: File,
        allowLegacyWithoutAad: Boolean,
    ): String? {
        if (!source.isFile || source.length() !in (IV_BYTES + 1L)..MAX_ENCRYPTED_FILE_BYTES) return null
        val bytes = runCatching { source.readBytes() }.getOrNull() ?: return null
        decrypt(bytes, source.name)?.let { return it }
        return if (allowLegacyWithoutAad) decrypt(bytes, aadName = null) else null
    }

    private fun decrypt(
        bytes: ByteArray,
        aadName: String?,
    ): String? =
        runCatching {
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val ciphertext = bytes.copyOfRange(IV_BYTES, bytes.size)
            val cipher =
                Cipher.getInstance(TRANSFORM).apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                    aadName?.let { updateAAD(it.toByteArray(StandardCharsets.UTF_8)) }
                }
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrNull()

    private fun parseCredential(payload: String): StoredCredential? =
        runCatching {
            val json = JSONObject(payload)
            StoredCredential(
                recordType = json.optString("recordType").takeIf { it.isNotBlank() },
                connectionId = json.getString("connectionId"),
                accessUrl = json.getString("accessUrl"),
            )
        }.getOrNull()

    private data class StoredCredential(
        val recordType: String?,
        val connectionId: String,
        val accessUrl: String,
    )

    private fun writeSafely(
        target: File,
        bytes: ByteArray,
    ) {
        val temporary = File(target.parentFile, "${target.name}.new")
        check(!temporary.exists() || temporary.delete()) { "SimpleFIN credential temporary file cleanup failed" }
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
        const val CREDENTIAL_FILE_NAME = "simplefin_access_url.bin"
        const val PENDING_FILE_NAME = "simplefin_pending_access_url.bin"
        private const val ESTABLISHED_RECORD = "established"
        private const val PENDING_RECORD = "pending"
        private const val KEY_ALIAS = "flowmoney_simplefin_access_url"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val MAX_ENCRYPTED_FILE_BYTES = 64 * 1024L
    }
}
