package com.carrie.widgetguide.core

enum class PageKind { HOME_OTHER, HOME_AI, MINE, SEARCH_ACTIVE, SEARCH_RESULTS, BOOKMARKS, HISTORY, DOWNLOADS, NEWS_DETAIL, OTHER }
data class PageRef(val id: String, val kind: PageKind)
enum class EntryPoint { HOME_SEARCH_BOX, HOME_SEARCH_BUTTON, SEARCH_SUBMIT, MINE_TOOL, AI_ARTICLE, OTHER }
data class NavigationEvent(val id: String, val from: PageRef, val to: PageRef, val entry: EntryPoint)

/** Call on the controller's serialized event loop. Only incomplete paths live here. */
class GuideBehaviorTracker {
    private var current: PageRef? = null
    private var searchActivation: PageRef? = null
    private val recentNavigationIds = ArrayDeque<String>()

    fun visible(page: PageRef) {
        if (current != page) searchActivation = null
        current = page
    }

    fun entered(event: NavigationEvent, satisfied: Set<Behavior> = emptySet()): Behavior? {
        if (event.id in recentNavigationIds) return null
        recentNavigationIds.addLast(event.id)
        if (recentNavigationIds.size > 32) recentNavigationIds.removeFirst()
        val sourceMatches = current == event.from
        val previousActivation = searchActivation
        searchActivation = null
        current = event.to
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

    fun clear() { current = null; searchActivation = null; recentNavigationIds.clear() }
}
