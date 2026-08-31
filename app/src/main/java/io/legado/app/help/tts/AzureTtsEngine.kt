package io.legado.app.help.tts

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

    private val region: String
        get() = httpTTS.getLoginInfoMap()
            ?.let { it["region"] ?: it["Region"] }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "eastus"

    private val endpoint: String
        get() {
            // 登录配置中的region优先，避免预设URL写死eastus导致Key区域不匹配
            val configuredRegion = httpTTS.getLoginInfoMap()
                ?.let { it["region"] ?: it["Region"] }
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            return if (configuredRegion != null) {
                "https://$configuredRegion.tts.speech.microsoft.com/cognitiveservices/v1"
            } else {
                httpTTS.url.takeIf { it.startsWith("https://") }
                    ?: "https://$region.tts.speech.microsoft.com/cognitiveservices/v1"
            }
        }

    private val outputFormat: String
        get() = httpTTS.apiFormat
            .takeIf { it.startsWith("audio-") || it.startsWith("riff-") }
            ?: "audio-24khz-48kbitrate-mono-mp3"

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

        val key = subscriptionKey ?: throw IllegalStateException(
            "Azure TTS未配置subscriptionKey，请在引擎登录中填写Key和region"
        )

        val request = Request.Builder()
            .url(endpoint)
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .header("Ocp-Apim-Subscription-Key", key)
            .header("Content-Type", "application/ssml+xml")
            .header("X-Microsoft-OutputFormat", outputFormat)
            .header("User-Agent", "Legado")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string()?.take(500).orEmpty()
            val hint = when (response.code) {
                401, 403 -> "Key无效或region与Azure资源不匹配"
                404 -> "Azure区域或接口地址错误"
                429 -> "Azure额度不足或请求过快"
                else -> "请检查网络和Azure语音资源"
            }
            throw IllegalStateException(
                "Azure TTS HTTP ${response.code}: $hint${if (errorBody.isBlank()) "" else "\n$errorBody"}"
            )
        }
        val bytes = response.body?.bytes() ?: ByteArray(0)
        if (bytes.isEmpty()) throw IllegalStateException("Azure TTS返回空音频")
        bytes
    }

    override suspend fun synthesizeStream(
        text: String,
        voice: String?,
        speed: Float,
        options: Map<String, Any>
    ): Flow<ByteArray> = flow {
        val usedVoice = voice ?: this@AzureTtsEngine.voice
        val ssml = buildSsml(text, usedVoice, speed, null, options["emotion"] as? String)

        val key = subscriptionKey ?: throw IllegalStateException(
            "Azure TTS未配置subscriptionKey，请在引擎登录中填写Key和region"
        )

        val request = Request.Builder()
            .url(endpoint)
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .header("Ocp-Apim-Subscription-Key", key)
            .header("Content-Type", "application/ssml+xml")
            .header("X-Microsoft-OutputFormat", outputFormat)
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("Azure TTS HTTP ${response.code}: Key或region配置错误")
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
