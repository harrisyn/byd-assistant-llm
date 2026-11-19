package com.bydassistantng.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bydassistantng.llm.LlmConfig
import com.bydassistantng.llm.LlmPreset
import com.bydassistantng.llm.LlmPresets
import com.bydassistantng.llm.LlmProvider
import com.bydassistantng.ui.AiStudioKeyLink
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

/** What Save hands back: the config plus whichever keys were typed (empty = keep what is saved). For Gemini, [llmKey] is the Gemini key. */
internal typealias SaveLlm = (config: LlmConfig, llmKey: String, sttKey: String, ttsKey: String) -> Unit

/**
 * The AI model chooser: Gemini, OpenAI, OpenRouter, Groq, MiniMax, Ollama or Custom, all equal choices. Pick one and the
 * address, model and speech defaults are filled in; what is left is the key (and a model, via Load models, if you want
 * another). Advanced shows every field. Used inline by the setup wizard and inside a dialog by Settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LlmProviderForm(
    initial: LlmConfig,
    geminiKeySaved: Boolean,
    saveLabel: String,
    onSave: SaveLlm,
    onLoadModels: suspend (base: String, key: String) -> List<String>,
    modifier: Modifier = Modifier,
    extraActions: @Composable () -> Unit = {},
) {
    var gemini by remember { mutableStateOf(initial.isGemini) }
    var presetId by remember { mutableStateOf(initial.preset) }
    var cfg by remember { mutableStateOf(initial) }
    var advanced by remember { mutableStateOf(false) }
    var chatKey by remember { mutableStateOf("") }
    var geminiKey by remember { mutableStateOf("") }
    val hostKeys = remember { mutableStateMapOf<String, String>() }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var modelNote by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var savedNote by remember { mutableStateOf(false) }
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
        savedNote = false
    }

    @Composable
    fun field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it); savedNote = false },
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

    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = gemini, onClick = { gemini = true; savedNote = false }, label = { Text("Gemini") })
            LlmPresets.all.forEach { p ->
                FilterChip(selected = !gemini && presetId == p.id, onClick = { choose(p) }, label = { Text(p.label) })
            }
        }

        if (gemini) {
            Text(
                "Google's Gemini, with Live audio: the fastest, most natural voice. Needs a key from Google AI Studio.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp),
            )
            field(if (geminiKeySaved) "Gemini API key (empty = keep saved)" else "Gemini API key", geminiKey, { geminiKey = it }, secret = true)
            AiStudioKeyLink(modifier = Modifier.padding(top = 8.dp))
        } else {
            if (preset.note.isNotBlank()) {
                Text(preset.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
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
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced") }
            }
            if (advanced) {
                field("Speech-to-text address (empty = chat address)", cfg.sttBaseUrl, { cfg = cfg.copy(sttBaseUrl = it) })
                field("Speech-to-text model", cfg.sttModel, { cfg = cfg.copy(sttModel = it) })
                field("Voice address (empty = chat address)", cfg.ttsBaseUrl, { cfg = cfg.copy(ttsBaseUrl = it) })
                field("Voice model (empty = system voice)", cfg.ttsModel, { cfg = cfg.copy(ttsModel = it) })
                field("Voice name", cfg.ttsVoice, { cfg = cfg.copy(ttsVoice = it) })
            }
        }

        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                val out = if (gemini) cfg.copy(provider = LlmProvider.GEMINI) else cfg.copy(provider = LlmProvider.OPENAI_COMPAT, preset = presetId)
                val sttKey = hostKeys[hostOf(out.effectiveSttBase)].orEmpty()
                val ttsKey = hostKeys[hostOf(out.effectiveTtsBase)].orEmpty()
                if (gemini) onSave(out, geminiKey, "", "") else onSave(out, chatKey, sttKey, ttsKey)
                savedNote = true
            }) { Text(saveLabel) }
            if (savedNote) Text("Saved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            extraActions()
        }
    }
}

/** Settings: the same chooser in a dialog. */
@Composable
internal fun LlmProviderDialog(
    initial: LlmConfig,
    geminiKeySaved: Boolean,
    onSave: SaveLlm,
    onLoadModels: suspend (base: String, key: String) -> List<String>,
    onDismiss: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI model") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LlmProviderForm(initial, geminiKeySaved, "Save", onSave, onLoadModels)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
