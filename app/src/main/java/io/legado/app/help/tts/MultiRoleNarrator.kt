package io.legado.app.help.tts

/**
 * 本地规则版多人多角色朗读。
 * 无需LLM：抽取引号对话、提示语和性别线索，为同一角色稳定分配音色。
 */
class MultiRoleNarrator(
    private val narratorVoice: String = "zh-CN-YunyangNeural",
    private val defaultVoice: String = "zh-CN-XiaoxiaoNeural"
) {

    data class RoleSegment(
        val text: String,
        val speaker: String,
        val voice: String,
        val emotion: String,
        val dialogue: Boolean
    )

    // 2026-08 实测可用音色池；移除已返回NoAudioReceived的云枫/云野/晓涵/晓梦/晓双
    private val maleVoices = listOf(
        "zh-CN-YunjianNeural", "zh-CN-YunxiNeural", "zh-CN-YunyangNeural"
    )
    private val femaleVoices = listOf(
        "zh-CN-XiaoyiNeural", "zh-CN-XiaoxuanNeural", "zh-CN-XiaoxiaoNeural"
    )
    private val childVoices = listOf("zh-CN-YunxiaNeural", "zh-CN-XiaoyiNeural")
    private enum class Gender { MALE, FEMALE, CHILD, UNKNOWN }
    private val roleVoices = linkedMapOf<String, String>()
    private val roleGenders = linkedMapOf<String, Gender>()
    private val recentSpeakers = ArrayDeque<String>()
    private var unknownTurn = 0

    private val quoteRegex = Regex("""[“「『"]([^”」』"]+)[”」』"]""")
    private val beforeSpeakerRegex = Regex(
        """([\p{IsHan}A-Za-z0-9·]{1,12})(?:低声|轻声|大声|忽然|冷冷|笑着|哭着|怒|厉声|柔声|沉声|喃喃)?""" +
            """(?:说|道|问|答|喊|叫|笑|哭|吼|骂|叹|嘟囔|反问|回应)(?:道)?[：:,，]?\s*$"""
    )
    private val afterSpeakerRegex = Regex(
        """^\s*[，,。.!！?？]?\s*([\p{IsHan}A-Za-z0-9·]{1,12})(?:低声|轻声|大声|笑着|哭着)?""" +
            """(?:说|道|问|答|喊|叫|笑|哭|吼|骂|叹)(?:道)?"""
    )

    fun analyze(paragraph: String): List<RoleSegment> {
        val text = paragraph.trim()
        if (text.isEmpty()) return emptyList()
        val matches = quoteRegex.findAll(text).toList()
        if (matches.isEmpty()) {
            return listOf(roleSegment(text, "旁白", narratorVoice, false))
        }

        val result = mutableListOf<RoleSegment>()
        learnGenderHints(text)
        var cursor = 0
        for ((index, match) in matches.withIndex()) {
            if (match.range.first > cursor) {
                val narration = text.substring(cursor, match.range.first).trim()
                if (narration.isNotEmpty()) {
                    result += roleSegment(narration, "旁白", narratorVoice, false)
                }
            }

            val before = text.substring(0, match.range.first).takeLast(40)
            val afterEnd = if (index + 1 < matches.size) matches[index + 1].range.first else text.length
            val after = text.substring(match.range.last + 1, afterEnd).take(40)
            val explicit = extractSpeaker(before, after)
            val speaker = explicit ?: inferUnknownSpeaker()
            rememberSpeaker(speaker)
            result += roleSegment(match.groupValues[1], speaker, voiceFor(speaker, before + after), true)
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            val tail = text.substring(cursor).trim()
            if (tail.isNotEmpty()) result += roleSegment(tail, "旁白", narratorVoice, false)
        }
        return result.filter { it.text.isNotBlank() }
    }

    private fun roleSegment(
        text: String,
        speaker: String,
        voice: String,
        dialogue: Boolean
    ) = RoleSegment(text, speaker, voice, SmartSegmenter.detectEmotion(text), dialogue)

    private fun extractSpeaker(before: String, after: String): String? {
        beforeSpeakerRegex.find(before)?.groupValues?.getOrNull(1)?.let { return cleanSpeaker(it) }
        afterSpeakerRegex.find(after)?.groupValues?.getOrNull(1)?.let { return cleanSpeaker(it) }
        return null
    }

    private fun cleanSpeaker(raw: String): String? {
        val speaker = raw.trim().removePrefix("只见").removePrefix("那")
        val excluded = setOf("他", "她", "它", "我", "你", "旁白", "众人", "有人")
        return speaker.takeIf { it.length in 1..12 && it !in excluded }
    }

    /** 无名称连续对话：优先在最近两个明确角色间交替 */
    private fun inferUnknownSpeaker(): String {
        val candidates = recentSpeakers.distinct().takeLast(2)
        if (candidates.size == 2) {
            val speaker = candidates[unknownTurn % 2]
            unknownTurn++
            return speaker
        }
        return "角色${(unknownTurn++ % 4) + 1}"
    }

    private fun rememberSpeaker(speaker: String) {
        if (speaker.startsWith("角色")) return
        recentSpeakers.addLast(speaker)
        while (recentSpeakers.size > 6) recentSpeakers.removeFirst()
    }

    /** 从整段叙述中持续学习“角色名 + 性别代词/称谓”，一旦明确后固定。 */
    private fun learnGenderHints(text: String) {
        val names = Regex("[\u4e00-\u9fa5·]{2,6}").findAll(text).map { it.value }.toSet()
        names.forEach { name ->
            val escaped = Regex.escape(name)
            val near = Regex("$escaped.{0,12}(她|姑娘|小姐|夫人|女子|少女|母亲|姐姐|妹妹|女儿)|" +
                "(她|姑娘|小姐|夫人|女子|少女|母亲|姐姐|妹妹|女儿).{0,12}$escaped")
            val maleNear = Regex("$escaped.{0,12}(他|公子|少爷|男子|少年|父亲|哥哥|弟弟|儿子)|" +
                "(他|公子|少爷|男子|少年|父亲|哥哥|弟弟|儿子).{0,12}$escaped")
            val childNear = Regex("$escaped.{0,10}(孩子|小孩|男孩|女孩|童声)|" +
                "(孩子|小孩|男孩|女孩|童声).{0,10}$escaped")
            when {
                childNear.containsMatchIn(text) -> setGender(name, Gender.CHILD)
                near.containsMatchIn(text) -> setGender(name, Gender.FEMALE)
                maleNear.containsMatchIn(text) -> setGender(name, Gender.MALE)
            }
        }
    }

    private fun setGender(speaker: String, gender: Gender) {
        if (gender == Gender.UNKNOWN) return
        val previous = roleGenders[speaker]
        if (previous == null || previous == Gender.UNKNOWN) {
            roleGenders[speaker] = gender
            roleVoices.remove(speaker) // 新证据出现时重新分配正确音色
        }
    }

    private fun detectGender(speaker: String, context: String): Gender {
        roleGenders[speaker]?.takeIf { it != Gender.UNKNOWN }?.let { return it }
        val child = Regex("小孩|孩子|男孩|女孩|少年|少女|童声|小朋友").containsMatchIn(context)
        val female = Regex("她|女士|小姐|姑娘|夫人|母亲|妈妈|奶奶|姐姐|妹妹|女儿|女子").containsMatchIn(context) ||
            speaker.endsWith("娘") || speaker.endsWith("妹") || speaker.endsWith("姐")
        val male = Regex("他|先生|公子|少爷|父亲|爸爸|爷爷|哥哥|弟弟|儿子|男子").containsMatchIn(context)
        return when {
            child -> Gender.CHILD
            female && !male -> Gender.FEMALE
            male && !female -> Gender.MALE
            else -> Gender.UNKNOWN
        }.also { if (it != Gender.UNKNOWN) setGender(speaker, it) }
    }

    private fun voiceFor(speaker: String, context: String): String {
        roleVoices[speaker]?.let { return it }
        val gender = detectGender(speaker, context)
        val pool = when (gender) {
            Gender.CHILD -> childVoices
            Gender.FEMALE -> femaleVoices
            Gender.MALE -> maleVoices
            // 性别不确定时用中性男声，杜绝男性角色被随机分到女声
            Gender.UNKNOWN -> maleVoices
        }
        val index = Math.floorMod(speaker.hashCode(), pool.size)
        val voice = pool[index]
        roleVoices[speaker] = voice
        return voice.ifBlank { defaultVoice }
    }
}
