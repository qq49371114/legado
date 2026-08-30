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
 * CosyVoice TTS 引擎 — 阿里通义千问语音合成
 * 支持声音克隆、情感合成、多语种
 * 可对接本地部署的 CosyVoice 模型或 API 服务
 *
 * 默认对接本地: http://localhost:5000/api/tts
 */
class CosyVoiceTtsEngine(
    private val httpTTS: HttpTTS
) : AiTtsEngine {

    override val engineType: String = AiTtsEngine.TYPE_COSYVOICE
    override val supportsStreaming: Boolean = true
    override val supportsSsml: Boolean = false
    override val maxCharPerRequest: Int = 500

    private val endpoint: String
        get() = httpTTS.url.ifBlank { "http://localhost:5000/api/tts" }

    private val voice: String
        get() = httpTTS.voiceName ?: "中文女"

    override suspend fun synthesize(
        text: String,
        voice: String?,
        speed: Float,
        pitch: Float?,
        options: Map<String, Any>
    ): ByteArray = withContext(Dispatchers.IO) {
        val requestBody = JSONObject().apply {
            put("text", text)
            put("voice", voice ?: this@CosyVoiceTtsEngine.voice)
            put("speed", speed)
            pitch?.let { put("pitch", it) }
            // 声音克隆音频URL（可选）
            options["cloneAudioUrl"]?.let { put("clone_audio_url", it) }
            // 情感标签
            options["emotion"]?.let { put("emotion", it) }
            put("format", httpTTS.apiFormat.ifBlank { "wav" })
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .apply {
                httpTTS.header?.let { headerStr ->
                    runCatching {
                        val headers = JSONObject(headerStr)
                        headers.keys().forEach { key ->
                            header(key, headers.getString(key))
                        }
                    }
                }
            }
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            AppLog.put("CosyVoice TTS 合成合成失败: HTTP ${response.code}")
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
        val requestBody = JSONObject().apply {
            put("text", text)
            put("voice", voice ?: this@CosyVoiceTtsEngine.voice)
            put("speed", speed)
            put("stream", true)
            put("format", httpTTS.apiFormat.ifBlank { "wav" })
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) return@flow

        val inputStream = response.body?.byteStream() ?: return@flow
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } > 0) {
            emit(buffer.copyOfRange(0, bytesRead))
        }
        inputStream.close()
    }.flowOn(Dispatchers.IO)

    override suspend fun listVoices(): List<AiTtsEngine.VoiceInfo> {
        return listOf(
            AiTtsEngine.VoiceInfo("中文女", "中文女声", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("中文男", "中文男声", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("clone", "克隆声音", "zh-CN", "Neutral"),
        )
    }
}
