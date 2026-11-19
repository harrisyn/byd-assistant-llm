package com.bydassistantng.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bydassistantng.llm.LlmConfig
import com.bydassistantng.llm.LlmPreset
import com.bydassistantng.llm.LlmPresets
import com.bydassistantng.llm.LlmProvider
import com.bydassistantng.ui.AppAlertDialog
import kotlinx.coroutines.launch

private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').lowercase()

private fun hostLabel(url: String): String = when {
    "openai.com" in url -> "OpenAI"
    "groq.com" in url -> "Groq"
    "openrouter.ai" in url -> "OpenRouter"
    "minimax" in url -> "MiniMax"
    else -> hostOf(url).ifBlank { "speech" }
}

/**
 * Picks the model behind the assistant. Pick a provider and the URL, model and speech defaults are filled in;
 * what is left is the key (and a model, via Load models, if you want another). Advanced shows every field.
 */
@Composable
internal fun LlmProviderDialog(
    initial: LlmConfig,
    onSave: (LlmConfig, llmKey: String, sttKey: String, ttsKey: String) -> Unit,
    onLoadModels: suspend (base: String, key: String) -> List<String>,
    onDismiss: () -> Unit,
) {
    var gemini by remember { mutableStateOf(initial.isGemini) }
    var presetId by remember { mutableStateOf(initial.preset) }
    var cfg by remember { mutableStateOf(initial) }
    var advanced by remember { mutableStateOf(false) }
    var chatKey by remember { mutableStateOf("") }
    val hostKeys = remember { mutableStateMapOf<String, String>() }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var modelNote by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val preset: LlmPreset = LlmPresets.byId(presetId)
    val chatHost = hostOf(cfg.baseUrl)
    val speechHosts = listOf(cfg.effectiveSttBase, cfg.effectiveTtsBase)
        .map { hostOf(it) }.filter { it.isNotBlank() && it != chatHost }.distinct()

    fun choose(p: LlmPreset) {
        gemini = false
        presetId = p.id
        cfg = p.toConfig()
        models = null
        modelNote = null
    }

    @Composable
    fun field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }

    if (picking) {
        var filter by remember { mutableStateOf("") }
        AppAlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Pick a model") },
            text = {
                Column {
                    field("Filter", filter, { filter = it })
                    LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                        items((models ?: emptyList()).filter { it.contains(filter, true) }) { id ->
                            Text(
                                id,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    cfg = cfg.copy(model = id)
                                    picking = false
                                }.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI model") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { gemini = true }) { Text(if (gemini) "● Gemini" else "○ Gemini") }
                    LlmPresets.all.forEach { p ->
                        TextButton(onClick = { choose(p) }) { Text(if (!gemini && presetId == p.id) "● ${p.label}" else "○ ${p.label}") }
                    }
                }
                if (gemini) {
                    Text("Uses your Gemini API key and the Live audio mode.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                } else {
                    if (preset.note.isNotBlank()) {
                        Text(preset.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (preset.editableBase || advanced) {
                        field("Server address (…/v1)", cfg.baseUrl, { cfg = cfg.copy(baseUrl = it) })
                    }
                    if (preset.needsKey) {
                        field("${preset.label} API key (empty = keep saved)", chatKey, { chatKey = it }, secret = true)
                    }
                    speechHosts.forEach { h ->
                        val url = listOf(cfg.effectiveSttBase, cfg.effectiveTtsBase).first { hostOf(it) == h }
                        field("${hostLabel(url)} key for speech (empty = keep saved)", hostKeys[h].orEmpty(), { hostKeys[h] = it }, secret = true)
                    }
                    field("Model", cfg.model, { cfg = cfg.copy(model = it) })
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton(onClick = {
                            modelNote = "Loading…"
                            scope.launch {
                                val list = onLoadModels(cfg.baseUrl, chatKey)
                                models = list
                                modelNote = if (list.isEmpty()) "No models returned — check the address and key." else null
                                if (list.isNotEmpty()) picking = true
                            }
                        }) { Text("Load models") }
                        modelNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced") }
                    if (advanced) {
                        field("Speech-to-text address (empty = chat address)", cfg.sttBaseUrl, { cfg = cfg.copy(sttBaseUrl = it) })
                        field("Speech-to-text model", cfg.sttModel, { cfg = cfg.copy(sttModel = it) })
                        field("Voice address (empty = chat address)", cfg.ttsBaseUrl, { cfg = cfg.copy(ttsBaseUrl = it) })
                        field("Voice model (empty = system voice)", cfg.ttsModel, { cfg = cfg.copy(ttsModel = it) })
                        field("Voice name", cfg.ttsVoice, { cfg = cfg.copy(ttsVoice = it) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val out = if (gemini) cfg.copy(provider = LlmProvider.GEMINI) else cfg.copy(provider = LlmProvider.OPENAI_COMPAT, preset = presetId)
                val sttKey = hostKeys[hostOf(out.effectiveSttBase)].orEmpty()
                val ttsKey = hostKeys[hostOf(out.effectiveTtsBase)].orEmpty()
                onSave(out, if (gemini) "" else chatKey, if (gemini) "" else sttKey, if (gemini) "" else ttsKey)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
