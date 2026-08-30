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
import java.security.MessageDigest
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
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
        private const val WS_ENDPOINT =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
        private const val WS_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val WS_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

        /** 生成微软Edge当前要求的Sec-MS-GEC令牌（按5分钟窗口） */
        private fun generateSecMsGec(): String {
            val unixSeconds = System.currentTimeMillis() / 1000L
            val roundedSeconds = unixSeconds - (unixSeconds % 300L)
            val windowsEpochSeconds = roundedSeconds + 11644473600L
            val fileTimeTicks = windowsEpochSeconds * 10_000_000L
            val input = "$fileTimeTicks$TRUSTED_CLIENT_TOKEN"
            return MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.US_ASCII))
                .joinToString("") { "%02X".format(it) }
        }
    }

    private val voice: String
        get() = httpTTS.voiceName ?: "zh-CN-XiaoxiaoNeural"

    private val outputFormat: String
        get() = httpTTS.apiFormat
            .takeIf { it.startsWith("audio-") }
            ?: "audio-24khz-48kbitrate-mono-mp3"

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

        // 必须实际收到音频，避免服务端拒绝后生成空文件却被当作成功
        val audioReceived = AtomicBoolean(false)
        val connected = AtomicBoolean(false)
        val turnEnded = AtomicBoolean(false)
        val errorHolder = arrayOfNulls<Exception>(1)

        val connectionId = UUID.randomUUID().toString().replace("-", "").uppercase()
        val websocketUrl = "$WS_ENDPOINT&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=${generateSecMsGec()}" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
        val request = Request.Builder()
            .url(websocketUrl)
            .header("Origin", WS_ORIGIN)
            .header("User-Agent", WS_UA)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate, br, zstd")
            .header("Sec-WebSocket-Version", "13")
            .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase()};")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true)
                // 1. 发送 speech.config
                webSocket.send(
                    "X-Timestamp:${java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)}\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        configBody.toString() + "\r\n"
                )
                // 2. 发送 SSML
                webSocket.send(
                    "X-RequestId:$requestId\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)}\r\n" +
                        "Path:ssml\r\n\r\n" +
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
                if (audioData.isNotEmpty()) {
                    audioReceived.set(true)
                    onChunk?.invoke(audioData)
                }
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
        if (!audioReceived.get()) {
            webSocket.cancel()
            throw Exception("Edge-TTS 未返回音频，请检查网络或稍后重试")
        }
        webSocket.close(1000, "done")
    }

    override suspend fun listVoices(): List<AiTtsEngine.VoiceInfo> {
        return AiTtsEngineFactory.getVoices(AiTtsEngine.TYPE_EDGE)
    }
}
