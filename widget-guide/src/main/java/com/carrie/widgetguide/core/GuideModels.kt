package com.carrie.widgetguide.core

import java.time.Instant
import java.time.ZoneId

enum class GuideType { TOOLS, AI_NEWS }
enum class Behavior { HOME_SEARCH, MINE_TOOL, HOME_AI_DETAIL }
enum class Surface { NONE, HOME_OTHER, HOME_AI, MINE }

data class GuideConfig(
    val enablePop: Boolean = false,
    val firstPopInterval: Int = 1,
    val secondPopInterval: Int = 3
) {
    init {
        require(firstPopInterval >= 0 && secondPopInterval >= 0)
    }
}

data class ShowRecord(
    val id: String,
    val type: GuideType,
    val shownAtMillis: Long,
    val localEpochDay: Long
)

/** A reservation is NOT a display count. An unresolved reservation fails closed on restart. */
data class PendingShow(val id: String, val type: GuideType)

data class GuideState(
    val behaviors: Set<Behavior> = emptySet(),
    val records: List<ShowRecord> = emptyList(),
    val pending: PendingShow? = null
) {
    val exhausted: Boolean get() = records.size >= 3
}

data class HostSnapshot(
    val hostId: String = "",
    val version: Long = 0,
    val surface: Surface = Surface.NONE,
    val canPresent: Boolean = false
)

data class GuideTime(val millis: Long, val zone: ZoneId) {
    val date get() = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val epochDay get() = date.toEpochDay()
    val nextMidnightMillis get() = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

fun interface GuideClock { fun now(): GuideTime }

object SystemGuideClock : GuideClock {
    override fun now() = GuideTime(System.currentTimeMillis(), ZoneId.systemDefault())
}

enum class BlockReason { DISABLED, LIMIT, RECOVERY_REQUIRED, HOST, BEHAVIOR, DAILY, COOLDOWN, CLOCK_ROLLBACK }

sealed interface Decision {
    data object Allow : Decision
    data class Block(val reason: BlockReason, val retryAtMillis: Long? = null) : Decision
}
