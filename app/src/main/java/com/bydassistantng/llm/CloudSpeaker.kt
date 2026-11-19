package com.bydassistantng.llm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.bydassistantng.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks a reply through an OpenAI-compatible `/audio/speech` endpoint. Needed because this head unit has no
 * platform speech engine for a text answer (and BYD's own engine is Chinese-only), so text from a non-Gemini
 * model would otherwise be silent.
 */
@Singleton
class CloudSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OpenAiCompatClient,
    private val configRepo: LlmConfigRepository,
) {
    private var player: MediaPlayer? = null

    suspend fun isEnabled(): Boolean = configRepo.current().cloudTtsEnabled

    suspend fun speak(text: String) {
        val cfg = configRepo.current()
        if (!cfg.cloudTtsEnabled) return
        try {
            val audio = client.speak(cfg.effectiveTtsBase, configRepo.ttsKey(), cfg.ttsModel, cfg.ttsVoice, text)
            val file = File(context.cacheDir, "reply.mp3").apply { writeBytes(audio) }
            withContext(Dispatchers.Main) {
                stop()
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { it.release(); if (player === it) player = null }
                    setOnErrorListener { mp, what, extra ->
                        AppLogger.logError("CloudSpeaker", "MediaPlayer error $what/$extra")
                        mp.release(); if (player === mp) player = null; true
                    }
                    prepare()
                    start()
                }
            }
        } catch (e: Exception) {
            AppLogger.logError("CloudSpeaker", "Could not speak the reply", e)
        }
    }

    fun stop() {
        runCatching { player?.release() }
        player = null
    }
}
