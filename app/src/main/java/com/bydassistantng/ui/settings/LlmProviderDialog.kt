package com.bydassistantng.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bydassistantng.llm.LlmConfig
import com.bydassistantng.llm.LlmProvider
import com.bydassistantng.ui.AppAlertDialog

/** Picks the model behind the assistant: Gemini as before, or any OpenAI-compatible endpoint (cloud or self-hosted). */
@Composable
internal fun LlmProviderDialog(
    initial: LlmConfig,
    onSave: (LlmConfig, llmKey: String, sttKey: String, ttsKey: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var provider by remember { mutableStateOf(initial.provider) }
    var base by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var sttBase by remember { mutableStateOf(initial.sttBaseUrl) }
    var sttModel by remember { mutableStateOf(initial.sttModel) }
    var ttsBase by remember { mutableStateOf(initial.ttsBaseUrl) }
    var ttsModel by remember { mutableStateOf(initial.ttsModel) }
    var ttsVoice by remember { mutableStateOf(initial.ttsVoice) }
    var llmKey by remember { mutableStateOf("") }
    var sttKey by remember { mutableStateOf("") }
    var ttsKey by remember { mutableStateOf("") }

    @Composable
    fun field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI model") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { provider = LlmProvider.GEMINI }) {
                        Text(if (provider == LlmProvider.GEMINI) "● Gemini (Live audio)" else "○ Gemini (Live audio)")
                    }
                    TextButton(onClick = { provider = LlmProvider.OPENAI_COMPAT }) {
                        Text(if (provider == LlmProvider.OPENAI_COMPAT) "● OpenAI-compatible" else "○ OpenAI-compatible")
                    }
                }
                if (provider == LlmProvider.OPENAI_COMPAT) {
                    Text(
                        "Works with OpenAI, MiniMax, Groq, OpenRouter, LiteLLM, or your own Ollama / llama.cpp / vLLM server. " +
                            "Leave a key empty for servers that need none. Speech-to-text and voice reuse the chat endpoint and key unless you override them.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    field("Chat base URL (…/v1)", base, { base = it })
                    field("Chat model", model, { model = it })
                    field("Chat API key (empty = keep saved)", llmKey, { llmKey = it }, secret = true)
                    field("Speech-to-text base URL (optional)", sttBase, { sttBase = it })
                    field("Speech-to-text model", sttModel, { sttModel = it })
                    field("Speech-to-text key (optional)", sttKey, { sttKey = it }, secret = true)
                    field("Voice base URL (optional)", ttsBase, { ttsBase = it })
                    field("Voice model (empty = system voice)", ttsModel, { ttsModel = it })
                    field("Voice name", ttsVoice, { ttsVoice = it })
                    field("Voice key (optional)", ttsKey, { ttsKey = it }, secret = true)
                } else {
                    Text("Uses your Gemini API key and the Live audio mode.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    LlmConfig(provider, base, model, sttBase, sttModel, ttsBase, ttsModel, ttsVoice),
                    llmKey, sttKey, ttsKey,
                )
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
