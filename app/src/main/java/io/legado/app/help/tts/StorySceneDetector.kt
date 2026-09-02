package io.legado.app.help.tts

/**
 * 本地情节识别器。按关键词计分并加入迟滞，避免背景音一句一换。
 */
class StorySceneDetector(
    private val minHoldMs: Long = 45_000L
) {
    enum class Scene { NONE, PEACEFUL, RAIN, FOREST, BATTLE, SUSPENSE, ROMANCE, SAD }


    enum class Effect(val assetName: String) {
        DOOR_SLAM("door_slam.wav"),
        THUNDER("thunder.wav"),
        RAIN("rain_burst.wav"),
        WIND("wind.wav"),
        KNOCK("knock.wav"),
        FOOTSTEP("footstep.wav"),
        SWORD("sword.wav"),
        EXPLOSION("explosion.wav")
    }

    private val effectKeywords = mapOf(
        Effect.DOOR_SLAM to listOf("砰", "嘭", "关门", "摔门", "门重重", "一声巨响", "撞门", "踹开门"),
        Effect.THUNDER to listOf("雷鸣", "惊雷", "炸雷", "轰隆", "雷声", "电闪雷鸣"),
        Effect.RAIN to listOf("下雨", "暴雨", "大雨", "雨幕", "倾盆", "狂风暴雨", "雨点", "雨声"),
        Effect.WIND to listOf("狂风", "风声", "呼啸", "寒风", "大风", "风刮", "风暴"),
        Effect.KNOCK to listOf("敲门", "叩门", "咚咚", "扣门"),
        Effect.FOOTSTEP to listOf("脚步声", "脚步", "走廊传来", "踩在", "靠近"),
        Effect.SWORD to listOf("拔剑", "剑鸣", "刀光", "刀剑", "铿锵", "兵刃", "剑气"),
        Effect.EXPLOSION to listOf("爆炸", "炸开", "轰然", "轰的一声", "炸裂")
    )

    fun detectEffect(text: String): Effect? {
        val scores = effectKeywords.mapValues { (_, words) ->
            words.sumOf { word -> Regex.escape(word).toRegex().findAll(text).count() }
        }
        return scores.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key
    }

    private var current = Scene.NONE
    private var changedAt = 0L

    private val keywords = mapOf(
        Scene.RAIN to listOf("雨", "暴雨", "雨滴", "雷", "闪电", "乌云", "雨幕", "倾盆", "雷鸣", "湿漉", "伞", "水洼"),
        Scene.FOREST to listOf("森林", "树林", "山谷", "鸟鸣", "虫鸣", "草丛", "山林", "树叶", "枝头", "野外", "山间", "丛林"),
        Scene.BATTLE to listOf("战斗", "厮杀", "刀剑", "冲锋", "追杀", "爆炸", "枪声", "杀气", "剑气", "出手", "攻击", "拳", "掌", "拔剑", "鲜血", "敌人", "战场", "怒吼", "砍", "刺", "轰", "交锋", "围攻"),
        Scene.SUSPENSE to listOf("诡异", "恐怖", "阴森", "黑暗", "尸体", "血迹", "秘密", "寂静", "心跳", "不安", "危险", "冷汗", "颤抖", "脚步声", "身后", "失踪", "谜", "幽灵", "鬼", "怪物", "异常"),
        Scene.ROMANCE to listOf("心动", "拥抱", "亲吻", "温柔", "爱意", "脸红", "恋人", "相拥", "喜欢", "爱", "牵手", "靠近", "目光", "微笑", "害羞", "柔情", "心跳加速", "婚礼"),
        Scene.SAD to listOf("离别", "眼泪", "哭泣", "悲伤", "死亡", "绝望", "心痛", "葬礼", "失去", "再见", "泪水", "哽咽", "孤独", "遗憾", "痛苦", "牺牲", "墓", "怀念"),
        Scene.PEACEFUL to listOf("清晨", "阳光", "微风", "安静", "平静", "悠闲", "午后", "宁静", "日常", "吃饭", "回家", "散步", "喝茶", "睡觉", "院子", "房间", "闲聊")
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
