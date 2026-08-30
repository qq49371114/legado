package io.legado.app.help.tts

import io.legado.app.constant.AppPattern
import java.util.regex.Pattern

/**
 * 智能分段 + 情感标注
 * 1. 按语义分段（不仅按\n切）
 * 2. 合并过短句、拆分过长段
 * 3. 保留对话引号完整性
 * 4. 自动情感标注（给支持SSML的引擎用）
 */
object SmartSegmenter {

    /** 句子结束标点 */
    private val sentenceEnd = charArrayOf('。', '！', '？', '!', '?', '…', '\n')

    /** 段落结束标点 */
    private val paragraphEnd = charArrayOf('\n', '\r')

    /** 引号 */
    private val openQuotes = charArrayOf('"', '"', '"', '「')
    private val closeQuotes = charArrayOf('"', '"', '"', '」')

    /** 单段最大字符数 */
    private const val MAX_SEGMENT_LENGTH = 500

    /** 单段最小字符数（低于此合并到上一段） */
    private const val MIN_SEGMENT_LENGTH = 10

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

    /**
     * 自动情感标注
     * 根据标点符号和关键词推断语气
     *
     * @param text 段落文本
     * @return 情感标签: cheerful/sad/angry/excited/friendly/neutral
     */
    fun detectEmotion(text: String): String {
        // 感叹号多 → excited/cheerful
        val exclamCount = text.count { it == '！' || it == '!' }
        if (exclamCount >= 3) return "excited"
        if (exclamCount >= 1) return "cheerful"

        // 问号多 → 思考语气（用 friendly 模拟）
        val questionCount = text.count { it == '？' || it == '?' }
        if (questionCount >= 2) return "friendly"

        // 悲伤关键词
        val sadKeywords = listOf("哭", "泪", "悲伤", "痛苦", "伤心", "难过", "绝望", "心痛", "悲哀", "哀伤")
        if (sadKeywords.any { text.contains(it) }) return "sad"

        // 愤怒关键词
        val angryKeywords = listOf("怒", "气", "愤", "混蛋", "该死", "可恶", "滚", "杀", "打", "骂")
        if (angryKeywords.any { text.contains(it) }) return "angry"

        // 欢快关键词
        val happyKeywords = listOf("笑", "开心", "快乐", "高兴", "兴奋", "哈哈", "嘻嘻", "嘿嘿")
        if (happyKeywords.any { text.contains(it) }) return "cheerful"

        return "neutral"
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
