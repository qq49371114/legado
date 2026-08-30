package io.legado.app.help.tts

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * OpenAI TTS 引擎
 * 支持 tts-1 / tts-1-hd 模型
 * 音色: alloy/echo/fable/onyx/nova/shimmer
 * 支持 mp3/opus/aac/flac/wav 格式
 */
class OpenAiTtsEngine(
    private val httpTTS: HttpTTS
) : AiTtsEngine {

    override val engineType: String = AiTtsEngine.TYPE_OPENAI
    override val supportsStreaming: Boolean = false
    override val supportsSsml: Boolean = false
    override val maxCharPerRequest: Int = 4096

    /**
     * API 端点 — 默认 OpenAI 官方，也支持兼容 API
     */
    private val apiEndpoint: String
        get() = httpTTS.url.ifBlank { "https://api.openai.com/v1/audio/speech" }

    /**
     * API Key — 从 header 或 loginInfo 获取
     */
    private val apiKey: String?
        get() {
            httpTTS.header?.let { headerStr ->
                runCatching {
                    val headers = JSONObject(headerStr)
                    // 优先 Authorization
                    headers.optString("Authorization", "").let {
                        if (it.startsWith("Bearer ")) return it.removePrefix("Bearer ")
                        if (it.isNotBlank()) return it
                    }
                    headers.optString("api-key", "").let {
                        if (it.isNotBlank()) return it
                    }
                }
            }
            return null
        }

    private val model: String
        get() = httpTTS.voiceModel ?: "tts-1"

    private val voice: String
        get() = httpTTS.voiceName ?: "alloy"

    private val format: String
        get() = httpTTS.apiFormat.ifBlank { "mp3" }

    override suspend fun synthesize(
        text: String,
        voice: String?,
        speed: Float,
        pitch: Float?,
        options: Map<String, Any>
    ): ByteArray = withContext(Dispatchers.IO) {
        val requestBody = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("voice", voice ?: this@OpenAiTtsEngine.voice)
            put("response_format", format)
            // OpenAI speed: 0.25 ~ 4.0, 默认 1.0
            val speedStr = String.format("%.2f", speed.coerceIn(0.25, 4.0))
            put("speed", speedStr.toDouble())
        }.toString()

        val key = apiKey ?: run {
            AppLog.put("OpenAI TTS: 未配置 API Key")
            return@withContext ByteArray(0)
        }

        val request = Request.Builder()
            .url(apiEndpoint)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            AppLog.put("OpenAI TTS 合成失败: HTTP ${response.code}\n$errorBody")
            return@withContext ByteArray(0)
        }
        response.body?.bytes() ?: ByteArray(0)
    }

    override suspend fun synthesizeStream(
        text: String,
        voice: String?,
        speed: Float,
        options: Map<String, Any>
    ): Flow<ByteArray> = flow {
        // OpenAI TTS 不原生支持流式，用同步合成后分块 emit
        val audio = synthesize(text, voice, speed)
        val chunkSize = 8192
        var offset = 0
        while (offset < audio.size) {
            val end = minOf(offset + chunkSize, audio.size)
            emit(audio.copyOfRange(offset, end))
            offset = end
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listVoices(): List<AiTtsEngine.VoiceInfo> {
        return AiTtsEngineFactory.getVoices(AiTtsEngine.TYPE_OPENAI)
    }
}
