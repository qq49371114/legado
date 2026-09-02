package io.legado.app.help.tts

import io.legado.app.data.entities.HttpTTS

/**
 * AI TTS 引擎工厂
 * 根据 HttpTTS.engineType 创建对应引擎实例
 */
object AiTtsEngineFactory {

    // 2026-09-02 逐个 WebSocket 实测通过的音色（其余已被微软下架，返回 Unsupported voice）。
    // 列表里出现下架音色会导致用户选了却静默回退到主音色，表现为"旁白音色切换没反应"。
    private val edgeVoices by lazy {
        listOf(
            AiTtsEngine.VoiceInfo("zh-CN-YunyangNeural", "云扬（男·沉稳播音）", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("zh-CN-YunjianNeural", "云健（男·浑厚有力）", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("zh-CN-YunxiNeural", "云希（男·清亮年轻）", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("zh-CN-YunxiaNeural", "云夏（男·少年童声）", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaoxiaoNeural", "晓晓（女·温柔标准）", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaoyiNeural", "晓伊（女·活泼少女）", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaoxuanNeural", "晓萱（女·干练成熟）", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-liaoning-XiaobeiNeural", "晓北（女·东北话）", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-shaanxi-XiaoniNeural", "晓妮（女·陕西话）", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-HK-HiuMaanNeural", "晓曼（女·粤语）", "zh-HK", "Female"),
            AiTtsEngine.VoiceInfo("zh-TW-HsiaoChenNeural", "晓臻（女·台湾腔）", "zh-TW", "Female")
        )
    }

    private val openaiVoices by lazy {
        listOf(
            AiTtsEngine.VoiceInfo("alloy", "Alloy", "en", "Neutral"),
            AiTtsEngine.VoiceInfo("echo", "Echo", "en", "Male"),
            AiTtsEngine.VoiceInfo("fable", "Fable", "en", "Neutral"),
            AiTtsEngine.VoiceInfo("onyx", "Onyx", "en", "Male"),
            AiTtsEngine.VoiceInfo("nova", "Nova", "en", "Female"),
            AiTtsEngine.VoiceInfo("shimmer", "Shimmer", "en", "Female")
        )
    }

    /**
     * 修复预设AI引擎：即使旧版本已把隐藏字段保存坏，也能按固定ID恢复。
     * 返回可直接写回数据库的完整对象。
     */
    fun normalizePreset(httpTTS: HttpTTS): HttpTTS {
        val edgeVoice = when (httpTTS.id) {
            -200L -> "zh-CN-XiaoxiaoNeural"
            -201L -> "zh-CN-YunjianNeural"
            -202L -> "zh-CN-XiaoyiNeural"
            -203L -> "zh-CN-YunxiNeural"
            -204L -> "zh-CN-YunyangNeural"
            -205L -> "zh-CN-XiaohanNeural"
            else -> null
        }
        return if (edgeVoice != null) {
            httpTTS.copy(
                engineType = AiTtsEngine.TYPE_EDGE,
                voiceName = edgeVoice,
                apiFormat = "audio-24khz-48kbitrate-mono-mp3",
                streamMode = false,
                ssmlSupport = true,
                maxCharLimit = 5000
            )
        } else {
            httpTTS
        }
    }

    fun create(httpTTS: HttpTTS): AiTtsEngine {
        val normalized = normalizePreset(httpTTS)
        val engineType = normalized.engineType.ifBlank { AiTtsEngine.TYPE_HTTP }
        return when (engineType) {
            AiTtsEngine.TYPE_EDGE -> EdgeTtsEngine(normalized)
            AiTtsEngine.TYPE_OPENAI -> OpenAiTtsEngine(normalized)
            AiTtsEngine.TYPE_AZURE -> AzureTtsEngine(normalized)
            AiTtsEngine.TYPE_COSYVOICE -> CosyVoiceTtsEngine(normalized)
            else -> EdgeTtsEngine(normalized)
        }
    }

    /** 判断是否为 AI 引擎（预设ID即使字段被旧版破坏，也仍识别为AI） */
    fun isAiEngine(httpTTS: HttpTTS): Boolean {
        val normalized = normalizePreset(httpTTS)
        val type = normalized.engineType.ifBlank { AiTtsEngine.TYPE_HTTP }
        return type != AiTtsEngine.TYPE_HTTP
    }

    /** 获取引擎预设音色 */
    fun getVoices(engineType: String): List<AiTtsEngine.VoiceInfo> {
        return when (engineType) {
            AiTtsEngine.TYPE_EDGE -> edgeVoices
            AiTtsEngine.TYPE_OPENAI -> openaiVoices
            else -> emptyList()
        }
    }
}
