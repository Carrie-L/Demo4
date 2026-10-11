package com.carrie.widgetguide // 验证展示频次，与任何页面实现无关。

import org.junit.Assert.assertFalse // 拒绝展示的断言。
import org.junit.Assert.assertTrue // 允许展示的断言。
import org.junit.Test // JUnit4 单元测试入口。
import java.time.LocalDateTime // 固定日期避免真实等待天数。
import java.time.ZoneId // 验证当地自然日额度。

/** 测试真实产品边界：三次上限、两类独立、共享日额度和固定 24 小时。 */
class GuidePolicyTest { // 不镜像页面实现逻辑。
    private val zone = ZoneId.of("Asia/Shanghai") // 固定设备时区。
    private val rules = GuideRules(true, 1L, 10L) // 一天 / 十天云配。
    private fun time(local: String): Long = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli() // 转成本地毫秒时间。

    @Test fun firstValidReturnCanDisplayImmediately() { // 冷启后第一次有效行为不受单类型冷却。
        assertTrue(GuidePolicy.canShow(GuideType.COMMON_TOOLS, rules, GuideRecord(), time("2026-10-11T10:00:00"), zone)) // 可展示 A 一次。
    } // 首次测试结束。

    @Test fun firstIntervalNeedsFullTwentyFourHours() { // 跨自然日仍要满足固定时长。
        val shown = time("2026-10-11T10:00:00") // 第一次已展示时间。
        val record = GuideRecord(commonTools = TypeRecord(1, shown), lastAnyShownAtMillis = shown) // 一次 A 展示。
        val next = time("2026-10-12T10:00:00") // 恰好固定 24 小时。
        assertFalse(GuidePolicy.canShow(GuideType.COMMON_TOOLS, rules, record, next - 1L, zone)) // 提前 1ms 拒绝。
        assertTrue(GuidePolicy.canShow(GuideType.COMMON_TOOLS, rules, record, next, zone)) // 边界允许第二次。
    } // 第一次间隔测试结束。

    @Test fun secondIntervalNeedsTenFullDays() { // 第二次后才能进入第三次。
        val shown = time("2026-10-11T10:00:00") // 第二次展示时间。
        val record = GuideRecord(aiQuickNews = TypeRecord(2, shown), lastAnyShownAtMillis = shown) // B 已展示两次。
        val next = shown + 10L * GuidePolicy.DAY_MILLIS // 第三次最早时刻。
        assertFalse(GuidePolicy.canShow(GuideType.AI_QUICK_NEWS, rules, record, next - 1L, zone)) // 十天少 1ms 拒绝。
        assertTrue(GuidePolicy.canShow(GuideType.AI_QUICK_NEWS, rules, record, next, zone)) // 恰好十天允许第三次。
    } // 第二次间隔测试结束。

    @Test fun eachTypeStopsAtThreeIndependently() { // thirdPopInterval 无论怎么配置都不存在第四次。
        val shown = time("2026-10-01T10:00:00") // 以前一次记录的时间。
        val record = GuideRecord(commonTools = TypeRecord(3, shown), lastAnyShownAtMillis = shown) // A 达到上限，B 尚未展示。
        val now = time("2026-11-01T10:00:00") // 已跨过任意冷却。
        assertFalse(GuidePolicy.canShow(GuideType.COMMON_TOOLS, rules, record, now, zone)) // A 永不再弹。
        assertTrue(GuidePolicy.canShow(GuideType.AI_QUICK_NEWS, rules, record, now, zone)) // B 仍能首弹。
    } // 独立上限测试结束。

