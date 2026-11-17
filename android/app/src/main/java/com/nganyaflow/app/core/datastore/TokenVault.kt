package com.nganyaflow.app.core.datastore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nganyaflow.app.core.model.AuthSession
import com.nganyaflow.app.core.network.WireJson
import kotlinx.serialization.encodeToString
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The database and backups never contain bearer-token plaintext. */
class TokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("encrypted-access", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("nganyaflow-access", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("nganyaflow-access", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun storageKey(server: String) = MessageDigest.getInstance("SHA-256").digest(server.toByteArray()).joinToString("") { "%02x".format(it) }
    @Synchronized fun write(server: String, auth: AuthSession?) {
        val id = storageKey(server)
        if (auth == null) { check(prefs.edit().remove(id).commit()); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(server.toByteArray()) }
        val encrypted = cipher.doFinal(WireJson.encodeToString(auth).toByteArray())
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString(id, value).commit())
    }
    @Synchronized fun read(server: String): AuthSession? = runCatching {
        val parts = prefs.getString(storageKey(server), null)?.split('.') ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            updateAAD(server.toByteArray())
        }
        WireJson.decodeFromString<AuthSession>(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8))
    }.getOrNull()
}
