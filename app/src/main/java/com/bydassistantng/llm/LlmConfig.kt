package com.bydassistantng.llm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bydassistantng.data.SecureCredentials
import com.bydassistantng.data.dataStore
import com.bydassistantng.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Which kind of model backs the assistant. GEMINI is the original behaviour (Live audio + Gemini key). */
enum class LlmProvider { GEMINI, OPENAI_COMPAT }

/**
 * Everything needed to talk to an OpenAI-compatible stack: chat model, speech-to-text and text-to-speech.
 * Works with OpenAI, MiniMax, Groq, OpenRouter, LiteLLM, and self-hosted Ollama / llama.cpp / vLLM / Whisper servers.
 * Base URLs end at the API root, e.g. `https://api.openai.com/v1` or `http://192.168.1.10:11434/v1`.
 */
data class LlmConfig(
    val provider: LlmProvider = LlmProvider.GEMINI,
    val preset: String = "openai",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val sttBaseUrl: String = "",
    val sttModel: String = "whisper-1",
    val ttsBaseUrl: String = "",
    val ttsModel: String = "tts-1",
    val ttsVoice: String = "alloy",
) {
    val isGemini: Boolean get() = provider == LlmProvider.GEMINI
    val effectiveSttBase: String get() = sttBaseUrl.ifBlank { baseUrl }.trimEnd('/')
    val effectiveTtsBase: String get() = ttsBaseUrl.ifBlank { baseUrl }.trimEnd('/')
    /** Spoken replies need a TTS endpoint; blank model means "use the platform speech engine instead". */
    val cloudTtsEnabled: Boolean get() = !isGemini && ttsModel.isNotBlank()
}

private const val FILE_NAME = "llm-config.json"

@Singleton
class LlmConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecureCredentials,
    private val prefs: com.bydassistantng.data.PreferencesRepository,
    private val client: OpenAiCompatClient,
) {
    private val dataStore = context.dataStore

    private object K {
        val PROVIDER = stringPreferencesKey("llm_provider")
        val PRESET = stringPreferencesKey("llm_preset")
        val BASE = stringPreferencesKey("llm_base_url")
        val MODEL = stringPreferencesKey("llm_model")
        val STT_BASE = stringPreferencesKey("llm_stt_base_url")
        val STT_MODEL = stringPreferencesKey("llm_stt_model")
        val TTS_BASE = stringPreferencesKey("llm_tts_base_url")
        val TTS_MODEL = stringPreferencesKey("llm_tts_model")
        val TTS_VOICE = stringPreferencesKey("llm_tts_voice")
    }

    val config: Flow<LlmConfig> = dataStore.data.map { p ->
        val d = LlmConfig()
        LlmConfig(
            provider = p[K.PROVIDER]?.let { runCatching { LlmProvider.valueOf(it) }.getOrNull() } ?: d.provider,
            preset = p[K.PRESET] ?: d.preset,
            baseUrl = p[K.BASE] ?: d.baseUrl,
            model = p[K.MODEL] ?: d.model,
            sttBaseUrl = p[K.STT_BASE] ?: d.sttBaseUrl,
            sttModel = p[K.STT_MODEL] ?: d.sttModel,
            ttsBaseUrl = p[K.TTS_BASE] ?: d.ttsBaseUrl,
            ttsModel = p[K.TTS_MODEL] ?: d.ttsModel,
            ttsVoice = p[K.TTS_VOICE] ?: d.ttsVoice,
        )
    }

    suspend fun current(): LlmConfig = config.first()

    suspend fun save(c: LlmConfig) {
        dataStore.edit {
            it[K.PROVIDER] = c.provider.name
            it[K.PRESET] = c.preset
            it[K.BASE] = c.baseUrl.trim()
            it[K.MODEL] = c.model.trim()
            it[K.STT_BASE] = c.sttBaseUrl.trim()
            it[K.STT_MODEL] = c.sttModel.trim()
            it[K.TTS_BASE] = c.ttsBaseUrl.trim()
            it[K.TTS_MODEL] = c.ttsModel.trim()
            it[K.TTS_VOICE] = c.ttsVoice.trim()
        }
    }

    /** Model ids the provider offers (`GET {base}/models`); empty when unreachable or the key is rejected. */
    suspend fun listModels(base: String, key: String): List<String> =
        runCatching { client.listModels(base.trim().trimEnd('/'), key.ifBlank { llmKey() }) }.getOrDefault(emptyList())

    suspend fun llmKey(): String? = secrets.getSecret("llm")
    /** STT and TTS fall back to the chat key when they have none of their own. */
    suspend fun sttKey(): String? = secrets.getSecret("stt")?.takeIf { it.isNotBlank() } ?: llmKey()
    suspend fun ttsKey(): String? = secrets.getSecret("tts")?.takeIf { it.isNotBlank() } ?: llmKey()
    suspend fun setLlmKey(v: String) = secrets.setSecret("llm", v.trim())
    suspend fun setSttKey(v: String) = secrets.setSecret("stt", v.trim())
    suspend fun setTtsKey(v: String) = secrets.setSecret("tts", v.trim())

    /**
     * Headless setup: `adb push llm-config.json /sdcard/Android/data/<package>/files/` and the next start of the
     * app (or the next voice turn) imports it, then deletes the file so the key does not linger on storage.
     * Typing a base URL and key on a car screen is painful; this is the one-step alternative.
     */
    suspend fun importFileIfPresent() {
        val file = File(context.getExternalFilesDir(null), FILE_NAME)
        if (!file.isFile) return
        try {
            val o: JsonObject = Json.parseToJsonElement(file.readText()).jsonObject
            fun s(k: String): String? = o[k]?.jsonPrimitive?.content
            val d = current()
            save(
                LlmConfig(
                    provider = s("provider")?.let { runCatching { LlmProvider.valueOf(it.uppercase()) }.getOrNull() } ?: LlmProvider.OPENAI_COMPAT,
                    preset = s("preset") ?: "custom",
                    baseUrl = s("baseUrl") ?: d.baseUrl,
                    model = s("model") ?: d.model,
                    sttBaseUrl = s("sttBaseUrl") ?: d.sttBaseUrl,
                    sttModel = s("sttModel") ?: d.sttModel,
                    ttsBaseUrl = s("ttsBaseUrl") ?: d.ttsBaseUrl,
                    ttsModel = s("ttsModel") ?: d.ttsModel,
                    ttsVoice = s("ttsVoice") ?: d.ttsVoice,
                ),
            )
            s("apiKey")?.let { setLlmKey(it) }
            s("sttApiKey")?.let { setSttKey(it) }
            s("ttsApiKey")?.let { setTtsKey(it) }
            if (s("skipOnboarding") == "true") prefs.setOnboardingCompleted(true)
            s("language")?.let { l -> com.bydassistantng.data.AssistantLanguage.entries.find { it.name.equals(l, true) }?.let { prefs.setAssistantLanguage(it) } }
            AppLogger.log("LlmConfig", "Imported $FILE_NAME")
            file.delete()
        } catch (e: Exception) {
            AppLogger.logError("LlmConfig", "Could not import $FILE_NAME", e)
        }
    }
}
