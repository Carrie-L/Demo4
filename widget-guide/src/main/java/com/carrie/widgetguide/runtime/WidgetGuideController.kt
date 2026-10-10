package com.carrie.widgetguide.runtime

import com.carrie.widgetguide.core.*
import com.carrie.widgetguide.storage.GuideRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID

/**
 * Process-scoped controller. Public entry points and GuideHost callbacks run on Main.
 * Repository work runs on IO; only one event consumer mutates domain state.
 * The host snapshot is read again AFTER every suspended reservation write.
 */
class WidgetGuideController(
    private val repository: GuideRepository,
    private val configSource: GuideConfigSource,
    private val clock: GuideClock = SystemGuideClock,
    private val policy: GuidePolicy = GuidePolicy(),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val reportError: (Throwable) -> Unit = {}
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val tracker = GuideBehaviorTracker()
    private var host: GuideHost? = null
    private var state = GuideState()
    private var active: PendingShow? = null
    private var activeHost: GuideHost? = null
    private var activeSnapshot: HostSnapshot? = null
    private var shown = false
    private var fault = false
    private var retryRecord: ShowRecord? = null
    private var timer: Job? = null
    private val mutableStatus = MutableStateFlow(GuideStatus("Loading"))
    val status: StateFlow<GuideStatus> = mutableStatus.asStateFlow()

    init {
        scope.launch {
            try {
                state = repository.read()
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

    fun attach(host: GuideHost) { this.host = host; refresh() }
    fun detach(host: GuideHost) {
        if (this.host === host) this.host = null
        events.trySend(Event.Refresh)
    }
    fun visible(page: PageRef) { events.trySend(Event.Visible(page)) }
    fun entered(event: NavigationEvent) { events.trySend(Event.Entered(event)) }
    fun refresh() { events.trySend(Event.Refresh) }

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
                shown = true
                val time = event.time
                retryRecord = ShowRecord(event.id, requireNotNull(active).type, time.millis, time.epochDay)
                commitShown()
            }
            is Event.Closed -> {
                if (active?.id != event.id) return
                try {
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

    private suspend fun evaluate() {
        timer?.cancel(); timer = null
        if (fault) return
        val currentHost = host
        val snapshot = currentHost?.snapshot ?: HostSnapshot()
        if (active != null) {
            // Cancel before the next frame if the host/location/config has become invalid.
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
            // An exception cannot prove that no window appeared: retain reservation and fail closed.
            fail(e); return
        }
        if (!accepted) {
            state = repository.update { it.copy(pending = null) }
            active = null; activeHost = null; activeSnapshot = null
            publish("Waiting for dialog manager")
        }
    }

    private fun publish(message: String) { mutableStatus.value = GuideStatus(message, state) }
    private fun fail(error: Exception) {
        fault = true; timer?.cancel(); publish("Storage/presentation paused: ${error.javaClass.simpleName}")
        runCatching { reportError(error) }
    }
    override fun close() {
        active?.let { activeHost?.cancel(it.id) }
        timer?.cancel(); scope.cancel(); events.close(); tracker.clear()
        host = null; active = null; activeHost = null; activeSnapshot = null
    }

    private sealed interface Event {
        data object Refresh : Event
        data class Visible(val page: PageRef) : Event
        data class Entered(val navigation: NavigationEvent) : Event
        data class Shown(val id: String, val time: GuideTime) : Event
        data class Closed(val id: String) : Event
    }
}
