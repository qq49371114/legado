package io.legado.app.help.tts

import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Edge-TTS 引擎 — 免费，基于微软 Bing 语音合成 WebSocket 服务
 * 零成本、高质量、低延迟，支持 200+ 音色（含 19 个中文音色）
 *
 * 协议: wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1
 * 流程: 连接 → 发speech.config → 发SSML → 收音频二进制块 → turn.end结束
 */
class EdgeTtsEngine(
    private val httpTTS: HttpTTS
) : AiTtsEngine {

    override val engineType: String = AiTtsEngine.TYPE_EDGE
    override val supportsStreaming: Boolean = true
    override val supportsSsml: Boolean = true
    override val maxCharPerRequest: Int = 5000

    companion object {
        // 微软 Edge-TTS WebSocket 端点（免费公开）
        private const val WS_ENDPOINT =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WS_ORIGIN = "chrome-extension://jdiccldigfinghifnjbnofpkoeajenbp"
        private const val WS_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36 Edg/119.0.0.0"
    }

    private val voice: String
        get() = httpTTS.voiceName ?: "zh-CN-XiaoxiaoNeural"

    private val outputFormat: String
        get() = httpTTS.apiFormat.ifBlank { "audio-24khz-48kbitrate-mono-mp3" }

    override suspend fun synthesize(
        text: String,
        voice: String?,
        speed: Float,
        pitch: Float?,
        options: Map<String, Any>
    ): ByteArray = withContext(Dispatchers.IO) {
        val audio = ByteArrayOutputStream()
        synthesizeInternal(text, voice, speed, pitch) { chunk ->
            audio.write(chunk)
        }
        audio.toByteArray()
    }

    override suspend fun synthesizeStream(
        text: String,
        voice: String?,
        speed: Float,
        options: Map<String, Any>
    ): Flow<ByteArray> = kotlinx.coroutines.flow.channelFlow {
        synthesizeInternal(text, voice, speed, null) { chunk ->
            trySend(chunk)  // channelFlow 内用 trySend 发送
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Edge-TTS WebSocket 合成核心
     * @param onChunk 音频块回调（null = 流式）
     */
    private suspend fun synthesizeInternal(
        text: String,
        voice: String?,
        speed: Float,
        pitch: Float?,
        onChunk: ((ByteArray) -> Unit)?
    ) {
        val usedVoice = voice ?: this.voice
        val requestId = UUID.randomUUID().toString().replace("-", "").uppercase()

        // 构建SSML
        val rateStr = "${(speed * 100 - 100).toInt()}%"
        val pitchStr = pitch?.let { "${(it * 100 - 100).toInt()}%" } ?: "+0Hz"
        val ssml = buildString {
            append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>")
            append("<voice name='$usedVoice'>")
            append("<prosody pitch='$pitchStr' rate='$rateStr' volume='+0%'>")
            append(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
            append("</prosody></voice></speak>")
        }

        // speech.config 消息
        val configBody = JSONObject().apply {
            put("context", JSONObject().apply {
                put("synthesis", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("metadataoptions", JSONObject().apply {
                            put("sentenceBoundaryEnabled", "false")
                            put("wordBoundaryEnabled", "false")
                        })
                        put("outputFormat", outputFormat)
                    })
                })
            })
        }

        val connected = AtomicBoolean(false)
        val turnEnded = AtomicBoolean(false)
        val errorHolder = arrayOfNulls<Exception>(1)

        val request = Request.Builder()
            .url(WS_ENDPOINT)
            .header("Origin", WS_ORIGIN)
            .header("User-Agent", WS_UA)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true)
                // 1. 发送 speech.config
                webSocket.send(
                    "X-RequestId: $requestId\n" +
                        "Content-Type: application/json; charset=utf-8\n" +
                        "Path: speech.config\n\n" +
                        configBody.toString()
                )
                // 2. 发送 SSML
                webSocket.send(
                    "X-RequestId: $requestId\n" +
                        "Content-Type: application/ssml+xml\n" +
                        "Path: ssml\n\n" +
                        ssml
                )
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                // Edge-TTS 二进制格式：前2字节为文本Header长度(大端)，随后Header文本，再后为音频
                if (data.size < 2) return
                val headerLength = ((data[0].toInt() and 0xFF) shl 8) or
                    (data[1].toInt() and 0xFF)
                val audioStart = 2 + headerLength
                if (audioStart >= data.size) return
                val audioData = data.copyOfRange(audioStart, data.size)
                if (audioData.isNotEmpty()) onChunk?.invoke(audioData)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 文本消息包含Path:turn.end，以此判断本轮合成结束
                if (text.contains("Path:turn.end", ignoreCase = true)) {
                    turnEnded.set(true)
                    webSocket.close(1000, "done")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                errorHolder[0] = t as? Exception ?: Exception(t.message)
                if (!turnEnded.get()) {
                    try { webSocket.close(1000, "error") } catch (_: Exception) {}
                }
            }
        }

        val webSocket = okHttpClient.newWebSocket(request, listener)

        // 等待连接完成
        val connectTimeout = 10000L
        val startTime = System.currentTimeMillis()
        while (!connected.get() && System.currentTimeMillis() - startTime < connectTimeout) {
            kotlinx.coroutines.delay(50)
        }
        if (!connected.get()) {
            webSocket.cancel()
            errorHolder[0]?.let { throw it }
            throw Exception("Edge-TTS 连接失败")
        }

        // 等待 turn.end 或超时（60秒）
        val synthesisTimeout = 60000L
        val synthStart = System.currentTimeMillis()
        while (!turnEnded.get() && System.currentTimeMillis() - synthStart < synthesisTimeout) {
            errorHolder[0]?.let { throw it }
            kotlinx.coroutines.delay(50)
        }
        if (!turnEnded.get()) {
            webSocket.cancel()
            throw Exception("Edge-TTS 合成超时")
        }
        webSocket.close(1000, "done")
    }

    override suspend fun listVoices(): List<AiTtsEngine.VoiceInfo> {
        return AiTtsEngineFactory.getVoices(AiTtsEngine.TYPE_EDGE)
    }
}
