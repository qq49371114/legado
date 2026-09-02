package io.legado.app.help.tts

/**
 * 本地规则版多人多角色朗读。
 * 无需LLM：抽取引号对话、提示语和性别线索，为同一角色稳定分配音色。
 *
 * 说话人抽取要点（2026-09 修复）：
 * 1. 名字用惰性匹配 + 显式剥离修饰语。旧版贪婪匹配会把动词吞进名字
 *    （"娘笑道" → 说话人被识别成"娘笑"），导致称谓判性别彻底失效。
 * 2. 性别证据分三级：名字里的称谓 > 紧邻代词 > 上下文散落代词加权计分。
 *    旧版把整段代词一锅炖，"娘笑道…他放下柴刀"会把娘判成男声。
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

    // 2026-09-02 逐个 WebSocket 实测可用的音色；已下架音色会返回 Unsupported voice
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

    private companion object {
        /** 说话动词，长词优先，避免"反问"被"问"截断 */
        val SPEAK_VERBS = listOf(
            "嘟囔", "反问", "回应", "低语", "接口", "开口", "应道",
            "说", "道", "问", "答", "喊", "叫", "笑", "哭", "吼", "骂", "叹"
        ).sortedByDescending { it.length }

        /** 名字与说话动词之间可能夹的神态/动作/语气修饰语，必须剥离 */
        val MODIFIERS = listOf(
            "叹了口气", "沉默片刻", "犹豫了一下", "红着脸", "皱着眉", "想了想",
            "看着他", "看着她", "低声", "轻声", "大声", "忽然", "突然", "冷冷",
            "淡淡", "笑着", "哭着", "厉声", "柔声", "沉声", "喃喃", "连忙",
            "急忙", "缓缓", "慢慢", "皱眉", "点头", "摇头", "抬头", "低头",
            "冷笑", "苦笑", "微笑", "大笑", "转身", "回头", "接着", "随即",
            "闻言", "这才", "一边", "怒", "又", "才", "也", "却", "便", "就", "还", "再"
        ).sortedByDescending { it.length }

        /** 名字前可能带的指示词 */
        val NAME_PREFIXES = listOf("只见", "却见", "但见", "那", "这")

        val PRONOUNS = setOf(
            "他", "她", "它", "我", "你", "您", "他们", "她们",
            "众人", "有人", "那人", "此人", "两人", "大家"
        )

        /** 名字里带这些称谓即可确定女性；长词优先 */
        val FEMALE_TITLES = listOf(
            "夫人", "小姐", "姑娘", "太太", "丫头", "侍女", "尼姑", "妇人",
            "女子", "女人", "女孩", "少女", "老板娘", "娘", "妈", "母", "婆",
            "嫂", "姐", "妹", "姑", "姨", "妃", "嫔", "氏", "奶", "媳", "妻", "婢"
        ).sortedByDescending { it.length }

        /** 名字里带这些称谓即可确定男性；长词优先 */
        val MALE_TITLES = listOf(
            "将军", "和尚", "道士", "书生", "少年", "老头", "老者", "师父",
            "掌门", "统领", "太监", "先生", "公子", "少爷", "汉子", "男子",
            "男人", "公公", "爹", "爸", "父", "爷", "叔", "伯", "兄", "哥",
            "弟", "君", "侯", "郎", "小子", "儿子"
        ).sortedByDescending { it.length }

        val CHILD_TITLES = listOf("小孩", "孩子", "男孩", "女孩", "娃", "童", "幼")
    }

    private val quoteRegex = Regex("""[“「『"]([^”」』"]+)[”」』"]""")

    // 名字用惰性匹配（{1,14}?），贪婪会把说话动词吞进名字
    private val speakerTail = "(?:${MODIFIERS.joinToString("|")})?" +
        "(?:${SPEAK_VERBS.joinToString("|")})(?:道)?"
    private val beforeSpeakerRegex =
        Regex("""([\p{IsHan}A-Za-z0-9·]{1,14}?)$speakerTail[：:,，]?\s*$""")
    private val afterSpeakerRegex =
        Regex("""^\s*[，,。.!！?？]?\s*([\p{IsHan}A-Za-z0-9·]{1,14}?)$speakerTail""")

    /** 紧贴引号前的代词权重最高，比段落里随便一个代词可信得多 */
    private val nearFemaleRegex = Regex("""(她|姑娘|小姐|夫人|女子|少女|妇人|婢女)[^。！？；]{0,8}$""")
    private val nearMaleRegex = Regex("""(他(?!们)|公子|少爷|男子|少年|汉子|老头)[^。！？；]{0,8}$""")
    private val femaleCountRegex = Regex("她")
    private val maleCountRegex = Regex("他(?!们)")

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
            val afterEnd =
                if (index + 1 < matches.size) matches[index + 1].range.first else text.length
            val after = text.substring(match.range.last + 1, afterEnd).take(40)

            val extracted = extractSpeaker(before, after)
            val speaker = when {
                extracted == null -> inferUnknownSpeaker()
                // 说话人写成代词（"他说"）时回溯本段最近出现的实名
                extracted.pronoun -> resolvePronounSpeaker(before, extracted.name)
                else -> extracted.name
            }
            rememberSpeaker(speaker)
            result += roleSegment(
                match.groupValues[1],
                speaker,
                voiceFor(speaker, before, after, extracted?.takeIf { it.pronoun }?.name),
                true
            )
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

    private data class Extracted(val name: String, val pronoun: Boolean)

    private fun extractSpeaker(before: String, after: String): Extracted? {
        beforeSpeakerRegex.find(before)?.groupValues?.getOrNull(1)
            ?.let { raw -> cleanSpeaker(raw)?.let { return it } }
        afterSpeakerRegex.find(after)?.groupValues?.getOrNull(1)
            ?.let { raw -> cleanSpeaker(raw)?.let { return it } }
        return null
    }

    /** 反复剥离尾部修饰语，直到只剩纯名字 */
    private fun stripModifiers(raw: String): String {
        var name = raw
        var changed = true
        while (changed && name.isNotEmpty()) {
            changed = false
            for (mod in MODIFIERS) {
                if (name.length > mod.length && name.endsWith(mod)) {
                    name = name.dropLast(mod.length)
                    changed = true
                    break
                }
            }
        }
        return name
    }

    private fun cleanSpeaker(raw: String): Extracted? {
        var speaker = stripModifiers(raw.trim()).trim(',', '，', '。', '.', '!', '！', '?', '？', '、', ' ')
        for (prefix in NAME_PREFIXES) {
            if (speaker.length > prefix.length && speaker.startsWith(prefix)) {
                speaker = speaker.removePrefix(prefix)
                break
            }
        }
        if (speaker.isEmpty()) return null
        if (speaker in PRONOUNS) return Extracted(speaker, true)
        if (speaker == "旁白") return null
        return speaker.takeIf { it.length in 1..12 }?.let { Extracted(it, false) }
    }

    /** "他说/她说"：回溯本段前文最近出现的实名，找不到就沿用最近说话人 */
    private fun resolvePronounSpeaker(before: String, pronoun: String): String {
        val candidate = Regex("""[\p{IsHan}]{2,4}(?=[^。！？；]{0,6}(?:${SPEAK_VERBS.joinToString("|")}))""")
            .findAll(before)
            .map { it.value }
            .lastOrNull { it !in PRONOUNS }
        if (candidate != null) return candidate
        val expectFemale = pronoun == "她"
        val known = recentSpeakers.lastOrNull { speaker ->
            val g = roleGenders[speaker]
            if (expectFemale) g == Gender.FEMALE else g == Gender.MALE
        }
        return known ?: recentSpeakers.lastOrNull() ?: pronoun
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
        if (speaker.startsWith("角色") || speaker in PRONOUNS) return
        recentSpeakers.addLast(speaker)
        while (recentSpeakers.size > 6) recentSpeakers.removeFirst()
    }

    /** 从整段叙述中持续学习"角色名 + 性别代词/称谓"，一旦明确后固定。 */
    private fun learnGenderHints(text: String) {
        val names = Regex("[\\u4e00-\\u9fa5·]{2,6}").findAll(text).map { it.value }.toSet()
        names.forEach { name ->
            if (name in PRONOUNS) return@forEach
            titleGender(name)?.let {
                setGender(name, it)
                return@forEach
            }
            val escaped = Regex.escape(name)
            val female = Regex(
                "$escaped.{0,12}(她|姑娘|小姐|夫人|女子|少女|母亲|姐姐|妹妹|女儿)|" +
                    "(她|姑娘|小姐|夫人|女子|少女|母亲|姐姐|妹妹|女儿).{0,12}$escaped"
            )
            val male = Regex(
                "$escaped.{0,12}(他|公子|少爷|男子|少年|父亲|哥哥|弟弟|儿子)|" +
                    "(他|公子|少爷|男子|少年|父亲|哥哥|弟弟|儿子).{0,12}$escaped"
            )
            val child = Regex(
                "$escaped.{0,10}(孩子|小孩|男孩|女孩|童声)|" +
                    "(孩子|小孩|男孩|女孩|童声).{0,10}$escaped"
            )
            when {
                child.containsMatchIn(text) -> setGender(name, Gender.CHILD)
                female.containsMatchIn(text) -> setGender(name, Gender.FEMALE)
                male.containsMatchIn(text) -> setGender(name, Gender.MALE)
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

    /**
     * 称谓判定：名字里写明的称谓是最强证据。
     * 结尾命中优先（"老板娘"结尾是娘 → 女），其次长词优先（避免"女子"里的"子"判成男）。
     */
    private fun titleGender(speaker: String): Gender? {
        CHILD_TITLES.firstOrNull { it in speaker }?.let { return Gender.CHILD }
        var best: Pair<Gender, Int>? = null
        fun consider(gender: Gender, title: String) {
            if (title !in speaker) return
            val score = title.length * 2 + if (speaker.endsWith(title)) 1000 else 0
            if (best == null || score > best!!.second) best = gender to score
        }
        FEMALE_TITLES.forEach { consider(Gender.FEMALE, it) }
        MALE_TITLES.forEach { consider(Gender.MALE, it) }
        return best?.first
    }

    private fun detectGender(
        speaker: String,
        before: String,
        after: String,
        pronoun: String?
    ): Gender {
        roleGenders[speaker]?.takeIf { it != Gender.UNKNOWN }?.let { return it }
        titleGender(speaker)?.let {
            setGender(speaker, it)
            return it
        }
        when (pronoun) {
            "她" -> {
                setGender(speaker, Gender.FEMALE)
                return Gender.FEMALE
            }
            "他" -> {
                setGender(speaker, Gender.MALE)
                return Gender.MALE
            }
        }
        val context = before + after
        var female = if (nearFemaleRegex.containsMatchIn(before)) 3 else 0
        var male = if (nearMaleRegex.containsMatchIn(before)) 3 else 0
        female += femaleCountRegex.findAll(context).count()
        male += maleCountRegex.findAll(context).count()
        return when {
            female > male -> Gender.FEMALE
            male > female -> Gender.MALE
            else -> Gender.UNKNOWN
        }.also { if (it != Gender.UNKNOWN) setGender(speaker, it) }
    }

    private fun voiceFor(
        speaker: String,
        before: String,
        after: String,
        pronoun: String?
    ): String {
        roleVoices[speaker]?.let { return it }
        val pool = when (detectGender(speaker, before, after, pronoun)) {
            Gender.CHILD -> childVoices
            Gender.FEMALE -> femaleVoices
            Gender.MALE -> maleVoices
            // 性别不确定时用中性男声，杜绝男性角色被随机分到女声
            Gender.UNKNOWN -> maleVoices
        }
        val index = Math.floorMod(speaker.hashCode(), pool.size)
        val voice = pool[index].ifBlank { defaultVoice }
        roleVoices[speaker] = voice
        return voice
    }
}
