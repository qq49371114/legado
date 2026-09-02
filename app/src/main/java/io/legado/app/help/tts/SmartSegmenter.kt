package io.legado.app.help.tts

import io.legado.app.constant.AppPattern
import java.util.regex.Pattern

/**
 * 智能分段 + 情感标注
 * 1. 按语义分段（不仅按\n切）
 * 2. 合并过短句、拆分过长段
 * 3. 保留对话引号完整性
 * 4. 自动情感标注（给支持SSML的引擎用）
 *
 * 情感标注重构（2026-09-03）：
 * 旧版对整段只给一个情感标签，且关键词表里混进了"打""气""杀"这种
 * 在中文里极高频的普通字（"打开""天气""杀青"都会命中），导致绝大多数
 * 段落被误判成 angry，再叠加只有 ±7% 的微弱 prosody，听起来就是平读背书。
 *
 * 新版：
 * - 关键词收紧为真正表达情绪的词/词组，去掉单字高频误命中项
 * - 新增 laugh / cry / shout / whisper 四个强表现力标签
 * - 提供 emotionClauses()：把一段话按情绪切成子句，每个子句独立合成，
 *   这是 Edge 免费端点上唯一能做出句内情绪起伏的手段
 *   （实测 mstts:express-as / break / emphasis 全被拒，>2 段 prosody 也被拒）
 */
object SmartSegmenter {

    /** 句子结束标点 */
    private val sentenceEnd = charArrayOf('。', '！', '？', '!', '?', '…', '\n')

    /** 段落结束标点 */
    private val paragraphEnd = charArrayOf('\n', '\r')

    /** 引号：开闭必须按同一索引成对，原版三项都写成了同一个 ASCII 双引号 */
    private val openQuotes = charArrayOf('“', '「', '『', '"')
    private val closeQuotes = charArrayOf('”', '」', '』', '"')

    /** 单段最大字符数 */
    private const val MAX_SEGMENT_LENGTH = 500

    /** 单段最小字符数（低于此合并到上一段） */
    private const val MIN_SEGMENT_LENGTH = 10

    /** 情绪子句最短长度：太短会造成大量微小请求，得不偿失 */
    private const val MIN_CLAUSE_LENGTH = 5

    /** 一段话最多切成几个情绪子句，防止请求数爆炸 */
    private const val MAX_CLAUSES_PER_SEGMENT = 4

