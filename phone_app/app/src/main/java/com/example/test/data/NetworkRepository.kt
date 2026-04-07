package com.example.test.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.networkDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "network_settings")

class NetworkRepository(private val context: Context) {

    private val gson = Gson()

    companion object {
        private val KEY_CONFIGS = stringPreferencesKey("api_configs")
        private val KEY_GLOBAL_PROMPT = stringPreferencesKey("global_prompt")

        const val DEFAULT_PROMPT =
            "你是一个专业的药物识别助手。用户将向你提供药物图片，请识别图片中的药物并给出：\n" +
            "1. 药物名称（通用名和商品名）\n" +
            "2. 主要功效和适应症\n" +
            "3. 重要注意事项和禁忌\n" +
            "4. 常见副作用\n" +
            "请用简洁、清晰的中文回答，如有安全风险请明显标注。"
    }

    val configsFlow: Flow<List<ApiConfig>> = context.networkDataStore.data.map { prefs ->
        val json = prefs[KEY_CONFIGS] ?: return@map emptyList()
        try {
            val type = object : TypeToken<List<ApiConfig>>() {}.type
            gson.fromJson<List<ApiConfig>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    val globalPromptFlow: Flow<String> = context.networkDataStore.data.map { prefs ->
        prefs[KEY_GLOBAL_PROMPT] ?: DEFAULT_PROMPT
    }

    suspend fun saveConfigs(configs: List<ApiConfig>) {
        context.networkDataStore.edit { prefs ->
            prefs[KEY_CONFIGS] = gson.toJson(configs)
        }
    }

    suspend fun saveGlobalPrompt(prompt: String) {
        context.networkDataStore.edit { prefs ->
            prefs[KEY_GLOBAL_PROMPT] = prompt
        }
    }
}
