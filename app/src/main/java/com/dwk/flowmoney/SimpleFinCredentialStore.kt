package com.dwk.flowmoney

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SimpleFinCredentialStore(private val context: Context) {
    private val file get() = File(context.noBackupFilesDir, "simplefin_access_url.bin")

    fun save(connectionId: String, accessUrl: String) {
        val payload = JSONObject()
            .put("connectionId", connectionId)
            .put("accessUrl", accessUrl)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        file.writeBytes(cipher.iv + cipher.doFinal(payload.toByteArray()))
        check(read(connectionId) == accessUrl) { "SimpleFIN credential save failed verification" }
    }

    fun read(expectedConnectionId: String): String? {
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size <= IV_BYTES) return null
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val ciphertext = bytes.copyOfRange(IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            val payload = String(cipher.doFinal(ciphertext))
            val credential = runCatching {
                val json = JSONObject(payload)
                json.getString("connectionId") to json.getString("accessUrl")
            }.getOrElse {
                // URL-only payloads were written before connection identities existed.
                "legacy" to payload
            }
            credential.second.takeIf { credential.first == expectedConnectionId }
        }.getOrNull()
    }

    fun delete() {
        check(!file.exists() || file.delete()) { "SimpleFIN credential deletion failed" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "flowmoney_simplefin_access_url"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
