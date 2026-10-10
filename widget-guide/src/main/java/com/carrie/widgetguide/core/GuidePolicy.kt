package com.carrie.widgetguide.core


/**
 * 频控规则集中入口，只依赖传入快照，可独立单元测试。
 * @param toolsOnAllHomeChannels true 时 A 也可在 AI 频道作为 B 的后备候选；false 只移除该候选。
 */
class GuidePolicy(private val toolsOnAllHomeChannels: Boolean = true) {
    /**
     * 按当前位置给出候选优先级。AI 频道为 B → A；B 被拦截不阻止继续评估 A。
     */
    fun candidates(surface: Surface): List<GuideType> = when (surface) {
        Surface.HOME_AI -> if (toolsOnAllHomeChannels) listOf(GuideType.AI_NEWS, GuideType.TOOLS)
            else listOf(GuideType.AI_NEWS)
        Surface.HOME_OTHER, Surface.MINE -> listOf(GuideType.TOOLS)
        Surface.NONE -> emptyList()
    }

    /**
     * 判断指定类型在当前快照下能否自动展示，不消费行为、不改变状态。
     * 依次检查云配、总次数、预约、宿主、行为、时钟、当天次数及同类型冷却。
     * 首次展示没有冷却；同类型历史只有一条时用 firstPopInterval，否则用 secondPopInterval。
     * DAILY 的重试点取下一零点与冷却结束的较晚值，避免无意义唤醒。
     */
    fun evaluate(type: GuideType, state: GuideState, config: GuideConfig,
                 host: HostSnapshot, time: GuideTime): Decision {
        if (!config.enablePop) return Decision.Block(BlockReason.DISABLED)
        if (state.exhausted) return Decision.Block(BlockReason.LIMIT)
        if (state.pending != null) return Decision.Block(BlockReason.RECOVERY_REQUIRED)
        if (!host.canPresent || type !in candidates(host.surface)) return Decision.Block(BlockReason.HOST)
        val behavior = when (type) {
            GuideType.AI_NEWS -> Behavior.HOME_AI_DETAIL
            GuideType.TOOLS -> if (host.surface == Surface.MINE) Behavior.MINE_TOOL else Behavior.HOME_SEARCH
        }
        if (behavior !in state.behaviors) return Decision.Block(BlockReason.BEHAVIOR)
        if (state.records.any { it.shownAtMillis > time.millis || it.localEpochDay > time.epochDay }) {
            return Decision.Block(BlockReason.CLOCK_ROLLBACK)
        }
        // 冷却只取该类型历史，日额度和总额度仍使用全体记录。
        val own = state.records.filter { it.type == type }
        val last = own.maxByOrNull { it.shownAtMillis }
        val days = if (own.size == 1) config.firstPopInterval else config.secondPopInterval
        val next = last?.let { it.shownAtMillis + days.toLong() * DAY_MILLIS } ?: time.millis
        val today = state.records.any { it.localEpochDay == time.epochDay }
        // 跨自然日与冷却到期必须同时满足；不要简单以“明天零点”作为唯一重试点。
        if (today) return Decision.Block(BlockReason.DAILY, maxOf(next, time.nextMidnightMillis))
        if (next > time.millis) return Decision.Block(BlockReason.COOLDOWN, next)
        return Decision.Allow
    }

    /**
     * 冷却的“天”固定为 24 小时；自然日限制另用 GuideTime 计算本地日期。
     */
    companion object { const val DAY_MILLIS = 86_400_000L }
}
