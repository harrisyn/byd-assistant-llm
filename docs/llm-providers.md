# Other LLM providers

Settings -> **AI model** (and the setup wizard's key step) switches between Gemini (Live audio, unchanged) and a provider preset:
**OpenAI, OpenRouter, Groq, MiniMax, Ollama / llama.cpp, Custom**. Pick one and the address, model and speech defaults are filled in;
you add the key. **Load models** fetches the provider's live model list (`GET /models`) to pick from, so there is no typing of model
names. **Advanced** shows every field. Providers without speech endpoints (OpenRouter, MiniMax, Ollama) borrow OpenAI's for
speech-to-text and voice, which takes a second key; Groq does speech-to-text itself and borrows OpenAI's voice.

Model names change often, so the preset model is only a starting point (`openrouter/auto` for OpenRouter). The MiniMax and Groq
defaults come from public docs and third-party configs and have not been verified against live accounts.


With an OpenAI-compatible model a voice turn is: recorded audio -> `/audio/transcriptions` -> `/chat/completions`
(with the same navigation / app / media / car tools) -> text -> `/audio/speech` played through the car speakers.
Each of the three can point at a different base URL, model and key; speech-to-text and voice default to the chat endpoint.
Leave the voice model empty to use the platform speech engine instead (most head units have none).

Claude, which has no speech endpoints, works through an OpenAI-compatible gateway (OpenRouter, LiteLLM) for chat,
with a separate provider for speech-to-text and voice. A direct Anthropic adapter is not implemented yet.

## Headless setup

Typing URLs and keys on a car screen is slow. Push a file and the app imports it, then deletes it:

```
adb push llm-config.json /sdcard/Android/data/com.bydassistantng.dev/files/
```

```json
{"provider":"openai_compat","baseUrl":"https://api.openai.com/v1","model":"gpt-4o-mini","apiKey":"...",
 "sttModel":"whisper-1","ttsModel":"tts-1","ttsVoice":"alloy","language":"english","skipOnboarding":"true"}
```

Optional keys: `sttBaseUrl`, `sttApiKey`, `ttsBaseUrl`, `ttsApiKey`.

## Status

Compiles and launches on a DiLink 5.0 head unit. The pipeline has not yet been exercised end to end against a provider.
