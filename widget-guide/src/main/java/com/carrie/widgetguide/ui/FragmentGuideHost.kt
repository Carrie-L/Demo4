package com.carrie.widgetguide.ui

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.carrie.widgetguide.core.*
import com.carrie.widgetguide.runtime.*

/** Replace/adapt this host at the existing dialog manager boundary in the production app. */
class FragmentGuideHost(
    private val activity: FragmentActivity,
    private val snapshotProvider: () -> HostSnapshot,
    private val contents: GuideContentProvider,
    private val navigator: WidgetGuideNavigator,
    private val managerAvailable: () -> Boolean = { true }
) : GuideHost {
    override val snapshot get() = snapshotProvider()

    override fun tryShow(request: PendingShow, callbacks: PresentationCallbacks): Boolean {
        val manager = activity.supportFragmentManager
        if (!snapshot.canPresent || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            activity.isFinishing || activity.isDestroyed || manager.isStateSaved ||
            !managerAvailable() || manager.findFragmentByTag(TAG) != null) return false
        val dialog = WidgetGuideDialogFragment.create(request, contents.content(request.type))
        dialog.callbacks = callbacks
        dialog.navigator = navigator
        dialog.showNow(manager, TAG)
        return true
    }

    override fun cancel(presentationId: String) {
        val fragment = activity.supportFragmentManager.findFragmentByTag(TAG) as? WidgetGuideDialogFragment
        if (fragment?.presentationId == presentationId) fragment.dismissAllowingStateLoss()
    }

    companion object { const val TAG = "browser.widget.guide" }
}