    /**
     * 按语义智能分段
     * @param content 原始正文
     * @param maxLen 单段最大字符数（默认500）
     * @return 分段列表
     */
    fun segment(content: String, maxLen: Int = MAX_SEGMENT_LENGTH): List<String> {
        if (content.isBlank()) return emptyList()

        // 第一步：按段落分隔符初步分段
        val paragraphs = content.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // 第二步：合并过短段落
        val merged = mutableListOf<String>()
        for (para in paragraphs) {
            if (merged.isNotEmpty() && para.length < MIN_SEGMENT_LENGTH) {
                // 短段落合并到上一段
                val last = merged.removeAt(merged.lastIndex)
                merged.add("$last$para")
            } else {
                merged.add(para)
            }
        }

        // 第三步：拆分过长段落
        val result = mutableListOf<String>()
        for (para in merged) {
            if (para.length <= maxLen) {
                result.add(para)
            } else {
                result.addAll(splitLongParagraph(para, maxLen))
            }
        }

        // 第四步：过滤纯标点/不可读内容
        return result
            .map { it.replace(AppPattern.notReadAloudRegex, "").trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 拆分过长段落
     * 在句号/问号/感叹号/引号闭合处切分，保证语义完整
     */
    private fun splitLongParagraph(text: String, maxLen: Int): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var lastSplit = 0

        for (i in text.indices) {
            val c = text[i]

            // 检查是否为句子结束位置
            if (c in sentenceEnd) {
                // 检查引号是否闭合
                if (isQuoteBalanced(text, start, i + 1)) {
                    lastSplit = i + 1
                }
                // 达到长度限制，在最近的句子结束处切分
                if (i - start >= maxLen && lastSplit > start) {
                    result.add(text.substring(start, lastSplit).trim())
                    start = lastSplit
                    lastSplit = start
                }
            }
        }

        // 添加最后一段
        if (start < text.length) {
            result.add(text.substring(start).trim())
        }

        return result
    }

    /**
     * 检查引号是否平衡（用于确保不在引号内部切分段落）
     */
    private fun isQuoteBalanced(text: String, start: Int, end: Int): Boolean {
        var openCount = 0
        for (i in start until end) {
            val c = text[i]
            if (c in openQuotes) openCount++
            else if (c in closeQuotes) openCount--
        }
        return openCount <= 0
    }

    // ---------------------------------------------------------------- 情感

    /** 情绪子句：文本 + 该子句独有的情绪标签 */
    data class Clause(val text: String, val emotion: String)

    /** 拟声笑：命中就必须夸张处理，否则"哈哈大笑"会被平平背出来 */
    private val laughWords = listOf(
        "哈哈", "呵呵", "嘿嘿", "嘻嘻", "咯咯", "哈！", "大笑", "狂笑",
        "爆笑", "捧腹", "笑得", "笑起来", "笑出声"
    )

    /** 哭腔：语速与音高都要明显压下去 */
    private val cryWords = listOf(
        "呜呜", "哇哇", "泣不成声", "痛哭", "大哭", "抽泣", "哽咽",
        "涕泪", "哭喊", "哭着", "哭起来", "眼泪", "泪水", "泪流"
    )

    /** 喊叫：音量拉满 */
    private val shoutWords = listOf(
        "大喊", "大叫", "呐喊", "咆哮", "怒吼", "吼道", "嘶喊", "厉喝",
        "喝道", "尖叫", "high声", "高呼", "喊道"
    )

    /** 低语：压音量、放慢 */
    private val whisperWords = listOf(
        "低声", "轻声", "喃喃", "低语", "耳语", "嘟囔", "小声", "悄声",
        "自言自语", "细声"
    )

    /** 悲伤：去掉了"哭""泪"（归入 cry），保留情绪描述词 */
    private val sadWords = listOf(
        "悲伤", "痛苦", "伤心", "难过", "绝望", "心痛", "悲哀", "哀伤",
        "悲凉", "凄然", "黯然", "神伤", "苦涩", "叹息", "叹了口气", "无奈"
    )

    /** 愤怒：全部改成双字以上词组，杜绝"打""气""杀"单字误命中 */
    private val angryWords = listOf(
        "愤怒", "暴怒", "怒火", "怒斥", "怒喝", "恼怒", "气愤", "愤慨",
        "混蛋", "该死", "可恶", "放肆", "无耻", "住口", "滚开", "找死",
        "咬牙", "切齿", "脸色铁青", "拍案", "怒目"
    )

    /** 恐惧/悬疑 */
    private val fearWords = listOf(
        "害怕", "恐惧", "惊恐", "颤抖", "冷汗", "诡异", "阴森", "不安",
        "心跳如鼓", "毛骨悚然", "胆寒", "惊骇", "骇然", "战栗"
    )

    /** 欢快：去掉单字"笑"（归入 laugh 判定），保留明确情绪词 */
    private val happyWords = listOf(
        "开心", "快乐", "高兴", "欣喜", "喜悦", "欢呼", "雀跃", "兴高采烈",
        "眉开眼笑", "喜出望外", "乐不可支", "微笑", "莞尔"
    )

    /** 兴奋 */
    private val excitedWords = listOf(
        "兴奋", "激动", "热血", "沸腾", "振奋", "亢奋", "迫不及待"
    )

    /**
     * 自动情感标注
     * 优先级：强表现力拟声（笑/哭/喊/低语）> 明确情绪词 > 标点强度 > neutral
     *
     * @return laugh/cry/shout/whisper/excited/cheerful/angry/sad/fearful/friendly/neutral
     */
    fun detectEmotion(text: String): String {
        if (text.isBlank()) return "neutral"

        // 1. 强表现力拟声词：最优先，这是"有感情"最直观的来源
        if (laughWords.any { text.contains(it) }) return "laugh"
        if (cryWords.any { text.contains(it) }) return "cry"
        if (shoutWords.any { text.contains(it) }) return "shout"
        if (whisperWords.any { text.contains(it) }) return "whisper"

        // 2. 明确情绪词组
        if (angryWords.any { text.contains(it) }) return "angry"
        if (sadWords.any { text.contains(it) }) return "sad"
        if (fearWords.any { text.contains(it) }) return "fearful"
        if (excitedWords.any { text.contains(it) }) return "excited"
        if (happyWords.any { text.contains(it) }) return "cheerful"

        // 3. 标点强度（放在词表之后，避免"！"把明确情绪盖掉）
        val exclamCount = text.count { it == '！' || it == '!' }
        if (exclamCount >= 2) return "excited"
        if (exclamCount == 1) return "cheerful"
        val questionCount = text.count { it == '？' || it == '?' }
        if (questionCount >= 1) return "friendly"

        return "neutral"
    }

    /** 强表现力标签：这些子句值得单独发一次合成请求 */
    private val strongEmotions = setOf("laugh", "cry", "shout", "whisper", "angry", "excited")

    /**
     * 把一段话按情绪切成子句，每个子句可独立合成。
     *
     * 为什么必须这么做：Edge 免费消费端点实测拒绝 mstts:express-as、break、
     * emphasis，并且一个 SSML 里超过 2 个 prosody 就直接返回 SSML is invalid。
     * 也就是说"一次请求内做句内起伏"这条路是死的。唯一可行的是把
     * "他/哈哈大笑/起来笑得直不起腰"这样的子句拆成独立请求，各自带自己的
     * prosody —— 实测三段独立请求的基频分别是 328/178/126 Hz，差异非常明显。
     *
     * 为了不让请求数爆炸，只在"子句情绪 ≠ 整段主情绪且属于强表现力标签"时才切，
     * 相邻同情绪子句会被合并，最多切 MAX_CLAUSES_PER_SEGMENT 段。
     */
    fun emotionClauses(text: String): List<Clause> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val whole = detectEmotion(trimmed)
        // 短句不值得再切
        if (trimmed.length < MIN_CLAUSE_LENGTH * 2) return listOf(Clause(trimmed, whole))

        val pieces = splitByPunctuation(trimmed)
        if (pieces.size <= 1) return listOf(Clause(trimmed, whole))

        // 逐子句标注。子句自己的强情绪最优先；平叙子句必须保持 neutral 平读，
        // 绝不能被整段的强情绪染色 —— 否则"他点了点头，忽然哈哈大笑起来"
        // 会整句都按大笑夸张读，等于没有起伏，仍然是背书。
        val tagged = pieces.map { piece ->
            val e = detectEmotion(piece)
            val emotion = when {
                e in strongEmotions -> e          // 子句本身有强情绪 → 用自己的
                whole in strongEmotions -> e      // 整段强情绪但本子句平淡 → 保持平淡
                else -> whole                     // 整段也无强情绪 → 跟随整段
            }
            Clause(piece, emotion)
        }

        // 相邻同情绪合并 + 过短片段并入邻居
        val merged = mutableListOf<Clause>()
        for (c in tagged) {
            val last = merged.lastOrNull()
            when {
                last == null -> merged.add(c)
                last.emotion == c.emotion ->
                    merged[merged.lastIndex] = Clause(last.text + c.text, last.emotion)
                c.text.length < MIN_CLAUSE_LENGTH ->
                    merged[merged.lastIndex] = Clause(last.text + c.text, last.emotion)
                last.text.length < MIN_CLAUSE_LENGTH ->
                    // 前一片太短，让它跟着后面这句的情绪走
                    merged[merged.lastIndex] = Clause(last.text + c.text, c.emotion)
                else -> merged.add(c)
            }
        }

        // 超过上限就退回整段单一情绪，避免一段话打十几个请求
        if (merged.size > MAX_CLAUSES_PER_SEGMENT) return listOf(Clause(trimmed, whole))
        return merged.filter { it.text.isNotBlank() }
    }

