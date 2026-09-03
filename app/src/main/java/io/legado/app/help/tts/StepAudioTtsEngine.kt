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
 * 阶跃星辰 StepAudio 2.5 TTS 引擎 —— 语境感知语音合成。
 *
 * 为什么加这个引擎（2026-09-03）：
 * Edge 免费端点实测拒绝 mstts:express-as / break / emphasis，且一条 SSML 里
 * 超过 2 个 prosody 就返回 SSML is invalid。也就是说在 Edge 上"情感"只能靠
 * 语速/音高/音量硬调，听起来始终是背书。
 *
 * StepAudio 2.5 是真正能"演"的模型，两层情感控制都实测生效：
 * 1. instruction（全局语境，≤200 字符）：自然语言描述整段基调。
 *    实测同一句话带"极度开心，放声大笑"比无指令长 1.34 秒 —— 多出来的
 *    时间就是模型真的在笑，不是把字念快。
 * 2. input 里的圆括号 ()：句内指令，括号内容不会被朗读，只作为表演提示。
 *    实测"（爆发出大笑）…（笑得喘不上气）…"比平读长 1.75 秒。
 *
 * 端点：POST {base}/audio/speech（OpenAI 兼容形状，多了 instruction 字段）
 * 音色：25 个官方中文音色，2026-09-03 逐个实测全部可用
 * 计费：按字符，括号内的指令也算字符，所以指令要短
 */
