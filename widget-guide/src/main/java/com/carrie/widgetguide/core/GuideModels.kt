package com.carrie.widgetguide.core

import java.time.Instant
import java.time.ZoneId

/** 两种自动引导：TOOLS 为 A（工具组件），AI_NEWS 为 B（AI 资讯组件）。 */
enum class GuideType { TOOLS, AI_NEWS }

/** 已完成、可持久化的行为资格；展示或暂时被频控拦截均不消费这些标记。 */
enum class Behavior {
    /** 从首页指定入口完成搜索。 */
    HOME_SEARCH,
    /** 从我的页进入书签、历史或下载。 */
    MINE_TOOL,
    /** 从首页 AI 快看频道进入资讯详情。 */
    HOME_AI_DETAIL
}

/** 当前允许展示的位置；比 PageKind 更粗，只用于选择弹窗候选。 */
enum class Surface {
    /** 非目标位置，例如搜索结果、设置或后台。 */
    NONE,
    /** 首页非 AI 频道。 */
    HOME_OTHER,
    /** 首页 AI 快看频道，优先评估 B，再评估 A。 */
    HOME_AI,
    /** 我的页，只评估 A。 */
    MINE
}

/**
 * 已校验的云配快照。A、B 共用间隔配置，各自依据自己的展示历史计算冷却。
 * @property enablePop 自动引导开关，默认关闭；关闭不清空行为和历史。
 * @property firstPopInterval 同类型第 1 次到第 2 次展示的间隔天数，每天固定 24 小时。
 * @property secondPopInterval 同类型第 2 次到第 3 次展示的间隔天数；全局三次上限仍生效。
 */
data class GuideConfig(
    val enablePop: Boolean = false,
    val firstPopInterval: Int = 1,
    val secondPopInterval: Int = 3
) {
    init {
        require(firstPopInterval >= 0 && secondPopInterval >= 0)
    }
}

/**
 * 已发生首帧绘制的展示记录；关闭、跳转失败均不撤销记录。
 * @property id 展示 ID，来自 PendingShow，用于首帧重复回调和写盘重试去重。
 * @property type 本次展示的组件类型，用于计算各自冷却。
 * @property shownAtMillis 首帧回调时的 Unix 毫秒时间，用于计算满 24h/72h 等时长。
 * @property localEpochDay 当时本地日期距 1970-01-01 的天数，用于 AB 合计自然日限制。
 */
data class ShowRecord(
    val id: String,
    val type: GuideType,
    val shownAtMillis: Long,
    val localEpochDay: Long
)

/**
 * 展示前写盘的预约，绝不等于已展示次数。
 * 重启后若仍存在预约，无法判定上次窗口是否画出，因此暂停自动引导。
 * @property id 一次展示尝试的唯一 ID，贯穿预约、首帧回调、关闭和失败重试。
 * @property type 已选中的引导类型。
 */
data class PendingShow(val id: String, val type: GuideType)

/**
 * 作为一个版本化 JSON 整体保存的业务状态。
 * @property behaviors 已完成的独立行为集合，永久保留到明确清除数据。
 * @property records A/B 共用展示历史，总数达到三次后停止自动引导。
 * @property pending 尚未完成首帧记录提交的预约；null 表示没有未决预约。
 */
data class GuideState(
    val behaviors: Set<Behavior> = emptySet(),
    val records: List<ShowRecord> = emptyList(),
    val pending: PendingShow? = null
) {
    /** 全局三次上限；只计算真实展示记录，不计算 pending。 */
    val exhausted: Boolean get() = records.size >= 3
}

/**
 * 宿主在某一瞬间的展示条件。Controller 写盘挂起后必须再次读取并逐字段比较。
 * @property hostId 逻辑宿主身份；配合宿主对象引用排除已经销毁/替换的 Activity。
 * @property version 位置或有效性改变时递增，识别“离开又回来”的变化；getter 不得递增。
 * @property surface 真正可见的底部 Tab 与首页频道组合，不能只读底部 Tab。
 * @property canPresent 当前是否允许尝试展示，例如已恢复前台、未保存 Fragment 状态。
 * 不要因本引导自身占用窗口就置 false，否则会触发自我取消。
 */
data class HostSnapshot(
    val hostId: String = "",
    val version: Long = 0,
    val surface: Surface = Surface.NONE,
    val canPresent: Boolean = false
)

/**
 * 一次一致的时钟快照，避免计算时跨零点造成日期和毫秒不一致。
 * @property millis Unix 毫秒时间；正式环境取系统时钟，测试可注入。
 * @property zone 该次计算采用的时区，用于本地日期和下一次零点。
 */
data class GuideTime(val millis: Long, val zone: ZoneId) {
    /** 当前本地日期。 */
    val date get() = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    /** 本地日期的整数表示；不是 millis / DAY_MILLIS。 */
    val epochDay get() = date.toEpochDay()
    /** 下一本地日期起点；夏令时变化时距离当前零点可能不是 24 小时。 */
    val nextMidnightMillis get() = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

/** 可替换时钟，使频控测试无需改系统时间或真的等待数天。 */
fun interface GuideClock {
    /** 获取同一次决策使用的毫秒时间和时区。 */
    fun now(): GuideTime
}

/** 正式默认时钟；每次查询读取最新系统时区。 */
object SystemGuideClock : GuideClock {
    override fun now() = GuideTime(System.currentTimeMillis(), ZoneId.systemDefault())
}

/** 策略拒绝原因，按 GuidePolicy 的检查顺序返回首个命中项。 */
enum class BlockReason {
    /** 云配未启用。 */
    DISABLED,
    /** A/B 合计已展示三次。 */
    LIMIT,
    /** 存在无法确认展示结果的预约，需要恢复决策。 */
    RECOVERY_REQUIRED,
    /** 宿主不可展示或当前位置不允许此类型。 */
    HOST,
    /** 当前位置对应的行为尚未完成。 */
    BEHAVIOR,
    /** 当前本地自然日已展示任一类型。 */
    DAILY,
    /** 此类型自己的冷却间隔未满。 */
    COOLDOWN,
    /** 已有记录的时间或本地日期在当前时间之后。 */
    CLOCK_ROLLBACK
}

/** 纯策略结果；不写存储、不启动窗口。 */
sealed interface Decision {
    /** 当前所有条件满足，但真正展示前仍须写预约并复核宿主。 */
    data object Allow : Decision
    /**
     * @property reason 首个阻断条件，供诊断和测试使用。
     * @property retryAtMillis 可定时重新评估的 Unix 毫秒时间；null 表示依赖外部事件刷新。
     */
    data class Block(val reason: BlockReason, val retryAtMillis: Long? = null) : Decision
}
