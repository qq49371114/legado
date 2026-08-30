package io.legado.app.help.tts

import kotlinx.coroutines.flow.Flow

/**
 * AI TTS 引擎统一接口
 * 支持 Edge-TTS / OpenAI / Azure / CosyVoice 等
 */
interface AiTtsEngine {

    val engineType: String

    /** 是否支持流式合成（边合成边播放） */
    val supportsStreaming: Boolean

    /** 是否支持 SSML 标记 */
    val supportsSsml: Boolean

    /** 单次合成字符上限，0 = 不限 */
    val maxCharPerRequest: Int

    /** 同步合成：返回完整音频流 */
    suspend fun synthesize(
        text: String,
        voice: String?,
        speed: Float,
        pitch: Float? = null,
        options: Map<String, Any> = emptyMap()
    ): ByteArray

    /** 流式合成：返回分块音频（边合成边播放） */
    suspend fun synthesizeStream(
        text: String,
        voice: String?,
        speed: Float,
        options: Map<String, Any> = emptyMap()
    ): Flow<ByteArray>

    /** 获取可用音色列表 */
    suspend fun listVoices(): List<VoiceInfo>

    data class VoiceInfo(
        val id: String,
        val name: String,
        val language: String,
        val gender: String,
        val previewUrl: String? = null
    )

    companion object {
        /** 引擎类型常量 */
        const val TYPE_HTTP = "http"
        const val TYPE_EDGE = "edge"
        const val TYPE_OPENAI = "openai"
        const val TYPE_AZURE = "azure"
        const val TYPE_COSYVOICE = "cosyvoice"
        const val TYPE_ELEVENLABS = "elevenlabs"
        const val TYPE_FISHTTS = "fishtts"
    }
}
