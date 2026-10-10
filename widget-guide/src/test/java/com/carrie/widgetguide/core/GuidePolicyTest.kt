package com.carrie.widgetguide.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** 纯策略边界测试：独立冷却、共享上限、位置、回拨和夏令时。 */
class GuidePolicyTest {
    /**
     * 被测纯策略实例，默认允许 A 在全部首页频道参与候选。
     */
    private val policy = GuidePolicy()
    /**
     * 启用引导，同类型第 2/3 次分别等待 1/3 个固定 24 小时。
     */
    private val config = GuideConfig(true, 1, 3)
    /**
     * 基准为上海 23:00，便于验证跨零点不等于满 24 小时。
     */
    private val time = GuideTime(Instant.parse("2026-10-10T15:00:00Z").toEpochMilli(), ZoneId.of("Asia/Shanghai"))
    /**
     * 可展示的非 AI 首页快照。
     */
    private val home = HostSnapshot("home", 1, Surface.HOME_OTHER, true)
    /**
     * 预先完成全部行为，单个用例可覆盖该状态来测试行为条件。
     */
    private val all = GuideState(Behavior.entries.toSet())
    /**
     * 按给定时钟快照构造一致的展示时间和本地日期。
     */
    private fun record(id: String, type: GuideType, at: GuideTime = time) = ShowRecord(id, type, at.millis, at.epochDay)
    /**
     * 提供默认上下文，各用例只覆盖本次需要变化的维度。
     */
    private fun decision(type: GuideType = GuideType.TOOLS, state: GuideState = all,
                         now: GuideTime = time, host: HostSnapshot = home,
                         rules: GuideConfig = config) = policy.evaluate(type, state, rules, host, now)

