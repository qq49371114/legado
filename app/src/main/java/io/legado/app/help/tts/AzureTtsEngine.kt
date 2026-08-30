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

/**
 * Azure 认知服务 TTS 引擎
 * 支持 SSML、多情感、多角色对话
 * 音色: zh-CN-XiaoxiaoNeural (cheerful/sad/angry/excited/friendly)
 *       zh-CN-YunxiNeural, zh-CN-YunyangNeural 等
 *
 * 需要 Azure 认知服务订阅 key
 */
class AzureTtsEngine(
    private val httpTTS: HttpTTS
) : AiTtsEngine {

    override val engineType: String = AiTtsEngine.TYPE_AZURE
    override val supportsStreaming: Boolean = true
    override val supportsSsml: Boolean = true
    override val maxCharPerRequest: Int = 10000

    private val endpoint: String
        get() = httpTTS.url.ifBlank {
            "https://eastus.tts.speech.microsoft.com/cognitiveservices/v1"
        }

    private val subscriptionKey: String?
        get() {
            // 优先读取登录信息（避免把Key明文放在header配置中）
            httpTTS.getLoginInfoMap()?.let { info ->
                info["subscriptionKey"]?.takeIf { it.isNotBlank() }?.let { return it }
                info["Subscription Key"]?.takeIf { it.isNotBlank() }?.let { return it }
            }
            httpTTS.header?.let { headerStr ->
                runCatching {
                    val headers = JSONObject(headerStr)
                    headers.optString("Ocp-Apim-Subscription-Key", "").let {
                        if (it.isNotBlank()) return it
                    }
                    headers.optString("api-key", "").let {
                        if (it.isNotBlank()) return it
                    }
                }
            }
            return null
        }

    private val voice: String
        get() = httpTTS.voiceName ?: "zh-CN-XiaoxiaoNeural"

    /** 构建SSML */
    private fun buildSsml(
        text: String,
        voice: String,
        speed: Float,
        pitch: Float?,
        emotion: String? = null
    ): String {
        val rateStr = "${(speed * 100 - 100).toInt()}%"
        val pitchStr = pitch?.let { "${(it * 100 - 100).toInt()}%" } ?: "+0%"
        val escapedText = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val content = if (emotion.isNullOrBlank() || emotion == "neutral") {
            escapedText
        } else {
            """<mstts:express-as style="$emotion">$escapedText</mstts:express-as>"""
        }

        return """
            <speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis"
                   xmlns:mstts="https://www.w3.org/2001/mstts"
                   xml:lang="zh-CN">
                <voice name="$voice">
                    <prosody rate="$rateStr" pitch="$pitchStr">
                        $content
                    </prosody>
                </voice>
            </speak>
        """.trimIndent()
    }

    override suspend fun synthesize(
        text: String,
        voice: String?,
        speed: Float,
        pitch: Float?,
        options: Map<String, Any>
    ): ByteArray = withContext(Dispatchers.IO) {
        val usedVoice = voice ?: this@AzureTtsEngine.voice
        val emotion = options["emotion"] as? String
        val ssml = buildSsml(text, usedVoice, speed, pitch, emotion)

        val key = subscriptionKey ?: run {
            AppLog.put("Azure TTS: 未配置 Subscription Key")
            return@withContext ByteArray(0)
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .header("Ocp-Apim-Subscription-Key", key)
            .header("Content-Type", "application/ssml+xml")
            .header("X-Microsoft-OutputFormat", httpTTS.apiFormat.ifBlank { "audio-16khz-128kbitrate-mono-mp3" })
            .header("User-Agent", "Legado")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            AppLog.put("Azure TTS 合成失败: HTTP ${response.code}\n$errorBody")
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
        val usedVoice = voice ?: this@AzureTtsEngine.voice
        val ssml = buildSsml(text, usedVoice, speed, null, options["emotion"] as? String)

        val key = subscriptionKey ?: return@flow

        val request = Request.Builder()
            .url(endpoint)
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .header("Ocp-Apim-Subscription-Key", key)
            .header("Content-Type", "application/ssml+xml")
            .header("X-Microsoft-OutputFormat", httpTTS.apiFormat.ifBlank { "audio-16khz-128kbitrate-mono-mp3" })
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
            AiTtsEngine.VoiceInfo("zh-CN-XiaoxiaoNeural", "晓晓(情感)", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaoyiNeural", "晓伊(活泼)", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-YunxiNeural", "云希(少年)", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("zh-CN-YunyangNeural", "云扬(新闻)", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaohanNeural", "晓涵(温暖)", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaochenNeural", "晓辰(成熟)", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-YunyeNeural", "云野(沉稳)", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("zh-CN-YunfengNeural", "云枫(大气)", "zh-CN", "Male"),
        )
    }
}
