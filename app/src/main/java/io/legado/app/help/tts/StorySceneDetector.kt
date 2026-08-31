package io.legado.app.help.tts

/**
 * 本地情节识别器。按关键词计分并加入迟滞，避免背景音一句一换。
 */
class StorySceneDetector(
    private val minHoldMs: Long = 45_000L
) {
    enum class Scene { NONE, PEACEFUL, RAIN, FOREST, BATTLE, SUSPENSE, ROMANCE, SAD }

    private var current = Scene.NONE
    private var changedAt = 0L

    private val keywords = mapOf(
        Scene.RAIN to listOf("雨", "暴雨", "雨滴", "雷", "闪电", "乌云", "雨幕"),
        Scene.FOREST to listOf("森林", "树林", "山谷", "鸟鸣", "虫鸣", "草丛", "山林"),
        Scene.BATTLE to listOf("战斗", "厮杀", "刀剑", "冲锋", "追杀", "爆炸", "枪声", "杀气", "剑气"),
        Scene.SUSPENSE to listOf("诡异", "恐怖", "阴森", "黑暗", "尸体", "血迹", "秘密", "寂静", "心跳"),
        Scene.ROMANCE to listOf("心动", "拥抱", "亲吻", "温柔", "爱意", "脸红", "恋人", "相拥"),
        Scene.SAD to listOf("离别", "眼泪", "哭泣", "悲伤", "死亡", "绝望", "心痛", "葬礼"),
        Scene.PEACEFUL to listOf("清晨", "阳光", "微风", "安静", "平静", "悠闲", "午后", "宁静")
    )

    fun classify(text: String): Scene {
        val scores = keywords.mapValues { (_, words) ->
            words.sumOf { word -> Regex.escape(word).toRegex().findAll(text).count() }
        }
        return scores.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: Scene.NONE
    }

    fun detect(text: String, now: Long = System.currentTimeMillis()): Scene {
        val candidate = classify(text).takeIf { it != Scene.NONE } ?: current
        val scores = keywords.mapValues { (_, words) ->
            words.sumOf { word -> Regex.escape(word).toRegex().findAll(text).count() }
        }
        val candidateScore = scores[candidate] ?: 0
        val currentScore = scores[current] ?: 0
        val canSwitch = current == Scene.NONE || now - changedAt >= minHoldMs || candidateScore >= currentScore + 2
        if (candidate != current && canSwitch) {
            current = candidate
            changedAt = now
        }
        return current
    }

    fun reset() {
        current = Scene.NONE
        changedAt = 0L
    }
}
