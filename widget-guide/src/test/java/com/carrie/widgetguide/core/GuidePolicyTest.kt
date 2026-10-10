package com.carrie.widgetguide.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GuidePolicyTest {
    private val policy = GuidePolicy()
    private val config = GuideConfig(true, 1, 3)
    private val time = GuideTime(Instant.parse("2026-10-10T15:00:00Z").toEpochMilli(), ZoneId.of("Asia/Shanghai"))
    private val home = HostSnapshot("home", 1, Surface.HOME_OTHER, true)
    private val all = GuideState(Behavior.entries.toSet())
    private fun record(id: String, type: GuideType, at: GuideTime = time) = ShowRecord(id, type, at.millis, at.epochDay)
    private fun decision(type: GuideType = GuideType.TOOLS, state: GuideState = all,
                         now: GuideTime = time, host: HostSnapshot = home,
                         rules: GuideConfig = config) = policy.evaluate(type, state, rules, host, now)

    @Test fun `default config is disabled`() {
        assertEquals(BlockReason.DISABLED, (decision(rules = GuideConfig()) as Decision.Block).reason)
    }
    @Test fun `first eligible display has no cooldown`() { assertEquals(Decision.Allow, decision()) }
    @Test fun `behavior is required`() {
        assertEquals(BlockReason.BEHAVIOR, (decision(state = GuideState()) as Decision.Block).reason)
    }
    @Test fun `home behavior does not unlock mine`() {
        assertEquals(BlockReason.BEHAVIOR, (decision(state = GuideState(setOf(Behavior.HOME_SEARCH)),
            host = home.copy(surface = Surface.MINE)) as Decision.Block).reason)
    }
    @Test fun `mine behavior does not unlock home`() {
        assertEquals(BlockReason.BEHAVIOR, (decision(state = GuideState(setOf(Behavior.MINE_TOOL))) as Decision.Block).reason)
    }
    @Test fun `B can only appear in AI home channel`() {
        assertEquals(BlockReason.HOST, (decision(type = GuideType.AI_NEWS) as Decision.Block).reason)
        assertEquals(Decision.Allow, decision(type = GuideType.AI_NEWS, host = home.copy(surface = Surface.HOME_AI)))
    }
    @Test fun `underlying selected tab is insufficient when host is hidden`() {
        assertEquals(BlockReason.HOST, (decision(host = home.copy(canPresent = false)) as Decision.Block).reason)
    }
    @Test fun `A today blocks B today`() {
        val state = all.copy(records = listOf(record("a", GuideType.TOOLS)))
        assertEquals(BlockReason.DAILY, (decision(GuideType.AI_NEWS, state, host = home.copy(surface = Surface.HOME_AI)) as Decision.Block).reason)
    }
    @Test fun `B first display on next date does not wait for A 24 hours`() {
        val nextDate = time.copy(millis = time.millis + 2 * 3_600_000L)
        val state = all.copy(records = listOf(record("a", GuideType.TOOLS)))
        assertEquals(Decision.Allow, decision(GuideType.AI_NEWS, state, nextDate, home.copy(surface = Surface.HOME_AI)))
        assertEquals(BlockReason.COOLDOWN, (decision(state = state, now = nextDate) as Decision.Block).reason)
    }
    @Test fun `first interval boundary is exactly 24 hours`() {
        val state = all.copy(records = listOf(record("a", GuideType.TOOLS)))
        assertEquals(BlockReason.COOLDOWN, (decision(state = state, now = time.copy(millis = time.millis + GuidePolicy.DAY_MILLIS - 1)) as Decision.Block).reason)
        assertEquals(Decision.Allow, decision(state = state, now = time.copy(millis = time.millis + GuidePolicy.DAY_MILLIS)))
    }
    @Test fun `second interval is relative to second display`() {
        val second = time.copy(millis = time.millis + GuidePolicy.DAY_MILLIS)
        val state = all.copy(records = listOf(record("1", GuideType.TOOLS), record("2", GuideType.TOOLS, second)))
        assertEquals(BlockReason.COOLDOWN, (decision(state = state, now = second.copy(millis = second.millis + 3 * GuidePolicy.DAY_MILLIS - 1)) as Decision.Block).reason)
        assertEquals(Decision.Allow, decision(state = state, now = second.copy(millis = second.millis + 3 * GuidePolicy.DAY_MILLIS)))
    }
    @Test fun `mixed three displays permanently stop both`() {
        val state = all.copy(records = listOf(record("1", GuideType.TOOLS), record("2", GuideType.AI_NEWS), record("3", GuideType.TOOLS)))
        for (type in GuideType.entries) assertEquals(BlockReason.LIMIT,
            (decision(type, state, host = home.copy(surface = Surface.HOME_AI)) as Decision.Block).reason)
    }
    @Test fun `zero interval still respects daily cap`() {
        val state = all.copy(records = listOf(record("1", GuideType.TOOLS)))
        assertEquals(BlockReason.DAILY, (decision(state = state, rules = GuideConfig(true, 0, 0)) as Decision.Block).reason)
    }
    @Test fun `rollback blocks rather than resetting records`() {
        val state = all.copy(records = listOf(record("1", GuideType.TOOLS)))
        assertEquals(BlockReason.CLOCK_ROLLBACK, (decision(state = state, now = time.copy(millis = time.millis - 1)) as Decision.Block).reason)
    }
    @Test fun `reservation does not count but blocks admission`() {
        val state = all.copy(pending = PendingShow("p", GuideType.TOOLS))
        assertEquals(0, state.records.size)
        assertEquals(BlockReason.RECOVERY_REQUIRED, (decision(state = state) as Decision.Block).reason)
    }
    @Test fun `AI first priority is a candidate order not a global blocker`() {
        assertEquals(listOf(GuideType.AI_NEWS, GuideType.TOOLS), policy.candidates(Surface.HOME_AI))
        val yesterday = time.copy(millis = time.millis - GuidePolicy.DAY_MILLIS)
        val state = all.copy(records = listOf(record("b", GuideType.AI_NEWS, yesterday)))
        val host = home.copy(surface = Surface.HOME_AI)
        val rules = GuideConfig(true, 3, 3)
        val winner = policy.candidates(host.surface).first { policy.evaluate(it, state, rules, host, time) == Decision.Allow }
        assertEquals(GuideType.TOOLS, winner)
    }
    @Test fun `daily wakeup also accounts for longer cooldown`() {
        val state = all.copy(records = listOf(record("a", GuideType.TOOLS)))
        val blocked = decision(state = state) as Decision.Block
        assertEquals(time.millis + GuidePolicy.DAY_MILLIS, blocked.retryAtMillis)
    }
    @Test fun `next midnight handles daylight saving without treating a date as 24 hours`() {
        val dst = GuideTime(Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(), ZoneId.of("America/New_York"))
        assertEquals(23 * 3_600_000L, dst.nextMidnightMillis - dst.millis)
    }
}
