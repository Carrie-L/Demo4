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
/** 用可控调度器与假仓库验证写盘竞态、回调幂等及暂停恢复。 */
class WidgetGuideControllerTest {
    /**
     * 可控内存仓库：gate 暂停预约写入，failRecord 模拟首帧记录落盘失败。
     */
    private class Repo(var state: GuideState) : GuideRepository {
        /**
         * 仅在新增预约时挂起，让测试在写盘窗口中切换宿主或云配。
         */
        var gate: CompletableDeferred<Unit>? = null
        /**
         * true 时新增展示记录抛 IOException，已有内存状态保持不变。
         */
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
    /**
     * 可手动发射配置的 StateFlow，测试配置变化与预约竞态。
     */
    private class Config(enabled: Boolean) : GuideConfigSource {
        override val config = MutableStateFlow(GuideConfig(enabled))
    }
    /**
     * 不创建真实窗口的宿主；测试手动调用 callbacks 模拟首帧和关闭。
     */
    private class Host : GuideHost {
        override var snapshot = HostSnapshot("host", 1, Surface.HOME_OTHER, true)
        /**
         * tryShow 调用数，用于证明多次刷新没有并发接纳。
         */
        var calls = 0
        /**
         * 模拟统一弹窗管理器接受或明确拒绝。
         */
        var accepts = true
        /**
         * 保存最近接纳请求的回调，供测试驱动生命周期。
         */
        var callbacks: PresentationCallbacks? = null
        /**
         * 保存请求 ID/类型，用于验证同 ID 重试和 B 优先级。
         */
        var last: PendingShow? = null
        override fun tryShow(request: PendingShow, callbacks: PresentationCallbacks): Boolean {
            calls++; this.callbacks = callbacks; last = request; return accepts
        }
        override fun cancel(presentationId: String) { callbacks?.onClosed() }
    }
    /**
     * 可直接推进的时钟；测试不等待真实时间，也不修改设备时间。
     */
    private class Clock : GuideClock {
        var time = GuideTime(Instant.parse("2026-10-10T04:00:00Z").toEpochMilli(), ZoneId.of("Asia/Shanghai"))
        override fun now() = time
    }
    /**
     * 预先满足首页搜索和 AI 资讯行为，聚焦控制器而不重复测试路径识别。
     */
    private val qualified = GuideState(setOf(Behavior.HOME_SEARCH, Behavior.HOME_AI_DETAIL))

    /** 已预约但未绘制时多次刷新，不增加次数、不重复请求窗口。 */
    @Test fun `reservation and repeat refresh do not count before actual display`() = runTest {
        val repo = Repo(qualified); val host = Host()
        val controller = WidgetGuideController(repo, Config(true), Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); runCurrent()
        repeat(5) { controller.refresh() }; runCurrent()
        assertEquals(1, host.calls); assertTrue(repo.state.records.isEmpty()); assertNotNull(repo.state.pending)
        controller.close()
    }
    /** 重复首帧加关闭回调最终只计一次，且不消费行为资格。 */
    @Test fun `duplicate shown callback and close count once`() = runTest {
        val repo = Repo(qualified); val host = Host()
        val controller = WidgetGuideController(repo, Config(true), Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); runCurrent()
        repeat(3) { host.callbacks!!.onShown() }; host.callbacks!!.onClosed(); runCurrent()
        assertEquals(1, repo.state.records.size); assertNull(repo.state.pending)
        assertEquals(1, host.calls); assertEquals(qualified.behaviors, repo.state.behaviors)
        controller.close()
    }
    /** 宿主明确拒绝时释放预约，名额恢复后无需重新做行为。 */
    @Test fun `rejected show releases reservation without consuming behavior`() = runTest {
        val repo = Repo(qualified); val host = Host().apply { accepts = false }
        val controller = WidgetGuideController(repo, Config(true), Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); runCurrent()
        assertNull(repo.state.pending); assertTrue(repo.state.records.isEmpty())
        host.accepts = true; controller.refresh(); runCurrent()
        assertNotNull(repo.state.pending); assertEquals(qualified.behaviors, repo.state.behaviors)
        controller.close()
    }
    /** 暂停预约写盘，期间使宿主失效，恢复写盘后必须取消旧请求。 */
    @Test fun `leaving page during disk reservation cancels stale show`() = runTest {
        val repo = Repo(qualified).apply { gate = CompletableDeferred() }; val host = Host()
        val controller = WidgetGuideController(repo, Config(true), Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); runCurrent()
        host.snapshot = host.snapshot.copy(version = 2, surface = Surface.NONE, canPresent = false)
        repo.gate!!.complete(Unit); runCurrent()
        assertEquals(0, host.calls); assertNull(repo.state.pending)
        controller.close()
    }
    /** 写盘期间关闭云配，完成预约后复核应阻止展示。 */
    @Test fun `config disabling during reservation prevents presentation`() = runTest {
        val repo = Repo(qualified).apply { gate = CompletableDeferred() }; val host = Host(); val config = Config(true)
        val controller = WidgetGuideController(repo, config, Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); runCurrent()
        config.config.value = GuideConfig(false); repo.gate!!.complete(Unit); runCurrent()
        assertEquals(0, host.calls); assertNull(repo.state.pending)
        controller.close()
    }
    /** 关闭云配仍记录行为；经过多页再开启，无需重做行为即可展示。 */
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
    /** 首轮显示并关闭后推进时钟，已有行为继续支持下一轮展示。 */
    @Test fun `cooldown expiry displays again without a new behavior`() = runTest {
        val repo = Repo(qualified); val host = Host(); val clock = Clock()
        val controller = WidgetGuideController(repo, Config(true), clock, dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); runCurrent(); host.callbacks!!.onShown(); host.callbacks!!.onClosed(); runCurrent()
        clock.time = clock.time.copy(millis = clock.time.millis + GuidePolicy.DAY_MILLIS)
        controller.refresh(); runCurrent()
        assertEquals(2, host.calls)
        controller.close()
    }
    /** 启动读到残留预约时暂停，不把它当次数，也不直接重新弹。 */
    @Test fun `unresolved persisted reservation freezes automatic presentation`() = runTest {
        val repo = Repo(qualified.copy(pending = PendingShow("crashed", GuideType.TOOLS))); val host = Host()
        val controller = WidgetGuideController(repo, Config(true), Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); runCurrent()
        assertEquals(0, host.calls); assertTrue(repo.state.records.isEmpty())
        assertTrue(controller.status.value.message.contains("Recovery required"))
        controller.close()
    }
    /** 首帧记录写入失败后暂停；刷新成功重试同 ID，不产生第二个窗口。 */
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
    /** AI 频道 A/B 均满足时只选 B，大量刷新不并发接纳 A。 */
    @Test fun `AI channel chooses B and refresh cannot admit A concurrently`() = runTest {
        val repo = Repo(qualified); val host = Host().apply { snapshot = snapshot.copy(surface = Surface.HOME_AI) }
        val controller = WidgetGuideController(repo, Config(true), Clock(), dispatcher = StandardTestDispatcher(testScheduler))
        controller.attach(host); repeat(20) { controller.refresh() }; runCurrent()
        assertEquals(GuideType.AI_NEWS, host.last!!.type); assertEquals(1, host.calls)
        controller.close()
    }
    /** 累计三次后即使发生合法新行为，也停止记录。 */
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
