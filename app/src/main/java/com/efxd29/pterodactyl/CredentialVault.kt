package com.efxd29.pterodactyl

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedPanelLogin(val username: String, val password: String)

/**
 * Credentials are encrypted using an AES-GCM key protected by Android Keystore.
 * The plaintext is held only while the app is running or briefly during login submission.
 */
object CredentialVault {
    private const val KEY_ALIAS = "pterodactyl_panel_login_v1"
    private const val PREFS = "pterodactyl_encrypted_login"
    private const val IV_KEY = "iv"
    private const val DATA_KEY = "encrypted_payload"
    private const val AUTO_LOGIN_KEY = "last_auto_login_attempt"

    fun save(context: Context, username: String, password: String) {
        if (username.isBlank() || password.isBlank()) return
        val payload = JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(payload)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(DATA_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context): SavedPanelLogin? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ivText = prefs.getString(IV_KEY, null) ?: return null
        val encryptedText = prefs.getString(DATA_KEY, null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            val clear = cipher.doFinal(Base64.decode(encryptedText, Base64.NO_WRAP))
            val json = JSONObject(String(clear, StandardCharsets.UTF_8))
            val username = json.optString("username")
            val password = json.optString("password")
            if (username.isBlank() || password.isBlank()) null else SavedPanelLogin(username, password)
        } catch (_: Exception) {
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(AUTO_LOGIN_KEY).apply()
    }

    fun canAttemptAutoLogin(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(AUTO_LOGIN_KEY, 0L)
        // Avoid repeatedly submitting invalid credentials or triggering server-side rate limits.
        return now - last >= AUTO_LOGIN_THROTTLE_MS
    }

    fun markAutoLoginAttempt(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(AUTO_LOGIN_KEY, now)
            .apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private const val AUTO_LOGIN_THROTTLE_MS = 60_000L
}
