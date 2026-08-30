package io.legado.app.help.tts

import io.legado.app.data.entities.HttpTTS

/**
 * AI TTS 引擎工厂
 * 根据 HttpTTS.engineType 创建对应引擎实例
 */
object AiTtsEngineFactory {

    private val edgeVoices by lazy {
        listOf(
            AiTtsEngine.VoiceInfo("zh-CN-XiaoxiaoNeural", "晓晓", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaoyiNeural", "晓伊", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-YunjianNeural", "云健", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("zh-CN-YunxiNeural", "云希", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("zh-CN-YunyangNeural", "云扬", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("zh-CN-YunxiaNeural", "云夏", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("zh-CN-YunfengNeural", "云枫", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("zh-CN-YunhaoNeural", "云皓", "zh-CN", "Male"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaochenNeural", "晓辰", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaohanNeural", "晓涵", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaomengNeural", "晓梦", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaomoNeural", "晓墨", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaoqiuNeural", "晓秋", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaoruiNeural", "晓睿", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaoshuangNeural", "晓双", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaoxuanNeural", "晓萱", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaoyanNeural", "晓颜", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-XiaozhenNeural", "晓臻", "zh-CN", "Female"),
            AiTtsEngine.VoiceInfo("zh-CN-YunyeNeural", "云野", "zh-CN", "Male")
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
