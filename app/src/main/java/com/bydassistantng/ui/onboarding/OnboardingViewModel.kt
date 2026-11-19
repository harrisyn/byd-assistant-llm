package com.bydassistantng.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydassistantng.R
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.data.SecureCredentials
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLanguageChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureCredentials: SecureCredentials,
    private val preferencesRepository: PreferencesRepository,
    private val llmConfigRepository: com.bydassistantng.llm.LlmConfigRepository,
) : ViewModel() {
    private val _appLanguage = MutableStateFlow(AppLanguage.choice(context))
    val appLanguage: StateFlow<AppLanguageChoice> = _appLanguage.asStateFlow()

    fun setAppLanguage(choice: AppLanguageChoice) {
        AppLanguage.setChoice(context, choice)
        _appLanguage.value = choice
        _errorMessage.value = null
    }

    private val _language = MutableStateFlow(AssistantLanguage.AUTO)
    val language: StateFlow<AssistantLanguage> = _language.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun setLanguage(language: AssistantLanguage) {
        _language.value = language
        viewModelScope.launch { preferencesRepository.setAssistantLanguage(language) }
    }

    /** The user has been sent to the head unit's auto-start screen (the app cannot see what they did there). */
    fun markAutoStartVisited() {
        viewModelScope.launch { preferencesRepository.markAutoStartVisited() }
    }

    val llmConfig: StateFlow<com.bydassistantng.llm.LlmConfig> = llmConfigRepository.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.bydassistantng.llm.LlmConfig())

    suspend fun loadModels(base: String, key: String): List<String> = llmConfigRepository.listModels(base, key)

    private val _configured = MutableStateFlow(false)

    /** Whether an AI model is chosen and has the key it needs. */
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    init {
        viewModelScope.launch { _configured.value = secureCredentials.hasApiKey() }
    }

    fun saveLlm(config: com.bydassistantng.llm.LlmConfig, llmKey: String, sttKey: String, ttsKey: String) {
        viewModelScope.launch {
            llmConfigRepository.save(config)
            if (config.isGemini) {
                if (llmKey.isNotBlank()) secureCredentials.setApiKey(llmKey.trim())
            } else {
                if (llmKey.isNotBlank()) llmConfigRepository.setLlmKey(llmKey)
                if (sttKey.isNotBlank()) llmConfigRepository.setSttKey(sttKey)
                if (ttsKey.isNotBlank()) llmConfigRepository.setTtsKey(ttsKey)
            }
            _configured.value = secureCredentials.hasApiKey()
        }
    }

    fun finish(onFinished: () -> Unit) {
        viewModelScope.launch {
            if (!secureCredentials.hasApiKey()) {
                _errorMessage.value = AppLanguage.string(context, R.string.onb_enter_key)
                return@launch
            }
            preferencesRepository.setOnboardingCompleted(true)
            onFinished()
        }
    }
}
