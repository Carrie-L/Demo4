package com.carrie.widgetguide.runtime

import com.carrie.widgetguide.core.HostSnapshot
import com.carrie.widgetguide.core.PendingShow

/**
 * Main-thread contract. tryShow must synchronously accept or reject; it must NOT enqueue a stale show.
 * A project-wide dialog manager should call controller.refresh() when it grants availability.
 * onShown is the first actual visible frame, not enqueue/show() invocation; then exactly one onClosed.
 */
interface GuideHost {
    val snapshot: HostSnapshot
    fun tryShow(request: PendingShow, callbacks: PresentationCallbacks): Boolean
    fun cancel(presentationId: String)
}

interface PresentationCallbacks {
    fun onShown()
    fun onClosed()
}

data class GuideStatus(val message: String, val state: com.carrie.widgetguide.core.GuideState? = null)
