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
        val emotion = options["emotion"]?.toString().orEmpty()
        val prosody = emotionProsody(emotion, speed, pitch)
        val audio = ByteArrayOutputStream()
        synthesizeInternal(
            text = text,
            voice = voice,
            speed = prosody.speed,
            pitch = prosody.pitch,
            volumePercent = prosody.volumePercent
        ) { chunk ->
            audio.write(chunk)
        }
        audio.toByteArray()
    }

    private data class Prosody(
        val speed: Float,
        val pitch: Float,
        val volumePercent: Int
    )

    /**
     * Edge 免费端点不支持 Azure 的 mstts:express-as，只能用 prosody 模拟情绪。
     *
     * 2026-09-03 用真实 WebSocket + ffmpeg 解码逐值实测出的边界（基频/RMS 量化）：
     * - pitch：+50% 处饱和（基频 116→173Hz），再往上无变化；负方向 -30% 之后失效
     * - volume：±50% 处饱和（RMS ±906），旧代码却把它夹在 ±20% 内，白扔掉一半表现力
     * - rate：±100% 都持续生效
     * - mstts:express-as / break / emphasis / say-as 一律被拒（SSML is invalid）
     * - 一条 SSML 里超过 2 个 prosody 直接被拒，所以句内起伏只能靠拆多次请求
     *
     * 旧值最大只有 pitch ±10% / volume ±12%，人耳基本听不出差别 —— 这就是
     * "角色和旁白都在背书"的直接原因。下面按实测边界重标定，全部用足量程。
     */
    private fun emotionProsody(emotion: String, baseSpeed: Float, explicitPitch: Float?): Prosody {
        val basePitch = explicitPitch ?: 1f
        return when (emotion.lowercase()) {
            // 拟声笑：音高拉到接近饱和 + 加速 + 加大音量，才像真在笑
            "laugh" -> Prosody((baseSpeed * 1.28f).coerceAtMost(1.9f), basePitch * 1.42f, 42)
            // 哭腔：压音高、显著放慢、收音量
            "cry" -> Prosody((baseSpeed * 0.72f).coerceAtLeast(0.5f), basePitch * 0.74f, -28)
            // 喊叫：音量直接拉到饱和
            "shout" -> Prosody((baseSpeed * 1.16f).coerceAtMost(1.8f), basePitch * 1.20f, 50)
            // 低语：压到最低可辨音量
            "whisper" -> Prosody((baseSpeed * 0.82f).coerceAtLeast(0.55f), basePitch * 0.88f, -45)
            "excited" -> Prosody((baseSpeed * 1.22f).coerceAtMost(1.85f), basePitch * 1.30f, 34)
            "cheerful", "happy" -> Prosody((baseSpeed * 1.10f).coerceAtMost(1.7f), basePitch * 1.18f, 20)
            "angry" -> Prosody((baseSpeed * 1.18f).coerceAtMost(1.8f), basePitch * 0.86f, 46)
            "sad" -> Prosody((baseSpeed * 0.78f).coerceAtLeast(0.5f), basePitch * 0.80f, -20)
            "friendly" -> Prosody((baseSpeed * 0.97f).coerceAtLeast(0.6f), basePitch * 1.07f, 8)
            "fearful", "suspense" -> Prosody((baseSpeed * 0.88f).coerceAtLeast(0.6f), basePitch * 0.92f, -26)
            else -> Prosody(baseSpeed, basePitch, 0)
        }
    }

    override suspend fun synthesizeStream(
        text: String,
        voice: String?,
        speed: Float,
        options: Map<String, Any>
    ): Flow<ByteArray> = kotlinx.coroutines.flow.channelFlow {
        synthesizeInternal(text, voice, speed, null, 0) { chunk ->
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
        volumePercent: Int,
        onChunk: ((ByteArray) -> Unit)?
    ) {
        val usedVoice = voice ?: this.voice
        val requestId = UUID.randomUUID().toString().replace("-", "").uppercase()

        // 构建SSML
        // 夹取范围按 2026-09-03 实测饱和点设定：
        // pitch 正向 +50% 饱和、负向 -30% 之后不再变化；volume ±50% 饱和。
        // 旧代码把 volume 夹在 ±20%，等于主动丢掉一半可用表现力。
        val rateStr = "${(speed * 100 - 100).toInt()}%"
        val pitchStr = pitch?.let { "${(it * 100 - 100).toInt().coerceIn(-30, 50)}%" } ?: "+0%"
        val volumeStr = "${volumePercent.coerceIn(-50, 50).let { if (it >= 0) "+$it%" else "$it%" }}"
        val ssml = buildString {
            append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>")
            append("<voice name='$usedVoice'>")
            append("<prosody pitch='$pitchStr' rate='$rateStr' volume='$volumeStr'>")
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
