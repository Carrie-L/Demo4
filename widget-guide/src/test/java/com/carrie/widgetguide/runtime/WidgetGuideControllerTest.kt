package com.carrie.widgetguide.runtime

import com.carrie.widgetguide.core.*
import com.carrie.widgetguide.storage.GuideRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetGuideControllerTest {
    private class Repo(var state: GuideState) : GuideRepository {
        var gate: CompletableDeferred<Unit>? = null
        var failRecord = false
        override suspend fun read() = state
        override suspend fun update(transform: (GuideState) -> GuideState): GuideState {
            val next = transform(state)
            if (next.pending != null && state.pending == null) gate?.await()
            if (failRecord && next.records.size > state.records.size) throw IOException("disk full")
            state = next
            return state
        }
    }
    private class Config(enabled: Boolean) : GuideConfigSource {
        override val config = MutableStateFlow(GuideConfig(enabled))
    }
    private class Host : GuideHost {
        override var snapshot = HostSnapshot("host", 1, Surface.HOME_OTHER, true)
        var calls = 0
        var accepts = true
        var callbacks: PresentationCallbacks? = null
        var last: PendingShow? = null
        override fun tryShow(request: PendingShow, callbacks: PresentationCallbacks): Boolean {
            calls++; this.callbacks = callbacks; last = request; return accepts
        }
        override fun cancel(presentationId: String) { callbacks?.onClosed() }
    }
    private class Clock : GuideClock {
        var time = GuideTime(Instant.parse("2026-10-10T04:00:00Z").toEpochMilli(), ZoneId.of("Asia/Shanghai"))
        override fun now() = time
    }
    private val qualified = GuideState(setOf(Behavior.HOME_SEARCH, Behavior.HOME_AI_DETAIL))

    @Test fun `reservation and repeat refresh do not count before actual display`() = runTest {
        val repo = Repo(qualified); val host = Host()
        val controller = WidgetGuideController(repo, Config(true), Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); runCurrent()
        repeat(5) { controller.refresh() }; runCurrent()
        assertEquals(1, host.calls); assertTrue(repo.state.records.isEmpty()); assertNotNull(repo.state.pending)
        controller.close()
    }
    @Test fun `duplicate shown callback and close count once`() = runTest {
        val repo = Repo(qualified); val host = Host()
        val controller = WidgetGuideController(repo, Config(true), Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); runCurrent()
        repeat(3) { host.callbacks!!.onShown() }; host.callbacks!!.onClosed(); runCurrent()
        assertEquals(1, repo.state.records.size); assertNull(repo.state.pending)
        assertEquals(1, host.calls); assertEquals(qualified.behaviors, repo.state.behaviors)
        controller.close()
    }
    @Test fun `rejected show releases reservation without consuming behavior`() = runTest {
        val repo = Repo(qualified); val host = Host().apply { accepts = false }
        val controller = WidgetGuideController(repo, Config(true), Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); runCurrent()
        assertNull(repo.state.pending); assertTrue(repo.state.records.isEmpty())
        host.accepts = true; controller.refresh(); runCurrent()
        assertNotNull(repo.state.pending); assertEquals(qualified.behaviors, repo.state.behaviors)
        controller.close()
    }
    @Test fun `leaving page during disk reservation cancels stale show`() = runTest {
        val repo = Repo(qualified).apply { gate = CompletableDeferred() }; val host = Host()
        val controller = WidgetGuideController(repo, Config(true), Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); runCurrent()
        host.snapshot = host.snapshot.copy(version = 2, surface = Surface.NONE, canPresent = false)
        repo.gate!!.complete(Unit); runCurrent()
        assertEquals(0, host.calls); assertNull(repo.state.pending)
        controller.close()
    }
    @Test fun `config disabling during reservation prevents presentation`() = runTest {
        val repo = Repo(qualified).apply { gate = CompletableDeferred() }; val host = Host(); val config = Config(true)
        val controller = WidgetGuideController(repo, config, Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); runCurrent()
        config.config.value = GuideConfig(false); repo.gate!!.complete(Unit); runCurrent()
        assertEquals(0, host.calls); assertNull(repo.state.pending)
        controller.close()
    }
    @Test fun `trigger while disabled survives navigation and later config enable`() = runTest {
        val repo = Repo(GuideState()); val host = Host(); val config = Config(false)
        val controller = WidgetGuideController(repo, config, Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        val home = PageRef("home", PageKind.HOME_OTHER); val result = PageRef("result", PageKind.SEARCH_RESULTS)
        controller.visible(home); controller.entered(NavigationEvent("search", home, result, EntryPoint.HOME_SEARCH_BUTTON))
        repeat(8) { controller.visible(PageRef("other-$it", PageKind.OTHER)) }
        controller.attach(host); runCurrent()
        assertEquals(setOf(Behavior.HOME_SEARCH), repo.state.behaviors); assertEquals(0, host.calls)
        config.config.value = GuideConfig(true); runCurrent()
        assertEquals(1, host.calls)
        controller.close()
    }
    @Test fun `cooldown expiry displays again without a new behavior`() = runTest {
        val repo = Repo(qualified); val host = Host(); val clock = Clock()
        val controller = WidgetGuideController(repo, Config(true), clock, dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); runCurrent(); host.callbacks!!.onShown(); host.callbacks!!.onClosed(); runCurrent()
        clock.time = clock.time.copy(millis = clock.time.millis + GuidePolicy.DAY_MILLIS)
        controller.refresh(); runCurrent()
        assertEquals(2, host.calls)
        controller.close()
    }
    @Test fun `unresolved persisted reservation freezes automatic presentation`() = runTest {
        val repo = Repo(qualified.copy(pending = PendingShow("crashed", GuideType.TOOLS))); val host = Host()
        val controller = WidgetGuideController(repo, Config(true), Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); runCurrent()
        assertEquals(0, host.calls); assertTrue(repo.state.records.isEmpty())
        assertTrue(controller.status.value.message.contains("Recovery required"))
        controller.close()
    }
    @Test fun `disk failure after showing blocks new displays and retries same ID`() = runTest {
        val repo = Repo(qualified); val host = Host()
        val controller = WidgetGuideController(repo, Config(true), Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); runCurrent()
        repo.failRecord = true; host.callbacks!!.onShown(); host.callbacks!!.onClosed(); runCurrent()
        assertEquals(1, host.calls); assertTrue(repo.state.records.isEmpty()); assertNotNull(repo.state.pending)
        val originalId = host.last!!.id
        repo.failRecord = false; controller.refresh(); runCurrent()
        assertEquals(originalId, repo.state.records.single().id); assertNull(repo.state.pending)
        assertEquals(1, host.calls)
        controller.close()
    }
    @Test fun `AI channel chooses B and refresh cannot admit A concurrently`() = runTest {
        val repo = Repo(qualified); val host = Host().apply { snapshot = snapshot.copy(surface = Surface.HOME_AI) }
        val controller = WidgetGuideController(repo, Config(true), Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); repeat(20) { controller.refresh() }; runCurrent()
        assertEquals(GuideType.AI_NEWS, host.last!!.type); assertEquals(1, host.calls)
        controller.close()
    }
    @Test fun `after total cap new behaviors are not recorded`() = runTest {
        val clock = Clock()
        val repo = Repo(GuideState(records = (1..3).map { ShowRecord("$it", GuideType.TOOLS, clock.time.millis, clock.time.epochDay) }))
        val controller = WidgetGuideController(repo, Config(true), clock, dispatcher = StandardTestDispatcher(testScheduler))
        val mine = PageRef("mine", PageKind.MINE)
        controller.visible(mine); controller.entered(NavigationEvent("n", mine, PageRef("b", PageKind.BOOKMARKS), EntryPoint.MINE_TOOL))
        runCurrent(); assertTrue(repo.state.behaviors.isEmpty())
        controller.close()
    }
}
