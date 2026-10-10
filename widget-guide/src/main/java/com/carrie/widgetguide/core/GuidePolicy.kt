package com.carrie.widgetguide.core

/** Side-effect-free rules. One day of cooldown is exactly 24 hours; daily cap is a local date. */
class GuidePolicy(private val toolsOnAllHomeChannels: Boolean = true) {
    fun candidates(surface: Surface): List<GuideType> = when (surface) {
        Surface.HOME_AI -> if (toolsOnAllHomeChannels) listOf(GuideType.AI_NEWS, GuideType.TOOLS)
            else listOf(GuideType.AI_NEWS)
        Surface.HOME_OTHER, Surface.MINE -> listOf(GuideType.TOOLS)
        Surface.NONE -> emptyList()
    }

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
        val own = state.records.filter { it.type == type }
        val last = own.maxByOrNull { it.shownAtMillis }
        val days = if (own.size == 1) config.firstPopInterval else config.secondPopInterval
        val next = last?.let { it.shownAtMillis + days.toLong() * DAY_MILLIS } ?: time.millis
        val today = state.records.any { it.localEpochDay == time.epochDay }
        if (today) return Decision.Block(BlockReason.DAILY, maxOf(next, time.nextMidnightMillis))
        if (next > time.millis) return Decision.Block(BlockReason.COOLDOWN, next)
        return Decision.Allow
    }

    companion object { const val DAY_MILLIS = 86_400_000L }
}
