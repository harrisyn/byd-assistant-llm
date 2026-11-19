package com.bydassistantng.llm

import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import com.bydassistantng.gemini.GeminiTool
import com.bydassistantng.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OpenAiCompatClient"
private val JSON = "application/json".toMediaType()

class LlmException(val code: Int, message: String) : IOException(message)

/** One assistant message back from /chat/completions: text, tool calls, or both. */
data class ChatReply(val raw: JsonObject, val text: String?, val toolCalls: List<ToolCall>)
data class ToolCall(val id: String, val name: String, val args: JsonObject)

/**
 * Minimal client for the de-facto standard HTTP API: `/chat/completions` (with tools), `/audio/transcriptions`
 * and `/audio/speech`. Deliberately plain OkHttp, like the Gemini client, so there is nothing else to pin.
 */
@Singleton
class OpenAiCompatClient @Inject constructor() {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun req(url: String, key: String?): Request.Builder =
        Request.Builder().url(url).apply { if (!key.isNullOrBlank()) header("Authorization", "Bearer $key") }

    private suspend fun exec(r: Request): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { resp ->
            val body = resp.body.bytes()
            if (!resp.isSuccessful) {
                val msg = String(body, Charsets.UTF_8).take(400)
                AppLogger.logError(TAG, "${r.method} ${r.url.encodedPath} -> ${resp.code}: $msg")
                throw LlmException(resp.code, msg)
            }
            body
        }
    }

    suspend fun chat(base: String, key: String?, model: String, messages: List<JsonObject>, tools: List<JsonObject>): ChatReply {
        val body = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages))
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(tools))
                put("tool_choice", "auto")
            }
        }
        val started = System.currentTimeMillis()
        val bytes = exec(req("$base/chat/completions", key).post(body.toString().toRequestBody(JSON)).build())
        AppLogger.log(TAG, "chat $model ok in ${System.currentTimeMillis() - started}ms (${messages.size} msgs, ${tools.size} tools)")
        val msg = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        val text = (msg["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val calls = (msg["tool_calls"] as? JsonArray).orEmpty().map { c ->
            val fn = c.jsonObject["function"]!!.jsonObject
            val argsText = fn["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "{}" }
            val args = runCatching { Json.parseToJsonElement(argsText).jsonObject }.getOrDefault(JsonObject(emptyMap()))
            ToolCall(c.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "call_${fn["name"]}", fn["name"]!!.jsonPrimitive.content, args)
        }
        return ChatReply(msg, text, calls)
    }

    /** Speech to text. [file] is whatever the recorder produced (m4a/AAC); servers sniff the container. */
    suspend fun transcribe(base: String, key: String?, model: String, file: File, language: String?): String {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .apply { if (!language.isNullOrBlank()) addFormDataPart("language", language) }
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .build()
        val started = System.currentTimeMillis()
        val bytes = exec(req("$base/audio/transcriptions", key).post(multipart).build())
        val text = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        AppLogger.log(TAG, "stt $model ok in ${System.currentTimeMillis() - started}ms: \"$text\"")
        return text
    }

    /** Text to speech. Returns an audio file's bytes (mp3 by default) for MediaPlayer to play. */
    suspend fun speak(base: String, key: String?, model: String, voice: String, text: String): ByteArray {
        val body = buildJsonObject {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", "mp3")
        }
        val started = System.currentTimeMillis()
        val bytes = exec(req("$base/audio/speech", key).post(body.toString().toRequestBody(JSON)).build())
        AppLogger.log(TAG, "tts $model ok in ${System.currentTimeMillis() - started}ms (${bytes.size} bytes)")
        return bytes
    }

    companion object {
        /** Gemini function declarations -> OpenAI `tools` entries (same JSON-Schema idea, lowercase types). */
        fun toOpenAiTools(tools: List<GeminiTool>): List<JsonObject> =
            tools.flatMap { it.functionDeclarations.orEmpty() }.map(::declarationToTool)

        private fun declarationToTool(d: GeminiFunctionDeclaration): JsonObject = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", d.name)
                put("description", d.description)
                put("parameters", d.parameters?.let(::schemaToJson) ?: buildJsonObject {
                    put("type", "object")
                    put("properties", JsonObject(emptyMap()))
                })
            })
        }

        private fun schemaToJson(s: GeminiSchema): JsonObject = buildJsonObject {
            put("type", s.type.lowercase())
            s.description?.let { put("description", it) }
            s.enum?.let { e -> putJsonArray("enum") { e.forEach { add(JsonPrimitive(it)) } } }
            s.properties?.let { props -> put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, schemaToJson(v)) } }) }
            s.required?.let { r -> putJsonArray("required") { r.forEach { add(JsonPrimitive(it)) } } }
        }
    }
}
