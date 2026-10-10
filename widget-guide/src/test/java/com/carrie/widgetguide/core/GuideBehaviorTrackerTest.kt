package com.carrie.widgetguide.core

import org.junit.Assert.*
import org.junit.Test

class GuideBehaviorTrackerTest {
    private val tracker = GuideBehaviorTracker()
    private val home = PageRef("home", PageKind.HOME_OTHER)
    private val active = PageRef("active", PageKind.SEARCH_ACTIVE)
    private val result = PageRef("result", PageKind.SEARCH_RESULTS)
    private val other = PageRef("other", PageKind.OTHER)
    private var serial = 0
    private fun go(from: PageRef, to: PageRef, entry: EntryPoint = EntryPoint.OTHER) =
        tracker.entered(NavigationEvent((serial++).toString(), from, to, entry))

    @Test fun `direct home search qualifies`() {
        tracker.visible(home)
        assertEquals(Behavior.HOME_SEARCH, go(home, result, EntryPoint.HOME_SEARCH_BUTTON))
    }
    @Test fun `home activation result qualifies`() {
        tracker.visible(home)
        assertNull(go(home, active, EntryPoint.HOME_SEARCH_BOX))
        assertEquals(Behavior.HOME_SEARCH, go(active, result, EntryPoint.SEARCH_SUBMIT))
    }
    @Test fun `news before activation invalidates home origin`() {
        tracker.visible(home)
        go(home, other)
        go(other, active, EntryPoint.HOME_SEARCH_BOX)
        assertNull(go(active, result, EntryPoint.SEARCH_SUBMIT))
    }
    @Test fun `intermediate page between activation and results invalidates chain`() {
        tracker.visible(home); go(home, active, EntryPoint.HOME_SEARCH_BOX)
        go(active, other); tracker.visible(active)
        assertNull(go(active, result, EntryPoint.SEARCH_SUBMIT))
    }
    @Test fun `back from activation without result does not qualify`() {
        tracker.visible(home); go(home, active, EntryPoint.HOME_SEARCH_BOX); tracker.visible(home)
        assertNull(go(active, result, EntryPoint.SEARCH_SUBMIT))
    }
    @Test fun `different activation instance cannot reuse origin`() {
        tracker.visible(home); go(home, active, EntryPoint.HOME_SEARCH_BOX)
        assertNull(go(active.copy(id = "other-instance"), result, EntryPoint.SEARCH_SUBMIT))
    }
    @Test fun `background resume of same activation preserves incomplete chain`() {
        tracker.visible(home); go(home, active, EntryPoint.HOME_SEARCH_BOX); tracker.visible(active)
        assertEquals(Behavior.HOME_SEARCH, go(active, result, EntryPoint.SEARCH_SUBMIT))
    }
    @Test fun `process restart does not restore incomplete chain`() {
        val restarted = GuideBehaviorTracker(); restarted.visible(active)
        assertNull(restarted.entered(NavigationEvent("1", active, result, EntryPoint.SEARCH_SUBMIT)))
    }
    @Test fun `duplicate navigation does not emit twice`() {
        tracker.visible(home)
        val event = NavigationEvent("same", home, result, EntryPoint.HOME_SEARCH_BUTTON)
        assertEquals(Behavior.HOME_SEARCH, tracker.entered(event)); assertNull(tracker.entered(event))
    }
    @Test fun `tools require mine entry and correct destination`() {
        val mine = PageRef("mine", PageKind.MINE)
        for (kind in listOf(PageKind.BOOKMARKS, PageKind.HISTORY, PageKind.DOWNLOADS)) {
            tracker.visible(mine)
            assertEquals(Behavior.MINE_TOOL, go(mine, PageRef(kind.name, kind), EntryPoint.MINE_TOOL))
        }
        tracker.visible(home)
        assertNull(go(home, PageRef("bookmark", PageKind.BOOKMARKS), EntryPoint.MINE_TOOL))
    }
    @Test fun `AI requires AI home channel`() {
        val ai = home.copy(kind = PageKind.HOME_AI)
        val news = PageRef("news", PageKind.NEWS_DETAIL)
        tracker.visible(home); assertNull(go(home, news, EntryPoint.AI_ARTICLE))
        tracker.visible(ai); assertEquals(Behavior.HOME_AI_DETAIL, go(ai, news, EntryPoint.AI_ARTICLE))
    }
}