    /** 在逗号/分号/句末标点后切分，标点保留在前一片 */
    private fun splitByPunctuation(text: String): List<String> {
        val cuts = charArrayOf('，', ',', '。', '！', '!', '？', '?', '；', ';', '…')
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in text) {
            sb.append(c)
            if (c in cuts) {
                out.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out.filter { it.isNotBlank() }
    }

    /**
     * 检测对话段落（是否为引号开头）
     */
    fun isDialogue(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.isNotEmpty() && trimmed[0] in openQuotes
    }

    /**
     * 提取对话内容和说话人提示语
     * 例如: 张三说："你好" → 说话人=张三, 内容=你好
     */
    data class DialogueInfo(
        val speaker: String?,
        val content: String,
        val isDialogue: Boolean
    )

    fun parseDialogue(text: String): DialogueInfo {
        val trimmed = text.trim()

        // 查找引号位置
        val quoteStart = openQuotes.indexOfFirst { qc ->
            trimmed.indexOf(qc) >= 0
        }

        if (quoteStart < 0) {
            return DialogueInfo(null, trimmed, false)
        }

        val openQuote = openQuotes[quoteStart]
        val closeQuote = closeQuotes[quoteStart]
        val startIdx = trimmed.indexOf(openQuote)
        val endIdx = trimmed.indexOf(closeQuote, startIdx + 1)

        if (endIdx < 0) {
            return DialogueInfo(null, trimmed, false)
        }

        val dialogueContent = trimmed.substring(startIdx + 1, endIdx)
        val beforeQuote = trimmed.substring(0, startIdx).trim()

        // 提取说话人: "张三说：" → 张三
        val speaker = beforeQuote
            .replace(Regex("[：:,，。！？.!?]$"), "")
            .replace(Regex("(说道?|问道?|喊道?|笑道?|答道?|叫道?)$"), "")
            .trim()
            .ifBlank { null }

        return DialogueInfo(speaker, dialogueContent, true)
    }
}
