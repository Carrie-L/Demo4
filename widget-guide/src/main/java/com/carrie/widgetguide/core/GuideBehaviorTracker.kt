package com.carrie.widgetguide.core

/** 业务页面类型，用于识别行为路径；与 Activity 类名、路由地址无关。 */
enum class PageKind {
    /** 首页非 AI 频道，包括推荐、体育、地区。 */
    HOME_OTHER,
    /** 首页的 AI 快看频道。 */
    HOME_AI,
    /** 底部“我的”页。 */
    MINE,
    /** 搜索激活页：已进入搜索界面，但尚未进入结果页。 */
    SEARCH_ACTIVE,
    /** 搜索结果页。进入这里仍须结合来源和入口判断是否满足首页搜索行为。 */
    SEARCH_RESULTS,
    /** 书签页，我的工具入口的合法目标。 */
    BOOKMARKS,
    /** 历史页，我的工具入口的合法目标。 */
    HISTORY,
    /** 下载页，我的工具入口的合法目标。 */
    DOWNLOADS,
    /** 资讯详情；只有 AI 频道的指定入口进入才满足 AI 行为。 */
    NEWS_DETAIL,
    /** 其余业务页面，也必须上报以切断绕行的未完成搜索链路。 */
    OTHER
}

/**
 * 一个逻辑页面实例的身份。相等判断同时比较 id 和 kind。
 *
 * @property id 页面实例唯一标识。两个搜索激活页必须不同；同一页返回、旋转重建应保持不变。
 * 首页在同一 Activity 内切 Tab/频道，也应使用不同逻辑页面 ID，不能只用 Activity 类名。
 * @property kind 页面的业务类型；只说明“是哪类页”，不负责区分同类页面的不同实例。
 */
data class PageRef(val id: String, val kind: PageKind)

/** 触发此次导航的具体业务入口；由可信的内部路由适配层填写。 */
enum class EntryPoint {
    /** 首页搜索框，合法目标是搜索激活页。 */
    HOME_SEARCH_BOX,
    /** 首页直接搜索按钮，合法目标是搜索结果页。 */
    HOME_SEARCH_BUTTON,
    /** 搜索激活页提交搜索，需匹配之前保存的同一个激活页实例。 */
    SEARCH_SUBMIT,
    /** 我的页的书签、历史或下载入口。 */
    MINE_TOOL,
    /** 首页 AI 快看频道的资讯入口。 */
    AI_ARTICLE,
    /** 普通跳转：不授予上述行为资格，但会推进当前页面并清除未完成链路。 */
    OTHER
}

/**
 * 一次已进入目标业务页的导航事实，而非“点击了按钮”或“调用了 ARouter”。
 * 目标页先上报 entered(event)，然后再上报 visible(to)；路由失败不能上报 entered。
 *
 * @property id 本次导航的唯一 ID，用于短期去重；重传同一事件要复用，新导航要新建。
 * 与 PageRef.id 含义不同：前者标识“跳转动作”，后者标识“页面实例”。
 * @property from 跳转前实际可见的来源页，必须与跟踪器的 current 完整匹配。
 * @property to 已经进入的目标页；后续从该页导航时，将其作为下一事件的 from。
 * @property entry 用户通过哪个入口进入；不能仅凭目标是结果页就认定完成首页搜索。
 */
data class NavigationEvent(val id: String, val from: PageRef, val to: PageRef, val entry: EntryPoint)

/**
 * 识别三类行为的纯 Kotlin 跟踪器，只保存未完成路径和短期事件去重信息。
 * 由 Controller 单一事件循环调用，不自行加锁、不持久化、不负责频控。
 * 已完成行为由 Controller 存入 GuideState；页面离开不会撤销已完成资格。
 */
class GuideBehaviorTracker {
    /**
     * 最近一次真实可见或成功进入的页面；验证事件 from 的依据。
     */
    private var current: PageRef? = null
    /**
     * 仅保存从首页搜索框直接进入的激活页实例；任何绕行都会清空。
     */
    private var searchActivation: PageRef? = null
    /**
     * 最近 32 次导航 ID 的内存去重窗口；不承担跨进程事件去重。
     */
    private val recentNavigationIds = ArrayDeque<String>()

    /**
     * 更新实际可见页；同页恢复不打断链路，换到另一实例则清除未完成搜索。
     * 返回旧页只调用此方法，不伪造一次新导航。
     */
    fun visible(page: PageRef) {
        if (current != page) searchActivation = null
        current = page
    }

    /**
     * 消费已成功进入目标页的导航事实，返回本次识别出的行为或 null。
     * @param satisfied 已持久化的行为；搜索已完成时无须再保存中间激活页。
     * 处理顺序：导航 ID 去重 → 核对真实来源 → 清除旧链路 → 按入口和目标识别。
     * 返回 Behavior 不等于允许弹窗；Controller 先保存行为，再统一评估频控。
     */
    fun entered(event: NavigationEvent, satisfied: Set<Behavior> = emptySet()): Behavior? {
        if (event.id in recentNavigationIds) return null
        recentNavigationIds.addLast(event.id)
        if (recentNavigationIds.size > 32) recentNavigationIds.removeFirst()
        // 校验“完整实例”，不能只比较 PageKind；先保存旧激活页，再清除旧链路。
        val sourceMatches = current == event.from
        val previousActivation = searchActivation
        searchActivation = null
        current = event.to
        // 即使来源不匹配，也更新到已报告的目标页，避免旧资格链路继续污染后续事件。
        if (!sourceMatches) return null
        val fromHome = event.from.kind in setOf(PageKind.HOME_OTHER, PageKind.HOME_AI)
        return when {
            fromHome && event.entry == EntryPoint.HOME_SEARCH_BOX && event.to.kind == PageKind.SEARCH_ACTIVE -> {
                if (Behavior.HOME_SEARCH !in satisfied) searchActivation = event.to
                null
            }
            fromHome && event.entry == EntryPoint.HOME_SEARCH_BUTTON && event.to.kind == PageKind.SEARCH_RESULTS -> Behavior.HOME_SEARCH
            event.from == previousActivation && event.entry == EntryPoint.SEARCH_SUBMIT && event.to.kind == PageKind.SEARCH_RESULTS -> Behavior.HOME_SEARCH
            event.from.kind == PageKind.MINE && event.entry == EntryPoint.MINE_TOOL &&
                event.to.kind in setOf(PageKind.BOOKMARKS, PageKind.HISTORY, PageKind.DOWNLOADS) -> Behavior.MINE_TOOL
            event.from.kind == PageKind.HOME_AI && event.entry == EntryPoint.AI_ARTICLE && event.to.kind == PageKind.NEWS_DETAIL -> Behavior.HOME_AI_DETAIL
            else -> null
        }
    }

    /**
     * 清空内存路径和短期去重信息；达到总次数上限或关闭控制器时调用。
     */
    fun clear() { current = null; searchActivation = null; recentNavigationIds.clear() }
}
