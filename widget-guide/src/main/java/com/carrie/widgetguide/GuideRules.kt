package com.carrie.widgetguide // 引导模块只对外暴露类型、规则与管理器。

import java.time.Instant // 把展示时间换算为自然日。
import java.time.ZoneId // 自然日使用设备当前时区。

/** 名称直接表达产品含义，存储键也使用相同含义。 */
enum class GuideType { COMMON_TOOLS, AI_QUICK_NEWS } // 两类弹窗各自独立计数。

/** 后台配置；第三个间隔不参与最多三次的展示规则。 */
data class GuideRules( // 用一份配置控制两种弹窗。
    val enable: Boolean = false, // 云配默认关闭，避免尚未取到配置时误弹。
    val firstPopInterval: Long? = null, // 第一次之后到第二次的固定 24 小时间隔。
    val secondPopInterval: Long? = null // 第二次之后到第三次的固定 24 小时间隔。
) // 不存 thirdPopInterval，因为绝不展示第四次。

/** 一类弹窗的展示历史与用户点击添加后的停止状态。 */
data class TypeRecord( // 仅保存频次，不保存访问过页面的资格。
    val showCount: Int = 0, // 已经展示的次数，最大为 3。
    val lastShownAtMillis: Long = 0L, // 此类型最后一次 onShow 时间。
    val stopped: Boolean = false // 点击此类型任意添加按钮后为 true。
) // A 与 B 分别拥有自己的实例。

/** A、B 唯一共享的是最近一次展示日期。 */
data class GuideRecord( // 用一份快照读取和写入七个持久字段。
    val commonTools: TypeRecord = TypeRecord(), // 常用工具组件独立记录。
    val aiQuickNews: TypeRecord = TypeRecord(), // AI 快看组件独立记录。
    val lastAnyShownAtMillis: Long = 0L // 共享本地自然日限额。
) // 行为访问状态不进入这份记录。

/** 与 Android UI 无关的频次函数，可以用固定时间直接做单测。 */
object GuidePolicy { // 页面是否访问正确由调用方先验证。
    const val DAY_MILLIS = 24L * 60L * 60L * 1000L // 冷却一天始终是 24 小时。

    fun canShow( // 每次合格的原栈返回只调用一次。
        type: GuideType, // 本轮行为对应的组件。
        rules: GuideRules, // 最新的后台配置。
        record: GuideRecord, // 只含频次与停止标志的本地快照。
        nowMillis: Long, // 本次尝试的真实或测试时间。
        zone: ZoneId // 判断共享自然日的当前设备时区。
    ): Boolean { // 拒绝后不保存“以后弹”的任务。
        val own = if (type == GuideType.COMMON_TOOLS) record.commonTools else record.aiQuickNews // 只看本类型次数。
        if (!rules.enable || own.stopped || own.showCount !in 0..2) return false // 总开关、停止和三次封顶。
        val shared = record.lastAnyShownAtMillis // 取 A/B 最近的一次展示。
        if (nowMillis <= 0L || shared < 0L || nowMillis < shared) return false // 时钟倒退时保守拒绝。
        if (shared > 0L) { // 尚未展示过时无共享日期限制。
            val lastDate = Instant.ofEpochMilli(shared).atZone(zone).toLocalDate() // 之前的本地日期。
            val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate() // 当前的本地日期。
            if (lastDate == today) return false // A/B 当天合计至多一次。
        } // 共享日期判断结束。
        if (own.showCount == 0) return true // 首次符合行为即可展示。
        if (own.lastShownAtMillis <= 0L || nowMillis < own.lastShownAtMillis) return false // 损坏时间不当成新用户。
        val days = if (own.showCount == 1) rules.firstPopInterval else rules.secondPopInterval // 只会用到前两个间隔。
        if (days == null || days < 0L || days > Long.MAX_VALUE / DAY_MILLIS) return false // 缺失或溢出拒绝。
        return nowMillis - own.lastShownAtMillis >= days * DAY_MILLIS // 固定 24 小时比较。
    } // 结束本次纯规则判断。
} // 结束频次规则。