    /** 默认配置必须关闭自动引导。 */
    @Test fun `default config is disabled`() {
        assertEquals(BlockReason.DISABLED, (decision(rules = GuideConfig()) as Decision.Block).reason)
    }
    /** 已满足行为且没有展示历史时，首次不需要等待冷却。 */
    @Test fun `first eligible display has no cooldown`() { assertEquals(Decision.Allow, decision()) }
    /** 仅有正确宿主和开关，缺少对应行为仍然阻断。 */
    @Test fun `behavior is required`() {
        assertEquals(BlockReason.BEHAVIOR, (decision(state = GuideState()) as Decision.Block).reason)
    }
    /** 首页搜索资格不能用于我的页展示 A。 */
    @Test fun `home behavior does not unlock mine`() {
        assertEquals(BlockReason.BEHAVIOR, (decision(state = GuideState(setOf(Behavior.HOME_SEARCH)),
            host = home.copy(surface = Surface.MINE)) as Decision.Block).reason)
    }
    /** 我的工具资格不能用于首页展示 A。 */
    @Test fun `mine behavior does not unlock home`() {
        assertEquals(BlockReason.BEHAVIOR, (decision(state = GuideState(setOf(Behavior.MINE_TOOL))) as Decision.Block).reason)
    }
    /** B 在普通首页拒绝，在首页 AI 频道允许。 */
    @Test fun `B can only appear in AI home channel`() {
        assertEquals(BlockReason.HOST, (decision(type = GuideType.AI_NEWS) as Decision.Block).reason)
        assertEquals(Decision.Allow, decision(type = GuideType.AI_NEWS, host = home.copy(surface = Surface.HOME_AI)))
    }
    /** 宿主不可展示时，即使底部仍选中首页也必须拒绝。 */
    @Test fun `underlying selected tab is insufficient when host is hidden`() {
        assertEquals(BlockReason.HOST, (decision(host = home.copy(canPresent = false)) as Decision.Block).reason)
    }
    /** A/B 共用本地自然日上限，今天展示 A 后不能再展示 B。 */
    @Test fun `A today blocks B today`() {
        val state = all.copy(records = listOf(record("a", GuideType.TOOLS)))
        assertEquals(BlockReason.DAILY, (decision(GuideType.AI_NEWS, state, host = home.copy(surface = Surface.HOME_AI)) as Decision.Block).reason)
    }
    /** 跨零点但 A 冷却未满，B 首次可展示，证明 A/B 冷却独立。 */
    @Test fun `B first display on next date does not wait for A 24 hours`() {
        val nextDate = time.copy(millis = time.millis + 2 * 3_600_000L)
        val state = all.copy(records = listOf(record("a", GuideType.TOOLS)))
        assertEquals(Decision.Allow, decision(GuideType.AI_NEWS, state, nextDate, home.copy(surface = Surface.HOME_AI)))
        assertEquals(BlockReason.COOLDOWN, (decision(state = state, now = nextDate) as Decision.Block).reason)
    }
    /** 同类型第二次在满 24 小时前 1 毫秒拒绝，恰好满时允许。 */
    @Test fun `first interval boundary is exactly 24 hours`() {
        val state = all.copy(records = listOf(record("a", GuideType.TOOLS)))
        assertEquals(BlockReason.COOLDOWN, (decision(state = state, now = time.copy(millis = time.millis + GuidePolicy.DAY_MILLIS - 1)) as Decision.Block).reason)
        assertEquals(Decision.Allow, decision(state = state, now = time.copy(millis = time.millis + GuidePolicy.DAY_MILLIS)))
    }
    /** 同类型第三次冷却从第二次展示起算，验证 72 小时边界。 */
    @Test fun `second interval is relative to second display`() {
        val second = time.copy(millis = time.millis + GuidePolicy.DAY_MILLIS)
        val state = all.copy(records = listOf(record("1", GuideType.TOOLS), record("2", GuideType.TOOLS, second)))
        assertEquals(BlockReason.COOLDOWN, (decision(state = state, now = second.copy(millis = second.millis + 3 * GuidePolicy.DAY_MILLIS - 1)) as Decision.Block).reason)
        assertEquals(Decision.Allow, decision(state = state, now = second.copy(millis = second.millis + 3 * GuidePolicy.DAY_MILLIS)))
    }
    /** A/B 混合累计三次，两个类型都触发总上限。 */
    @Test fun `mixed three displays permanently stop both`() {
        val state = all.copy(records = listOf(record("1", GuideType.TOOLS), record("2", GuideType.AI_NEWS), record("3", GuideType.TOOLS)))
        for (type in GuideType.entries) assertEquals(BlockReason.LIMIT,
            (decision(type, state, host = home.copy(surface = Surface.HOME_AI)) as Decision.Block).reason)
    }
    /** 配置间隔为零也不能绕过每天最多一次。 */
    @Test fun `zero interval still respects daily cap`() {
        val state = all.copy(records = listOf(record("1", GuideType.TOOLS)))
        assertEquals(BlockReason.DAILY, (decision(state = state, rules = GuideConfig(true, 0, 0)) as Decision.Block).reason)
    }
    /** 系统时间早于已有展示记录时阻断，不能清空历史。 */
    @Test fun `rollback blocks rather than resetting records`() {
        val state = all.copy(records = listOf(record("1", GuideType.TOOLS)))
        assertEquals(BlockReason.CLOCK_ROLLBACK, (decision(state = state, now = time.copy(millis = time.millis - 1)) as Decision.Block).reason)
    }
    /** pending 不算次数，但必须阻止再接纳新展示。 */
    @Test fun `reservation does not count but blocks admission`() {
        val state = all.copy(pending = PendingShow("p", GuideType.TOOLS))
        assertEquals(0, state.records.size)
        assertEquals(BlockReason.RECOVERY_REQUIRED, (decision(state = state) as Decision.Block).reason)
    }
    /** AI 频道优先 B；B 冷却未满时，允许满足条件的 A 补位。 */
    @Test fun `AI first priority is a candidate order not a global blocker`() {
        assertEquals(listOf(GuideType.AI_NEWS, GuideType.TOOLS), policy.candidates(Surface.HOME_AI))
        val yesterday = time.copy(millis = time.millis - GuidePolicy.DAY_MILLIS)
        val state = all.copy(records = listOf(record("b", GuideType.AI_NEWS, yesterday)))
        val host = home.copy(surface = Surface.HOME_AI)
        val rules = GuideConfig(true, 3, 3)
        val winner = policy.candidates(host.surface).first { policy.evaluate(it, state, rules, host, time) == Decision.Allow }
        assertEquals(GuideType.TOOLS, winner)
    }
    /** 今天已展示时，重试时间应同时满足次日和冷却结束。 */
    @Test fun `daily wakeup also accounts for longer cooldown`() {
        val state = all.copy(records = listOf(record("a", GuideType.TOOLS)))
        val blocked = decision(state = state) as Decision.Block
        assertEquals(time.millis + GuidePolicy.DAY_MILLIS, blocked.retryAtMillis)
    }
    /** 夏令时切换日下一零点可能仅差 23 小时，不能用固定 24 小时计算自然日。 */
    @Test fun `next midnight handles daylight saving without treating a date as 24 hours`() {
        val dst = GuideTime(Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(), ZoneId.of("America/New_York"))
        assertEquals(23 * 3_600_000L, dst.nextMidnightMillis - dst.millis)
    }
}
