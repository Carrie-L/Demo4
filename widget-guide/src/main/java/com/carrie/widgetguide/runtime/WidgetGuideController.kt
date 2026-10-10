package com.carrie.widgetguide.runtime

import com.carrie.widgetguide.core.*
import com.carrie.widgetguide.storage.GuideRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID

/**
 * 进程级自动引导协调器，唯一事件消费者串行处理行为、预约和展示结果。
 * UI 入口/回调要求在主线程；存储内部切 IO，期间主线程仍可更新 host 和配置。
 * @param repository 单进程唯一状态仓库，负责串行读改写和检查写盘结果。
 * @param configSource 已校验配置流；配置更新触发重新评估，不重置历史。
 * @param clock 决策和首帧记录共用的可注入时钟。
 * @param policy 纯规则对象，选择候选并执行频控。
 * @param dispatcher 事件循环调度器，正式使用 Main.immediate，测试替换为测试调度器。
 * @param reportError 存储或宿主异常的监控出口；上报自身抛错也不影响事件循环。
 */
class WidgetGuideController(
    private val repository: GuideRepository,
    private val configSource: GuideConfigSource,
    private val clock: GuideClock = SystemGuideClock,
    private val policy: GuidePolicy = GuidePolicy(),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val reportError: (Throwable) -> Unit = {}
) : AutoCloseable {
    /**
     * SupervisorJob 管理事件消费者、云配订阅和计时器；close 时统一取消。
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    /**
     * 不丢弃已进入/已展示事件的串行队列；宿主只应上报业务事件，不可在每帧刷新中调用。
     */
    private val events = Channel<Event>(Channel.UNLIMITED)
    /**
     * 仅保存内存中的未完成路径；成功行为另存入 repository。
     */
    private val tracker = GuideBehaviorTracker()
    /**
     * 最新连接宿主。attach/detach 立即更新引用，便于写盘挂起结束后发现宿主替换。
     */
    private var host: GuideHost? = null
    /**
     * 最近一次成功读写返回的持久状态快照，只由事件消费者更新。
     */
    private var state = GuideState()
    /**
     * 当前已接纳/正在展示的请求；同一时刻只能有一个，不等于次数已提交。
     */
    private var active: PendingShow? = null
    /**
     * 接纳 active 的原宿主；取消时必须通知原宿主，不能误操作新宿主。
     */
    private var activeHost: GuideHost? = null
    /**
     * 接纳时的宿主快照，用于检测位置、版本或展示有效性变化。
     */
    private var activeSnapshot: HostSnapshot? = null
    /**
     * 当前请求是否收到首帧回调。true 即使写盘失败也不能释放为“未展示”。
     */
    private var shown = false
    /**
     * 异常暂停标记；阻止继续采集行为和产生新弹窗，避免不确定状态下重复打扰。
     */
    private var fault = false
    /**
     * 首帧已经发生但可能尚未写盘的记录；重试保留原 ID、时间、日期。
     */
    private var retryRecord: ShowRecord? = null
    /**
     * 仅一个到期唤醒任务；每次评估先取消旧任务，按最新配置重新计算。
     */
    private var timer: Job? = null
    /**
     * 内部可写诊断状态，不作为业务判断的第二份真相。
     */
    private val mutableStatus = MutableStateFlow(GuideStatus("Loading"))
    /**
     * 只读诊断流，供开发面板/监控观察；message 不是稳定的业务协议。
     */
    val status: StateFlow<GuideStatus> = mutableStatus.asStateFlow()

    // 先读取持久状态再处理排队事件；启动残留预约没有自动恢复为“未展示”的安全依据。
    init {
        scope.launch {
            try {
                state = repository.read()
                // 无法区分“写预约后就崩溃”和“已展示但未记账就崩溃”，因此暂停。
                fault = state.pending != null
                publish(if (fault) "Recovery required: unresolved presentation" else "Ready")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
            for (event in events) {
                try { handle(event) } catch (e: CancellationException) { throw e }
                catch (e: Exception) { fail(e) }
            }
        }
        scope.launch { configSource.config.collect { events.send(Event.Refresh) } }
    }

    /**
     * 主线程连接当前宿主并排队复核；旧宿主已接纳的窗口会在评估时被取消。
     */
    fun attach(host: GuideHost) { this.host = host; refresh() }
    /**
     * 只解除同一个宿主引用，防止旧 Activity 销毁时误解绑新 Activity。
     */
    fun detach(host: GuideHost) {
        if (this.host === host) this.host = null
        events.trySend(Event.Refresh)
    }
    /**
     * 上报实际可见业务页；返回、Tab/频道切换也调用，不能只在目标 Activity 创建时调用。
     */
    fun visible(page: PageRef) { events.trySend(Event.Visible(page)) }
    /**
     * 上报已进入目标页的导航事实；目标页应先 entered 再 visible。
     */
    fun entered(event: NavigationEvent) { events.trySend(Event.Entered(event)) }
    /**
     * 请求重算资格：云配、生命周期、弹窗管理器名额、时间/时区变化均可触发。
     * 若首帧记录此前写入失败，先用原 ID 重试；此方法不清空损坏历史或未决预约。
     */
    fun refresh() { events.trySend(Event.Refresh) }

    /**
     * 按顺序处理一个事件，完成后统一评估。过期展示 ID 和重复首帧直接忽略。
     * 行为去重后才写盘；累计三次后清空跟踪器并停止记录后续行为。
     */
    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Visible -> if (!state.exhausted && !fault) tracker.visible(event.page)
            is Event.Entered -> if (!state.exhausted && !fault) {
                tracker.entered(event.navigation, state.behaviors)?.let { behavior ->
                    if (behavior !in state.behaviors) state = repository.update {
                        it.copy(behaviors = it.behaviors + behavior)
                    }
                }
            }
            is Event.Shown -> {
                if (active?.id != event.id || shown) return
                // 先记内存事实再写盘，避免写盘失败后 Closed 错把预约释放掉。
                shown = true
                val time = event.time
                retryRecord = ShowRecord(event.id, requireNotNull(active).type, time.millis, time.epochDay)
                commitShown()
            }
            is Event.Closed -> {
                if (active?.id != event.id) return
                try {
                    // 只有明确尚未收到首帧，关闭才可以不计数地释放预约。
                    if (!shown) state = repository.update {
                        if (it.pending?.id == event.id) it.copy(pending = null) else it
                    }
                } finally {
                    active = null; activeHost = null; activeSnapshot = null
                }
            }
            Event.Refresh -> if (retryRecord != null) commitShown()
        }
        if (state.exhausted) tracker.clear()
        evaluate()
    }

    /**
     * 将预约替换为真实展示记录。若同 ID 已存在，只清预约，绝不重复加次数。
     * 写入失败由外层设置 fault，retryRecord 保留，下一次 Refresh 可重试同一记录。
     */
    private suspend fun commitShown() {
        val record = retryRecord ?: return
        state = repository.update {
            if (it.records.any { r -> r.id == record.id }) it.copy(pending = null)
            else {
                check(it.pending?.id == record.id) { "Presentation reservation lost" }
                it.copy(records = it.records + record, pending = null)
            }
        }
        retryRecord = null
        fault = false
        publish("Displayed ${record.type}; total=${state.records.size}")
    }

    /**
     * 完整展示流程：处理旧窗口 → 候选决策 → 保存预约 → 复核最新快照 → 同步请求宿主。
     * 只有 DAILY/COOLDOWN 提供定时唤醒；其他拒绝依赖宿主/配置等外部事件。
     * IO 挂起结束后复核 host 引用、整份 snapshot、配置与当前时间，防止过期展示。
     */
    private suspend fun evaluate() {
        timer?.cancel(); timer = null
        if (fault) return
        val currentHost = host
        val snapshot = currentHost?.snapshot ?: HostSnapshot()
        if (active != null) {
            // 发现宿主/位置/配置失效就请求取消；绘制前还须校验行为和频控。
            val eligibleBeforeDraw = shown || policy.evaluate(requireNotNull(active).type,
                state.copy(pending = null), configSource.config.value, snapshot, clock.now()) == Decision.Allow
            if (currentHost !== activeHost || snapshot != activeSnapshot ||
                !configSource.config.value.enablePop || !eligibleBeforeDraw) {
                activeHost?.cancel(requireNotNull(active).id)
            }
            return
        }
        if (state.exhausted) { publish("Stopped: total limit reached"); return }
        val time = clock.now()
        val decisions = policy.candidates(snapshot.surface).map { type ->
            type to policy.evaluate(type, state, configSource.config.value, snapshot, time)
        }
        val type = decisions.firstOrNull { it.second == Decision.Allow }?.first
        if (type == null || currentHost == null) {
            publish(decisions.joinToString { "${it.first}: ${it.second}" }.ifBlank { "Waiting for target page" })
            decisions.mapNotNull { (it.second as? Decision.Block)?.retryAtMillis }.minOrNull()?.let { next ->
                timer = scope.launch { delay((next - time.millis).coerceAtLeast(1)); events.send(Event.Refresh) }
            }
            return
        }
        val request = PendingShow(UUID.randomUUID().toString(), type)
        state = repository.update { check(it.pending == null); it.copy(pending = request) }
        // 前面的 repository.update 会挂起；此处不能继续使用写盘前的快照。
        val latest = currentHost.snapshot
        val stillAllowed = host === currentHost && snapshot == latest && policy.evaluate(
            type, state.copy(pending = null), configSource.config.value, latest, clock.now()
        ) == Decision.Allow
        if (!stillAllowed) {
            state = repository.update { it.copy(pending = null) }
            events.trySend(Event.Refresh)
            return
        }
        active = request; activeHost = currentHost; activeSnapshot = latest; shown = false
        val accepted = try {
            currentHost.tryShow(request, object : PresentationCallbacks {
                override fun onShown() { events.trySend(Event.Shown(request.id, clock.now())) }
                override fun onClosed() { events.trySend(Event.Closed(request.id)) }
            })
        } catch (e: Exception) {
            // 抛异常不能证明窗口没出现：保留预约并暂停，不能按普通拒绝释放。
            fail(e); return
        }
        // false 必须表示没有创建/排队任何窗口；明确拒绝可释放预约，等待名额通知。
        if (!accepted) {
            state = repository.update { it.copy(pending = null) }
            active = null; activeHost = null; activeSnapshot = null
            publish("Waiting for dialog manager")
        }
    }

    /**
     * 发布消息和当前已确认状态，便于定位正在等待哪个条件。
     */
    private fun publish(message: String) { mutableStatus.value = GuideStatus(message, state) }
    /**
     * 保守暂停新展示并报告异常；不静默清零、不把预约伪装成展示次数。
     */
    private fun fail(error: Exception) {
        fault = true; timer?.cancel(); publish("Storage/presentation paused: ${error.javaClass.simpleName}")
        runCatching { reportError(error) }
    }
    /**
     * 主线程关闭控制器：取消窗口、定时器、协程和内存路径，释放宿主引用。
     * 不清除磁盘历史；调试重置由 Application 显式执行 repository.update。
     */
    override fun close() {
        active?.let { activeHost?.cancel(it.id) }
        timer?.cancel(); scope.cancel(); events.close(); tracker.clear()
        host = null; active = null; activeHost = null; activeSnapshot = null
    }

    /**
     * 进程内串行事件协议；不持久化事件队列，重启只恢复 GuideState。
     */
    private sealed interface Event {
        /**
         * 重新判断资格，或重试尚未落盘的首帧记录。
         */
        data object Refresh : Event
        /**
         * 实际可见页面变化，影响未完成链路。
         */
        data class Visible(val page: PageRef) : Event
        /**
         * 成功进入目标页面，可能产生新的持久行为。
         */
        data class Entered(val navigation: NavigationEvent) : Event
        /**
         * 某展示 ID 已发生首帧；time 在回调时捕获，而非排队处理时捕获。
         */
        data class Shown(val id: String, val time: GuideTime) : Event
        /**
         * 某展示 ID 的窗口结束；未绘制时释放预约，已绘制时保留计数事实。
         */
        data class Closed(val id: String) : Event
    }
}