class StepAudioTtsEngine(
    private val httpTTS: HttpTTS
) : AiTtsEngine {

    override val engineType: String = AiTtsEngine.TYPE_STEPAUDIO
    override val supportsStreaming: Boolean = false
    /** 不用 SSML —— 这个模型用自然语言指令，比 SSML 表达力强得多 */
    override val supportsSsml: Boolean = false
    /** 官方单次输入上限 1000 字符 */
    override val maxCharPerRequest: Int = 1000

    private val apiEndpoint: String
        get() = httpTTS.url.ifBlank { DEFAULT_ENDPOINT }

    private val apiKey: String?
        get() {
            httpTTS.getLoginInfoMap()?.let { info ->
                info["apiKey"]?.takeIf { it.isNotBlank() }?.let { return it }
                info["API Key"]?.takeIf { it.isNotBlank() }?.let { return it }
            }
            httpTTS.header?.let { headerStr ->
                runCatching {
                    val headers = JSONObject(headerStr)
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
        get() = httpTTS.voiceModel?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    private val voice: String
        get() = httpTTS.voiceName?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE

    private val format: String
        get() = httpTTS.apiFormat.takeIf { it in SUPPORTED_FORMATS } ?: "mp3"

    override suspend fun synthesize(
        text: String,
        voice: String?,
        speed: Float,
        pitch: Float?,
        options: Map<String, Any>
    ): ByteArray = withContext(Dispatchers.IO) {
        val key = apiKey ?: run {
            AppLog.put("StepAudio TTS: 未配置 API Key")
            return@withContext ByteArray(0)
        }

        val emotion = options["emotion"]?.toString().orEmpty()
        val speaker = options["speaker"]?.toString().orEmpty()

        val body = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("voice", voice ?: this@StepAudioTtsEngine.voice)
            put("response_format", format)
            // 官方 speed 范围 0.5-2.0
            put("speed", String.format("%.2f", speed.coerceIn(0.5f, 2.0f)).toDouble())
            instructionFor(emotion, speaker)?.let { put("instruction", it) }
        }.toString()

        val request = Request.Builder()
            .url(apiEndpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string()?.take(300) ?: ""
            AppLog.put("StepAudio TTS 合成失败: HTTP ${response.code}\n$errorBody")
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
        // 非流式端点，合成完再分块 emit
        val audio = synthesize(text, voice, speed, null, options)
        var offset = 0
        while (offset < audio.size) {
            val end = minOf(offset + 8192, audio.size)
            emit(audio.copyOfRange(offset, end))
            offset = end
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listVoices(): List<AiTtsEngine.VoiceInfo> {
        return AiTtsEngineFactory.getVoices(AiTtsEngine.TYPE_STEPAUDIO)
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.stepfun.com/v1/audio/speech"
        const val DEFAULT_MODEL = "stepaudio-2.5-tts"
        const val DEFAULT_VOICE = "linjiajiejie"
        const val DEFAULT_NARRATOR = "boyinnansheng"

        private val SUPPORTED_FORMATS = setOf("mp3", "wav", "flac", "opus", "pcm")

        /**
         * 情绪标签 → 中文自然语言表演指令。
         *
         * 这是本引擎的核心价值：SmartSegmenter 判出的情绪标签在 Edge 上只能
         * 变成几个百分比，在这里能变成"放声大笑，笑声爽朗有起伏"这种真指令。
         *
         * 指令保持在 60 字以内：官方上限 200 字符，而且 instruction 计入字符
         * 计费，写长了每段都要多掏钱。
         */
        private val EMOTION_INSTRUCTIONS = mapOf(
            "laugh" to "极度开心，放声大笑，笑声爽朗有起伏，语速偏快，充满感染力",
            "cry" to "克制的悲伤，声音轻轻发颤，语速缓慢，气息虚弱断续",
            "shout" to "情绪爆发，大声喊叫，声音洪亮有穿透力，咬字用力",
            "whisper" to "压低声音耳语，气声明显，语速偏慢，像在说秘密",
            "angry" to "怒气冲冲，声音低沉有压迫感，咬字用力，语速偏快",
            "sad" to "低落难过，语速缓慢，尾音下沉，带一丝叹息感",
            "excited" to "兴奋激动，语速快，音调上扬，情绪高涨",
            "cheerful" to "轻松愉快，语调明亮上扬，带笑意",
            "fearful" to "紧张不安，声音发紧压低，语速忽快忽慢，气息不稳",
            "friendly" to "亲切自然，语气温和，像在跟熟人聊天"
        )

        /** 旁白：明确要求平稳叙述，避免模型自己加戏 */
        private const val NARRATION_INSTRUCTION = "平稳客观地叙述，情绪克制，像有声书旁白"

        fun instructionFor(emotion: String, speaker: String): String? {
            EMOTION_INSTRUCTIONS[emotion.lowercase()]?.let { return it }
            if (speaker == "旁白") return NARRATION_INSTRUCTION
            return null
        }

        /** 2026-09-03 逐个实测可用的 25 个官方音色 */
        val VOICES = listOf(
            // 男声
            AiTtsEngine.VoiceInfo("boyinnansheng", "播音男声（沉稳·推荐旁白）", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("cixingnansheng", "磁性男声", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("shenchennanyin", "深沉男音", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("wenrounansheng", "温柔男声", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("wenrougongzi", "温柔公子", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("yuanqinansheng", "元气男声", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("qingniandaxuesheng", "青年大学生", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("shuangkuainansheng", "爽快男声", "zh-CN", "Male"),
            // 女声
            AiTtsEngine.VoiceInfo("linjiajiejie", "邻家姐姐", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("linjiameimei", "邻家妹妹", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zhixingjiejie", "知性姐姐", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("shuangkuaijiejie", "爽快姐姐", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("wenjingxuejie", "文静学姐", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("lengyanyujie", "冷艳御姐", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("youyanvsheng", "优雅女声", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("wenrounvsheng", "温柔女声", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("tianmeinvsheng", "甜美女声", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("elegantgentle-female", "优雅温柔女声（默认）", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("ganliannvsheng", "干练女声", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("qinhenvsheng", "亲和女声", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("huolinvsheng", "活力女声", "zh-CN", "Female"),
            // 少女/童声向
            AiTtsEngine.VoiceInfo("qingchunshaonv", "青春少女", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("jilingshaonv", "机灵少女", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("yuanqishaonv", "元气少女", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("ruanmengnvsheng", "软萌女声", "zh-CN", "Female")
        )
    }
}
