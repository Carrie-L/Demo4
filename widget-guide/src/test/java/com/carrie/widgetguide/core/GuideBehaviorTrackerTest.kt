package com.carrie.widgetguide.core

import org.junit.Assert.*
import org.junit.Test

/** 行为路径测试：覆盖入口、实例、绕行、恢复和导航去重。 */
class GuideBehaviorTrackerTest {
    /**
     * 每个 JUnit 用例独立的跟踪器，避免链路状态跨测试污染。
     */
    private val tracker = GuideBehaviorTracker()
    /**
     * 固定的首页逻辑实例。
     */
    private val home = PageRef("home", PageKind.HOME_OTHER)
    /**
     * 固定的搜索激活实例；不同实例测试会显式改 ID。
     */
    private val active = PageRef("active", PageKind.SEARCH_ACTIVE)
    /**
     * 搜索结果页实例。
     */
    private val result = PageRef("result", PageKind.SEARCH_RESULTS)
    /**
     * 用于模拟绕行的普通页面。
     */
    private val other = PageRef("other", PageKind.OTHER)
    /**
     * 生成测试导航 ID，与页面 ID 独立。
     */
    private var serial = 0
    /**
     * 模拟成功进入目标页面，每次生成新导航 ID 并返回识别结果。
     */
    private fun go(from: PageRef, to: PageRef, entry: EntryPoint = EntryPoint.OTHER) =
        tracker.entered(NavigationEvent((serial++).toString(), from, to, entry))

    /** 首页直接搜索按钮进入结果页，应产生 HOME_SEARCH。 */
    @Test fun `direct home search qualifies`() {
        tracker.visible(home)
        assertEquals(Behavior.HOME_SEARCH, go(home, result, EntryPoint.HOME_SEARCH_BUTTON))
    }
    /** 首页搜索框进入激活页时不算完成；该实例提交结果后才产生 HOME_SEARCH。 */
    @Test fun `home activation result qualifies`() {
        tracker.visible(home)
        assertNull(go(home, active, EntryPoint.HOME_SEARCH_BOX))
        assertEquals(Behavior.HOME_SEARCH, go(active, result, EntryPoint.SEARCH_SUBMIT))
    }
    /** 先经普通页面再进入搜索激活，不能继承首页来源。 */
    @Test fun `news before activation invalidates home origin`() {
        tracker.visible(home)
        go(home, other)
        go(other, active, EntryPoint.HOME_SEARCH_BOX)
        assertNull(go(active, result, EntryPoint.SEARCH_SUBMIT))
    }
    /** 激活后经过其他页再返回，未完成链路应失效。 */
    @Test fun `intermediate page between activation and results invalidates chain`() {
        tracker.visible(home); go(home, active, EntryPoint.HOME_SEARCH_BOX)
        go(active, other); tracker.visible(active)
        assertNull(go(active, result, EntryPoint.SEARCH_SUBMIT))
    }
    /** 激活页返回首页而未进入结果，不应授予搜索行为。 */
    @Test fun `back from activation without result does not qualify`() {
        tracker.visible(home); go(home, active, EntryPoint.HOME_SEARCH_BOX); tracker.visible(home)
        assertNull(go(active, result, EntryPoint.SEARCH_SUBMIT))
    }
    /** 页面类型相同但实例 ID 不同，不能借用旧激活页来源。 */
    @Test fun `different activation instance cannot reuse origin`() {
        tracker.visible(home); go(home, active, EntryPoint.HOME_SEARCH_BOX)
        assertNull(go(active.copy(id = "other-instance"), result, EntryPoint.SEARCH_SUBMIT))
    }
    /** 同一激活页后台恢复，未发生业务页切换，应保留链路。 */
    @Test fun `background resume of same activation preserves incomplete chain`() {
        tracker.visible(home); go(home, active, EntryPoint.HOME_SEARCH_BOX); tracker.visible(active)
        assertEquals(Behavior.HOME_SEARCH, go(active, result, EntryPoint.SEARCH_SUBMIT))
    }
    /** 重建跟踪器不恢复未完成路径，单独的提交事件不能解锁。 */
    @Test fun `process restart does not restore incomplete chain`() {
        val restarted = GuideBehaviorTracker(); restarted.visible(active)
        assertNull(restarted.entered(NavigationEvent("1", active, result, EntryPoint.SEARCH_SUBMIT)))
    }
    /** 同一个 navigationId 重复上报，只允许首次产生行为。 */
    @Test fun `duplicate navigation does not emit twice`() {
        tracker.visible(home)
        val event = NavigationEvent("same", home, result, EntryPoint.HOME_SEARCH_BUTTON)
        assertEquals(Behavior.HOME_SEARCH, tracker.entered(event)); assertNull(tracker.entered(event))
    }
    /** 书签/历史/下载只接受我的页指定入口；从首页进入不能解锁 MINE_TOOL。 */
    @Test fun `tools require mine entry and correct destination`() {
        val mine = PageRef("mine", PageKind.MINE)
        for (kind in listOf(PageKind.BOOKMARKS, PageKind.HISTORY, PageKind.DOWNLOADS)) {
            tracker.visible(mine)
            assertEquals(Behavior.MINE_TOOL, go(mine, PageRef(kind.name, kind), EntryPoint.MINE_TOOL))
        }
        tracker.visible(home)
        assertNull(go(home, PageRef("bookmark", PageKind.BOOKMARKS), EntryPoint.MINE_TOOL))
    }
    /** 只有首页 AI 频道的资讯入口生效，普通首页频道不生效。 */
    @Test fun `AI requires AI home channel`() {
        val ai = home.copy(kind = PageKind.HOME_AI)
        val news = PageRef("news", PageKind.NEWS_DETAIL)
        tracker.visible(home); assertNull(go(home, news, EntryPoint.AI_ARTICLE))
        tracker.visible(ai); assertEquals(Behavior.HOME_AI_DETAIL, go(ai, news, EntryPoint.AI_ARTICLE))
    }
}