    @Test fun bothTypesShareOneLocalCalendarDay() { // A 当天展示阻止 B 当天展示。
        val shown = time("2026-10-11T08:00:00") // A 当天上午展示。
        val record = GuideRecord(commonTools = TypeRecord(1, shown), lastAnyShownAtMillis = shown) // 共享时间有记录。
        assertFalse(GuidePolicy.canShow(GuideType.AI_QUICK_NEWS, rules, record, time("2026-10-11T20:00:00"), zone)) // B 晚间不能弹。
        assertTrue(GuidePolicy.canShow(GuideType.AI_QUICK_NEWS, rules, record, time("2026-10-12T00:00:00"), zone)) // B 次日首次可弹。
    } // 共享自然日测试结束。

    @Test fun addClickStopsOnlyItsOwnType() { // deeplink 失败也由存储中的停止位控制。
        val shown = time("2026-10-01T10:00:00") // A 以前的展示时间。
        val record = GuideRecord(commonTools = TypeRecord(1, shown, stopped = true), lastAnyShownAtMillis = shown) // A 点击添加。
        val now = time("2026-10-12T10:00:00") // B 新行为发生在另一天。
        assertFalse(GuidePolicy.canShow(GuideType.COMMON_TOOLS, rules, record, now, zone)) // A 永远停。
        assertTrue(GuidePolicy.canShow(GuideType.AI_QUICK_NEWS, rules, record, now, zone)) // B 仍正常。
    } // 独立停止测试结束。

    @Test fun zeroIntervalStillRespectsSharedCalendarDay() { // 后台配置 0 不取消共同日限制。
        val zero = GuideRules(true, 0L, 0L) // 允许频次间隔为 0。
        val shown = time("2026-10-11T08:00:00") // 第一次 A 展示。
        val record = GuideRecord(commonTools = TypeRecord(1, shown), lastAnyShownAtMillis = shown) // 计数 1。
        assertFalse(GuidePolicy.canShow(GuideType.COMMON_TOOLS, zero, record, time("2026-10-11T20:00:00"), zone)) // 当天仍拒绝。
        assertTrue(GuidePolicy.canShow(GuideType.COMMON_TOOLS, zero, record, time("2026-10-12T00:00:00"), zone)) // 次日允许。
    } // 零间隔测试结束。

    @Test fun missingOrInvalidIntervalDoesNotResetRecord() { // 错误配置不能当 0 天处理。
        val shown = time("2026-10-01T10:00:00") // 第一条历史。
        val record = GuideRecord(commonTools = TypeRecord(1, shown), lastAnyShownAtMillis = shown) // A 已展示一次。
        val now = time("2026-10-12T10:00:00") // 时间充分经过。
        assertFalse(GuidePolicy.canShow(GuideType.COMMON_TOOLS, GuideRules(true, null, 10L), record, now, zone)) // 缺失间隔拒绝。
        assertFalse(GuidePolicy.canShow(GuideType.COMMON_TOOLS, GuideRules(true, -1L, 10L), record, now, zone)) // 负间隔拒绝。
        assertFalse(GuidePolicy.canShow(GuideType.COMMON_TOOLS, GuideRules(true, Long.MAX_VALUE, 10L), record, now, zone)) // 溢出拒绝。
    } // 异常配置测试结束。

    @Test fun clockRollbackDoesNotCreateNewOpportunity() { // 用户调慢时间不能重置当天额度。
        val shown = time("2026-10-11T10:00:00") // A 上次展示。
        val record = GuideRecord(commonTools = TypeRecord(1, shown), lastAnyShownAtMillis = shown) // 有真实展示记录。
        assertFalse(GuidePolicy.canShow(GuideType.AI_QUICK_NEWS, rules, record, shown - 1L, zone)) // 回拨时拒绝 B。
    } // 时间回拨测试结束。

    @Test fun disabledConfigNeverDisplays() { // 后台关闭时本轮机会结束。
        val off = rules.copy(enable = false) // 模拟关闭配置。
        assertFalse(GuidePolicy.canShow(GuideType.COMMON_TOOLS, off, GuideRecord(), time("2026-10-11T10:00:00"), zone)) // 即使首弹也被拦截。
    } // 总开关测试结束。
} // 核心规则测试结束。
