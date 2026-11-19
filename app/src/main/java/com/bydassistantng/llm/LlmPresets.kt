package com.bydassistantng.llm

/**
 * Ready-made settings for the common OpenAI-compatible providers, so setup is "pick one, paste a key".
 * Model names change often: [LlmPreset.model] is only a starting point, and the settings dialog can fetch the
 * provider's live model list (`GET {base}/models`) to pick from instead of typing.
 *
 * Speech: a provider with no OpenAI-style speech endpoints (OpenRouter, MiniMax, Ollama) borrows OpenAI's, which
 * needs a second key. [LlmPreset.stt*] / [LlmPreset.tts*] are blank when the chat provider's own endpoints are used.
 */
data class LlmPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val needsKey: Boolean = true,
    val editableBase: Boolean = false,
    val sttBaseUrl: String = "",
    val sttModel: String = "whisper-1",
    val ttsBaseUrl: String = "",
    val ttsModel: String = "tts-1",
    val ttsVoice: String = "alloy",
    val note: String = "",
) {
    /** The config this preset stands for (keys are stored separately). */
    fun toConfig(): LlmConfig = LlmConfig(
        provider = LlmProvider.OPENAI_COMPAT,
        preset = id,
        baseUrl = baseUrl,
        model = model,
        sttBaseUrl = sttBaseUrl,
        sttModel = sttModel,
        ttsBaseUrl = ttsBaseUrl,
        ttsModel = ttsModel,
        ttsVoice = ttsVoice,
    )
}

object LlmPresets {
    private const val OPENAI = "https://api.openai.com/v1"
    private const val GROQ = "https://api.groq.com/openai/v1"

    val all: List<LlmPreset> = listOf(
        LlmPreset(
            id = "openai", label = "OpenAI",
            baseUrl = OPENAI, model = "gpt-4o-mini",
            note = "One key covers chat, speech-to-text and voice.",
        ),
        LlmPreset(
            id = "openrouter", label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1", model = "openrouter/auto",
            sttBaseUrl = OPENAI, ttsBaseUrl = OPENAI,
            note = "One key for hundreds of models, including Claude and Llama. It has no speech: speech-to-text and voice use OpenAI (second key) unless you change them under Advanced.",
        ),
        LlmPreset(
            id = "groq", label = "Groq",
            baseUrl = GROQ, model = "llama-3.3-70b-versatile",
            sttBaseUrl = GROQ, sttModel = "whisper-large-v3-turbo",
            ttsBaseUrl = OPENAI,
            note = "Fast chat and speech-to-text on one key. Voice uses OpenAI (second key).",
        ),
        LlmPreset(
            id = "minimax", label = "MiniMax",
            baseUrl = "https://api.minimax.io/v1", model = "MiniMax-M2",
            sttBaseUrl = OPENAI, ttsBaseUrl = OPENAI,
            note = "Chat only. If the model name is rejected, tap Load models. Speech uses OpenAI (second key). China accounts: use https://api.minimaxi.com/v1 under Advanced.",
        ),
        LlmPreset(
            id = "ollama", label = "Ollama / llama.cpp",
            baseUrl = "http://192.168.1.10:11434/v1", model = "", needsKey = false, editableBase = true,
            sttBaseUrl = OPENAI, ttsBaseUrl = OPENAI,
            note = "Your own server on the network: set its address, then Load models. Needs a model with tool support. Speech uses OpenAI unless you point it at a local Whisper server under Advanced.",
        ),
        LlmPreset(
            id = "custom", label = "Custom",
            baseUrl = "", model = "", editableBase = true,
            note = "Any OpenAI-compatible endpoint.",
        ),
    )

    fun byId(id: String): LlmPreset = all.firstOrNull { it.id == id } ?: all.last()
}
