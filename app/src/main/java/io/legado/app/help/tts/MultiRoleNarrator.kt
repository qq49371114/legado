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
 *
 * 音色稳定性重构（2026-09-03，修"角色声音一直在轮换"）：
 * 3. 角色档案改为按书持久化（ROLE_REGISTRY）。旧版每章都 new 一个
 *    MultiRoleNarrator，roleVoices 随之清空，下一章同一个人重新算性别、
 *    重新分配音色 —— 这是跨章换声的根因。
 * 4. 名字变体归一（canonicalKey）。"李大爷"/"大爷"/"那李大爷" 旧版是三个
 *    不同 key，hashCode 不同 → 三个音色。现在按包含关系归并到同一档案。
 * 5. 音色一经分配只在"性别由未知升级为已知"时才改一次，其余任何情况都不动。
 *    旧版 setGender 里无条件 roleVoices.remove()，每来一条新证据就重投一次。
 * 6. 无名对话的交替改为段内局部交替，不再用全局计数器 —— 全局计数器会被
 *    有名对话打乱相位，导致同一个人这句男声下句女声。
 */
class MultiRoleNarrator(
    private val narratorVoice: String = "zh-CN-YunyangNeural",
    private val defaultVoice: String = "zh-CN-XiaoxiaoNeural",
    /** 书籍标识：同一本书跨章共享角色档案，换书自动隔离 */
    private val bookKey: String = ""
) {

    data class RoleSegment(
        val text: String,
        val speaker: String,
        val voice: String,
        val emotion: String,
        val dialogue: Boolean
    )

    /** 角色档案：性别 + 已分配音色，按书持久化 */
    private data class RoleProfile(var gender: Gender, var voice: String?)

    // 2026-09-02 逐个 WebSocket 实测可用的音色；已下架音色会返回 Unsupported voice
    private val maleVoices = listOf(
        "zh-CN-YunjianNeural", "zh-CN-YunxiNeural", "zh-CN-YunyangNeural"
    )
    private val femaleVoices = listOf(
        "zh-CN-XiaoyiNeural", "zh-CN-XiaoxuanNeural", "zh-CN-XiaoxiaoNeural"
    )
    private val childVoices = listOf("zh-CN-YunxiaNeural", "zh-CN-XiaoyiNeural")

    private enum class Gender { MALE, FEMALE, CHILD, UNKNOWN }

    /** 本书角色档案（跨章共享的那一份） */
    private val roles: MutableMap<String, RoleProfile> = registryFor(bookKey)

    private val recentSpeakers = ArrayDeque<String>()

    private companion object {
        /**
         * 按书缓存的角色档案。朗读服务每章都会重建 Narrator，
         * 档案必须活得比 Narrator 长，否则同一个角色每章换一次声音。
         * 只保留最近 4 本书，避免长期占内存。
         */
        private val ROLE_REGISTRY = LinkedHashMap<String, MutableMap<String, RoleProfile>>()
        private const val MAX_BOOKS_CACHED = 4

        fun registryFor(bookKey: String): MutableMap<String, RoleProfile> {
            synchronized(ROLE_REGISTRY) {
                val existing = ROLE_REGISTRY.remove(bookKey)
                val map = existing ?: linkedMapOf()
                ROLE_REGISTRY[bookKey] = map          // 重新插入 = 标记为最近使用
                while (ROLE_REGISTRY.size > MAX_BOOKS_CACHED) {
                    val oldest = ROLE_REGISTRY.keys.firstOrNull() ?: break
                    ROLE_REGISTRY.remove(oldest)
                }
                return map
            }
        }

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
        // 段内无名对话的交替相位：只在本段内有效，不用全局计数器
        var localTurn = 0

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
                extracted == null -> inferUnknownSpeaker(localTurn++)
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
        return speaker.takeIf { it.length in 1..12 }
            ?.let { Extracted(canonicalKey(it), false) }
    }

    /**
     * 名字变体归一：把"大爷"/"李大爷"/"李大爷儿"合并到已登记的同一角色。
     * 旧版不做归一，同一个人的不同称呼各自 hashCode，音色自然各不相同。
     * 规则：与已知角色名互为前缀/后缀且公共部分 ≥2 字 → 视为同一人，
     * 统一用更长的那个作为档案键（信息更完整）。
     */
    private fun canonicalKey(name: String): String {
        if (name.length < 2) return name
        if (roles.containsKey(name)) return name
        val hit = roles.keys.firstOrNull { known ->
            known.length >= 2 &&
                (known.endsWith(name) || known.startsWith(name) ||
                    name.endsWith(known) || name.startsWith(known))
        } ?: return name
        return if (name.length > hit.length) {
            // 新名字更完整：把旧档案迁移到新键，避免两份档案各持一个音色
            roles.remove(hit)?.let { roles[name] = it }
            name
        } else {
            hit
        }
    }

    /** "他说/她说"：回溯本段前文最近出现的实名，找不到就沿用最近说话人 */
    private fun resolvePronounSpeaker(before: String, pronoun: String): String {
        val candidate = Regex("""[\p{IsHan}]{2,4}(?=[^。！？；]{0,6}(?:${SPEAK_VERBS.joinToString("|")}))""")
            .findAll(before)
            .map { it.value }
            .lastOrNull { it !in PRONOUNS }
        if (candidate != null) return canonicalKey(candidate)
        val expectFemale = pronoun == "她"
        val known = recentSpeakers.lastOrNull { speaker ->
            val g = roles[speaker]?.gender
            if (expectFemale) g == Gender.FEMALE else g == Gender.MALE
        }
        return known ?: recentSpeakers.lastOrNull() ?: pronoun
    }

    /**
     * 无名称连续对话：在最近两个明确角色间交替。
     * turn 由调用方按"本段第几个无名引号"传入 —— 段内交替是对的，
     * 但绝不能用跨段全局计数器，否则有名对话会把相位打乱，
     * 表现出来就是同一个角色上一句男声下一句女声。
     */
    private fun inferUnknownSpeaker(turn: Int): String {
        val candidates = recentSpeakers.distinct().takeLast(2)
        if (candidates.size == 2) return candidates[turn % 2]
        return candidates.lastOrNull() ?: "角色1"
    }

    private fun rememberSpeaker(speaker: String) {
        if (speaker.startsWith("角色") || speaker in PRONOUNS) return
        recentSpeakers.addLast(speaker)
        while (recentSpeakers.size > 6) recentSpeakers.removeFirst()
    }

    /**
     * 从整段叙述中学习"角色名 + 性别代词/称谓"。
     *
     * 旧版用 Regex("[\u4e00-\u9fa5·]{2,6}") 把整段切成任意 6 字块当"名字"，
     * 切出来的是"张三笑道""起来笑得"这类垃圾，真正的说话人反而不在集合里，
     * 等于白跑一遍还污染档案。现在只对已登记角色和本段抽出的说话人做学习。
     */
    private fun learnGenderHints(text: String) {
        val names = LinkedHashSet<String>()
        names += roles.keys
        names += recentSpeakers
        // 本段内出现的"名字+说话动词"，取动词前的 2-4 字
        Regex("""([\p{IsHan}]{2,4})(?:${SPEAK_VERBS.joinToString("|")})(?:道)?[：:，,]""")
            .findAll(text)
            .forEach { names += it.groupValues[1] }

        names.forEach { name ->
            if (name.isBlank() || name in PRONOUNS || name == "旁白") return@forEach
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

    /**
     * 写入性别。只允许"未知 → 已知"这一次升级，且仅在这种情况下才丢弃已分配音色。
     * 旧版每次 setGender 都无条件 roleVoices.remove()，一段话里出现几次新证据
     * 就重投几次音色 —— 这是段内换声的直接原因。
     */
    private fun setGender(speaker: String, gender: Gender) {
        if (gender == Gender.UNKNOWN) return
        val profile = roles[speaker]
        if (profile == null) {
            roles[speaker] = RoleProfile(gender, null)
            return
        }
        if (profile.gender == Gender.UNKNOWN) {
            profile.gender = gender
            profile.voice = null      // 仅此一次：性别刚确定，重投一次正确音色
        }
        // 已有明确性别：任何新证据都不再改动，宁可错一个也不要来回换声
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
        roles[speaker]?.gender?.takeIf { it != Gender.UNKNOWN }?.let { return it }
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

    /**
     * 取角色音色。档案里已有就直接用，永不重算 —— 这是"声音固定"的关键。
     * 只有当档案不存在、或性别刚从未知升级为已知时才会走到分配逻辑。
     */
    private fun voiceFor(
        speaker: String,
        before: String,
        after: String,
        pronoun: String?
    ): String {
        roles[speaker]?.voice?.let { return it }
        val gender = detectGender(speaker, before, after, pronoun)
        val pool = when (gender) {
            Gender.CHILD -> childVoices
            Gender.FEMALE -> femaleVoices
            Gender.MALE -> maleVoices
            // 性别不确定时用中性男声，杜绝男性角色被随机分到女声
            Gender.UNKNOWN -> maleVoices
        }
        // 用名字的稳定哈希取池内下标：同名同书永远拿到同一个音色。
        // String.hashCode 在 JVM 上有规范定义，跨进程稳定，可以放心用。
        val index = Math.floorMod(stableHash(speaker), pool.size)
        val voice = pool[index].ifBlank { defaultVoice }
        val profile = roles.getOrPut(speaker) { RoleProfile(gender, null) }
        profile.voice = voice
        return voice
    }

    /** 与 String.hashCode 同算法，显式写出以强调"必须稳定" */
    private fun stableHash(s: String): Int {
        var h = 0
        for (c in s) h = 31 * h + c.code
        return h
    }
}
