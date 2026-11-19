package com.bydassistantng.llm

import android.content.Context
import android.os.SystemClock
import com.bydassistantng.R
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.SettingsPaths
import com.bydassistantng.gemini.SwitchableFeature
import com.bydassistantng.gemini.SwitchedOff
import com.bydassistantng.gemini.TurnStatus
import com.bydassistantng.gemini.VehicleFunctionDispatcher
import com.bydassistantng.gemini.assistantTools
import com.bydassistantng.gemini.voiceAssistantSystemPrompt
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.ShellHelperVehicleController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PipelineController"
private const val MAX_TOOL_ROUNDS = 4

/**
 * One voice turn for any OpenAI-compatible model: recorded audio -> speech-to-text -> chat (with the same
 * navigation / app / media / car tools Gemini gets) -> text reply, which the caller speaks.
 * Same contract as [com.bydassistantng.gemini.ConversationController.runTurn], so callers are unchanged.
 */
@Singleton
class PipelineConversationController @Inject constructor(
    private val client: OpenAiCompatClient,
    private val configRepo: LlmConfigRepository,
    private val preferencesRepository: PreferencesRepository,
    private val vehicleController: ShellHelperVehicleController,
    @ApplicationContext private val context: Context,
) {
    suspend fun runTurn(audioFile: File, onStatus: suspend (TurnStatus) -> Unit) {
        val t0 = SystemClock.elapsedRealtime()
        onStatus(TurnStatus.Thinking)
        val cfg = configRepo.current()
        val llmKey = configRepo.llmKey()
        if (llmKey.isNullOrBlank() && !cfg.baseUrl.startsWith("http://")) {
            return onStatus(TurnStatus.Failed(txt(R.string.classic_no_key)))
        }
        val language = preferencesRepository.assistantLanguage.first()
        val vehicleControlEnabled = preferencesRepository.vehicleControlEnabled.first()
        val tools = OpenAiCompatClient.toOpenAiTools(assistantTools(vehicleControlEnabled))

        try {
            val heard = client.transcribe(
                cfg.effectiveSttBase, configRepo.sttKey(), cfg.sttModel, audioFile,
                language.bcp47.ifBlank { null },
            )
            if (heard.isBlank()) return onStatus(TurnStatus.Failed(txt(R.string.classic_empty_response)))

            val switchedOff = if (vehicleControlEnabled) emptyList()
            else listOf(SwitchedOff(SwitchableFeature.VEHICLE_CONTROL, SettingsPaths.of(context, SwitchableFeature.VEHICLE_CONTROL)))
            val system = voiceAssistantSystemPrompt(language, switchedOff = switchedOff)
            val messages = mutableListOf(
                buildJsonObject { put("role", "system"); put("content", system) },
                buildJsonObject { put("role", "user"); put("content", heard) },
            )

            repeat(MAX_TOOL_ROUNDS) {
                val reply = client.chat(cfg.baseUrl.trimEnd('/'), llmKey, cfg.model, messages, tools)
                if (reply.toolCalls.isEmpty()) {
                    AppLogger.log(TAG, "done in ${SystemClock.elapsedRealtime() - t0}ms")
                    val text = reply.text
                    return onStatus(if (text != null) TurnStatus.Speaking(text, language) else TurnStatus.Failed(txt(R.string.classic_empty_reply)))
                }
                messages += reply.raw
                for (call in reply.toolCalls) {
                    AppLogger.log(TAG, "tool call: ${call.name}(${call.args})")
                    val result = VehicleFunctionDispatcher.dispatch(
                        GeminiFunctionCall(call.name, call.args), vehicleControlEnabled, vehicleController, context,
                    ) { name -> onStatus(TurnStatus.DispatchingCommand(name)) }
                    messages += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", call.id)
                        put("content", result.toString())
                    }
                }
            }
            onStatus(TurnStatus.Failed(txt(R.string.classic_empty_reply)))
        } catch (e: LlmException) {
            AppLogger.logError(TAG, "provider error ${e.code}", e)
            onStatus(TurnStatus.Failed(when (e.code) {
                401, 403 -> txt(R.string.classic_key_rejected)
                429 -> txt(R.string.classic_rate_limited)
                else -> "${txt(R.string.classic_provider_error)} (${e.code})"
            }))
        } catch (e: java.io.IOException) {
            AppLogger.logError(TAG, "network error", e)
            onStatus(TurnStatus.Failed(txt(R.string.classic_network)))
        }
    }

    private fun txt(id: Int): String = AppLanguage.string(context, id)
}
