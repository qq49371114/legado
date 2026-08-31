package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.jayway.jsonpath.DocumentContext
import io.legado.app.utils.GSON
import io.legado.app.utils.jsonPath
import io.legado.app.utils.readLong
import io.legado.app.utils.readString

/**
 * 在线朗读引擎
 * 支持 AI TTS 引擎 (Edge/OpenAI/Azure/CosyVoice)
 */
@Entity(tableName = "httpTTS")
data class HttpTTS(
    @PrimaryKey
    val id: Long = System.currentTimeMillis(),
    var name: String = "",
    var url: String = "",
    var contentType: String? = null,
    @ColumnInfo(defaultValue = "0")
    override var concurrentRate: String? = "0",
    override var loginUrl: String? = null,
    override var loginUi: String? = null,
    override var header: String? = null,
    override var jsLib: String? = null,
    @ColumnInfo(defaultValue = "0")
    override var enabledCookieJar: Boolean? = false,
    var loginCheckJs: String? = null,
    @ColumnInfo(defaultValue = "0")
    var lastUpdateTime: Long = System.currentTimeMillis(),
    // ===== AI TTS 扩展字段 =====
    /** 引擎类型: http(传统) | edge | openai | azure | cosyvoice | elevenlabs */
    @ColumnInfo(defaultValue = "http")
    var engineType: String = "http",
    /** 模型选择: tts-1/tts-1-hd 等 */
    var voiceModel: String? = null,
    /** 音色: alloy/echo/zh-CN-XiaoxiaoNeural 等 */
    var voiceName: String? = null,
    /** 输出格式: mp3/wav/opus/flac */
    @ColumnInfo(defaultValue = "mp3")
    var apiFormat: String = "mp3",
    /** 是否流式合成（边合成边播放） */
    @ColumnInfo(defaultValue = "0")
    var streamMode: Boolean = false,
    /** 是否支持SSML标记 */
    @ColumnInfo(defaultValue = "0")
    var ssmlSupport: Boolean = false,
    /** 单次合成字符上限(0=不限) */
    @ColumnInfo(defaultValue = "0")
    var maxCharLimit: Int = 0,
    /** 语速范围 */
    var speedRange: String? = null,
    /** 音调范围 */
    var pitchRange: String? = null,
    /** 情感标签: cheerful/sad/angry/excited 等 (JSON数组) */
    var emotionTags: String? = null,
    /** 自动多人多角色朗读 */
    @ColumnInfo(defaultValue = "0")
    var multiRoleEnabled: Boolean = false,
    /** 旁白音色 */
    var narratorVoice: String? = null,
    /** AI有声剧：自动背景氛围 */
    @ColumnInfo(defaultValue = "0")
    var audioDramaEnabled: Boolean = false,
    /** 背景音量百分比 */
    @ColumnInfo(defaultValue = "25")
    var backgroundVolume: Int = 25,
    /** 场景最短持续秒数 */
    @ColumnInfo(defaultValue = "45")
    var sceneHoldSeconds: Int = 45,
    /** 语音播放时自动压低背景 */
    @ColumnInfo(defaultValue = "1")
    var duckBackground: Boolean = true
) : BaseSource {

    override fun getTag(): String {
        return name
    }

    override fun getKey(): String {
        return "httpTts:$id"
    }

    @Suppress("MemberVisibilityCanBePrivate")
    companion object {

        fun fromJsonDoc(doc: DocumentContext): Result<HttpTTS> {
            return kotlin.runCatching {
                val loginUi = doc.read<Any>("$.loginUi")
                HttpTTS(
                    id = doc.readLong("$.id") ?: System.currentTimeMillis(),
                    name = doc.readString("$.name")!!,
                    url = doc.readString("$.url")!!,
                    contentType = doc.readString("$.contentType"),
                    concurrentRate = doc.readString("$.concurrentRate"),
                    loginUrl = doc.readString("$.loginUrl"),
                    loginUi = if (loginUi is List<*>) GSON.toJson(loginUi) else loginUi?.toString(),
                    header = doc.readString("$.header"),
                    loginCheckJs = doc.readString("$.loginCheckJs"),
                    // AI TTS 扩展字段
                    engineType = doc.readString("$.engineType") ?: "http",
                    voiceModel = doc.readString("$.voiceModel"),
                    voiceName = doc.readString("$.voiceName"),
                    apiFormat = doc.readString("$.apiFormat") ?: "mp3",
                    streamMode = doc.read<Any?>("$.streamMode")?.let {
                        when (it) {
                            is Boolean -> it
                            is Number -> it.toInt() != 0
                            else -> it.toString().toBoolean()
                        }
                    } ?: false,
                    ssmlSupport = doc.read<Any?>("$.ssmlSupport")?.let {
                        when (it) {
                            is Boolean -> it
                            is Number -> it.toInt() != 0
                            else -> it.toString().toBoolean()
                        }
                    } ?: false,
                    maxCharLimit = doc.read<Any?>("$.maxCharLimit")?.let {
                        when (it) {
                            is Number -> it.toInt()
                            else -> it.toString().toIntOrNull() ?: 0
                        }
                    } ?: 0,
                    speedRange = doc.readString("$.speedRange"),
                    pitchRange = doc.readString("$.pitchRange"),
                    emotionTags = doc.readString("$.emotionTags"),
                    multiRoleEnabled = doc.read<Any?>("$.multiRoleEnabled")?.let {
                        when (it) {
                            is Boolean -> it
                            is Number -> it.toInt() != 0
                            else -> it.toString().toBoolean()
                        }
                    } ?: false,
                    narratorVoice = doc.readString("$.narratorVoice"),
                    audioDramaEnabled = doc.read<Any?>("$.audioDramaEnabled")?.let {
                        when (it) {
                            is Boolean -> it
                            is Number -> it.toInt() != 0
                            else -> it.toString().toBoolean()
                        }
                    } ?: false,
                    backgroundVolume = doc.read<Any?>("$.backgroundVolume")?.toString()?.toIntOrNull() ?: 25,
                    sceneHoldSeconds = doc.read<Any?>("$.sceneHoldSeconds")?.toString()?.toIntOrNull() ?: 45,
                    duckBackground = doc.read<Any?>("$.duckBackground")?.let {
                        when (it) {
                            is Boolean -> it
                            is Number -> it.toInt() != 0
                            else -> it.toString().toBoolean()
                        }
                    } ?: true
                )
            }
        }

        fun fromJson(json: String): Result<HttpTTS> {
            return fromJsonDoc(jsonPath.parse(json))
        }

        fun fromJsonArray(jsonArray: String): Result<ArrayList<HttpTTS>> {
            return kotlin.runCatching {
                val sources = arrayListOf<HttpTTS>()
                val doc = jsonPath.parse(jsonArray).read<List<*>>("$")
                doc.forEach {
                    val jsonItem = jsonPath.parse(it)
                    fromJsonDoc(jsonItem).getOrThrow().let { source ->
                        sources.add(source)
                    }
                }
                return@runCatching sources
            }
        }

    }

}