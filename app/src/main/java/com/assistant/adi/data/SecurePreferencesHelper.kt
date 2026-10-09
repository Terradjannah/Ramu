package com.assistant.adi.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePreferencesHelper(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        "secure_ai_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init {
        migrateOldToken()
    }

    private fun migrateOldToken() {
        // Retrieve old token from plaintext prefs
        val oldPrefs = context.getSharedPreferences("ai_settings_prefs", Context.MODE_PRIVATE)
        val oldToken = oldPrefs.getString("hf_token", null)
        
        if (!oldToken.isNullOrEmpty()) {
            // Save securely
            saveHfToken(oldToken)
            // Remove from plaintext prefs
            oldPrefs.edit().remove("hf_token").apply()
        }
    }

    fun saveHfToken(token: String) {
        securePrefs.edit().putString("secure_hf_token", token).apply()
    }

    fun getHfToken(): String? {
        return securePrefs.getString("secure_hf_token", null)
    }

    fun clearHfToken() {
        securePrefs.edit().remove("secure_hf_token").apply()
    }
}
