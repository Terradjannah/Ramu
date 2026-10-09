package com.assistant.adi.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.assistant.adi.data.model.AiSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_settings")

class AiSettingsDataStore(private val context: Context) {

    companion object {
        private val KEY_TEMPERATURE = floatPreferencesKey("temperature")
        private val KEY_TOP_K = intPreferencesKey("top_k")
        private val KEY_TOP_P = floatPreferencesKey("top_p")
        private val KEY_REPEAT_PENALTY = floatPreferencesKey("repeat_penalty")
        private val KEY_MAX_TOKENS = intPreferencesKey("max_tokens")
        private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        private val KEY_USER_PROMPT_TEMPLATE = stringPreferencesKey("user_prompt_template")
        private val KEY_ACCELERATOR = stringPreferencesKey("accelerator")
        private val KEY_ENABLE_THINKING = booleanPreferencesKey("enable_thinking")
        private val KEY_ACTIVE_MODEL_PATH = stringPreferencesKey("active_model_path")
        private val KEY_ACTIVE_VARIANT_IDENTITY = stringPreferencesKey("active_variant_identity")
        private val KEY_IDLE_TIMEOUT_MINUTES = intPreferencesKey("idle_timeout_minutes")
    }

    val aiSettingsFlow: Flow<AiSettings> = context.dataStore.data.map { preferences ->
        AiSettings(
            temperature = preferences[KEY_TEMPERATURE] ?: 0.3f,
            topK = preferences[KEY_TOP_K] ?: 40,
            topP = preferences[KEY_TOP_P] ?: 0.9f,
            repeatPenalty = preferences[KEY_REPEAT_PENALTY] ?: 1.1f,
            maxTokens = preferences[KEY_MAX_TOKENS] ?: 1024,
            systemPrompt = preferences[KEY_SYSTEM_PROMPT]?.let { savedPrompt ->
                val oldDefault = AiSettings.DEFAULT_SYSTEM_PROMPT.replace("personal Ramu", "personal Pocket Buddy")
                if (savedPrompt == oldDefault) AiSettings.DEFAULT_SYSTEM_PROMPT else savedPrompt
            } ?: AiSettings.DEFAULT_SYSTEM_PROMPT,
            userPromptTemplate = preferences[KEY_USER_PROMPT_TEMPLATE] ?: AiSettings.DEFAULT_USER_PROMPT_TEMPLATE,
            accelerator = preferences[KEY_ACCELERATOR] ?: "AUTO",
            enableThinking = preferences[KEY_ENABLE_THINKING] ?: false,
            activeModelPath = preferences[KEY_ACTIVE_MODEL_PATH] ?: "",
            activeVariantIdentity = preferences[KEY_ACTIVE_VARIANT_IDENTITY] ?: "",
            idleTimeoutMinutes = preferences[KEY_IDLE_TIMEOUT_MINUTES] ?: 3
        )
    }

    suspend fun saveSettings(settings: AiSettings) {
        context.dataStore.edit { preferences ->
            preferences[KEY_TEMPERATURE] = settings.temperature
            preferences[KEY_TOP_K] = settings.topK
            preferences[KEY_TOP_P] = settings.topP
            preferences[KEY_MAX_TOKENS] = settings.maxTokens
            preferences[KEY_SYSTEM_PROMPT] = settings.systemPrompt
            preferences[KEY_USER_PROMPT_TEMPLATE] = settings.userPromptTemplate
            preferences[KEY_ACCELERATOR] = settings.accelerator
            preferences[KEY_ACTIVE_MODEL_PATH] = settings.activeModelPath
            preferences[KEY_ACTIVE_VARIANT_IDENTITY] = settings.activeVariantIdentity
            preferences[KEY_IDLE_TIMEOUT_MINUTES] = settings.idleTimeoutMinutes
        }
    }

    suspend fun setActiveModelPath(modelPath: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ACTIVE_MODEL_PATH] = modelPath
            preferences[KEY_ACTIVE_VARIANT_IDENTITY] = ""
        }
    }

    suspend fun setActiveModel(modelPath: String, identity: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ACTIVE_MODEL_PATH] = modelPath
            preferences[KEY_ACTIVE_VARIANT_IDENTITY] = identity
        }
    }

    suspend fun migrateActiveVariant(modelPath: String, identity: String) {
        context.dataStore.edit { preferences ->
            if (preferences[KEY_ACTIVE_MODEL_PATH] == modelPath && preferences[KEY_ACTIVE_VARIANT_IDENTITY].isNullOrBlank()) {
                preferences[KEY_ACTIVE_VARIANT_IDENTITY] = identity
            }
        }
    }

    suspend fun resetToDefaults() {
        context.dataStore.edit { preferences ->
            preferences.remove(KEY_TEMPERATURE)
            preferences.remove(KEY_TOP_K)
            preferences.remove(KEY_TOP_P)
            preferences.remove(KEY_MAX_TOKENS)
            preferences.remove(KEY_SYSTEM_PROMPT)
            preferences.remove(KEY_USER_PROMPT_TEMPLATE)
            preferences.remove(KEY_ACCELERATOR)
            preferences.remove(KEY_IDLE_TIMEOUT_MINUTES)
        }
    }
}
