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
 * Edge-TTS 引擎 — 免费，基于微软 Bing 语音合成服务
 * 支持 200+ 音色，包括 19 个中文音色
 * 零成本、高质量、低延迟
 *
 * 使用 HTTPS POST 到 edge-tts 代理端点
 * 可对接自建 edge-tts-server 或直接用公开 API
 */
class EdgeTtsEngine(
    private val httpTTS: HttpTTS
) : AiTtsEngine {

    override val engineType: String = AiTtsEngine.TYPE_EDGE
    override val supportsStreaming: Boolean = true
    override val supportsSsml: Boolean = false
    override val maxCharPerRequest: Int = 5000

    /**
     * Edge-TTS 服务端点
     * httpTTS.url 字段存储端点地址，默认用公开 edge-tts API
     */
    private val endpoint: String
        get() = httpTTS.url.ifBlank {
            "https://edge-tts-proxy.example.com/api/tts"
        }

    private val voice: String
        get() = httpTTS.voiceName ?: "zh-CN-XiaoxiaoNeural"

    override suspend fun synthesize(
        text: String,
        voice: String?,
        speed: Float,
        pitch: Float?,
        options: Map<String, Any>
    ): ByteArray = withContext(Dispatchers.IO) {
        val requestBody = JSONObject().apply {
            put("text", text)
            put("voice", voice ?: this@EdgeTtsEngine.voice)
            put("rate", "${(speed * 100 - 100).toInt()}%")
            pitch?.let { put("pitch", "${(it * 100 - 100).toInt()}%") }
            put("format", httpTTS.apiFormat.ifBlank { "mp3" })
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .apply {
                httpTTS.header?.let { headerStr ->
                    // 解析自定义 header
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
            AppLog.put("Edge-TTS 合成失败: HTTP ${response.code}")
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
            put("voice", voice ?: this@EdgeTtsEngine.voice)
            put("rate", "${(speed * 100 - 100).toInt()}%")
            put("format", httpTTS.apiFormat.ifBlank { "mp3" })
            put("stream", true) // 请求流式返回
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            AppLog.put("Edge-TTS 流式合成失败: HTTP ${response.code}")
            return@flow
        }

        val inputStream = response.body?.byteStream() ?: return@flow
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } > 0) {
            emit(buffer.copyOfRange(0, bytesRead))
        }
        inputStream.close()
    }.flowOn(Dispatchers.IO)

    override suspend fun listVoices(): List<AiTtsEngine.VoiceInfo> {
        return AiTtsEngineFactory.getVoices(AiTtsEngine.TYPE_EDGE)
    }
}
