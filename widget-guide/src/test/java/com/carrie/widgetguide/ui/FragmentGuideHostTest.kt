package com.carrie.widgetguide.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.carrie.widgetguide.core.*
import com.carrie.widgetguide.runtime.PresentationCallbacks
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FragmentGuideHostTest {
    private val contents = GuideContentProvider {
        GuideContent("Title", "Description", android.R.drawable.ic_menu_search,
            "Preview", "demo://desktop", "demo://assistant")
    }
    private class Callbacks : PresentationCallbacks {
        var shown = 0; var closed = 0
        override fun onShown() { shown++ }
        override fun onClosed() { closed++ }
    }

    @Test fun `host rejects saved state and busy manager`() {
        val activityController = Robolectric.buildActivity(FragmentActivity::class.java)
        activityController.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        val activity = activityController.setup().get()
        val request = PendingShow("id", GuideType.TOOLS)
        val callbacks = Callbacks()
        val busy = FragmentGuideHost(activity, { HostSnapshot("h", 1, Surface.HOME_OTHER, true) }, contents,
            WidgetGuideNavigator { true }, managerAvailable = { false })
        assertFalse(busy.tryShow(request, callbacks))
        activityController.saveInstanceState(Bundle())
        val host = FragmentGuideHost(activity, { HostSnapshot("h", 1, Surface.HOME_OTHER, true) }, contents, WidgetGuideNavigator { true })
        assertFalse(host.tryShow(request, callbacks)); assertEquals(0, callbacks.shown)
        activityController.pause().stop().destroy()
    }

    @Test fun `first content draw counts once and duplicate tag is rejected`() {
        val activityController = Robolectric.buildActivity(FragmentActivity::class.java)
        activityController.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        val activity = activityController.setup().get()
        val host = FragmentGuideHost(activity, { HostSnapshot("h", 1, Surface.HOME_OTHER, true) }, contents, WidgetGuideNavigator { true })
        val callbacks = Callbacks()
        assertTrue(host.tryShow(PendingShow("id", GuideType.TOOLS), callbacks))
        assertEquals(0, callbacks.shown)
        assertFalse(host.tryShow(PendingShow("second", GuideType.AI_NEWS), Callbacks()))
        val fragment = activity.supportFragmentManager.findFragmentByTag(FragmentGuideHost.TAG) as WidgetGuideDialogFragment
        val decor = fragment.requireDialog().window!!.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST))
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        // ViewRoot dispatches this before drawing the content; invoking View.draw alone does not.
        decor.viewTreeObserver.dispatchOnDraw(); decor.viewTreeObserver.dispatchOnDraw()
        assertEquals(1, callbacks.shown)
        host.cancel("id")
        activity.supportFragmentManager.executePendingTransactions()
        assertEquals(1, callbacks.closed)
        activityController.pause().stop().destroy()
    }

    @Test fun `restored fragment without live owner cannot show window`() {
        val activityController = Robolectric.buildActivity(FragmentActivity::class.java)
        activityController.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        val activity = activityController.setup().get()
        val fragment = WidgetGuideDialogFragment.create(PendingShow("old", GuideType.TOOLS), contents.content(GuideType.TOOLS))
        // Same condition as process/config restoration: Android restored arguments, no callback owner.
        fragment.showNow(activity.supportFragmentManager, FragmentGuideHost.TAG)
        activity.supportFragmentManager.executePendingTransactions()
        assertFalse(fragment.dialog?.isShowing == true)
        activityController.pause().stop().destroy()
    }
}
