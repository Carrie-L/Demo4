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
/** 在 Robolectric API 29 上验证 Fragment 宿主接纳与绘制回调契约。 */
class FragmentGuideHostTest {
    /**
     * 使用系统 Drawable 和固定测试链接，避免依赖宿主产品资源。
     */
    private val contents = GuideContentProvider {
        GuideContent("Title", "Description", android.R.drawable.ic_menu_search,
            "Preview", "demo://desktop", "demo://assistant")
    }
    /**
     * 计数型回调替身，分别验证首帧与关闭的调用次数。
     */
    private class Callbacks : PresentationCallbacks {
        /**
         * 首帧/关闭累计回调次数；初始接纳时两者都应为零。
         */
        var shown = 0; var closed = 0
        override fun onShown() { shown++ }
        override fun onClosed() { closed++ }
    }

    /** 管理器忙或 Fragment 已保存状态时拒绝，不能上报首帧。 */
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

    /** 接纳不立即计数；重复绘制只回调一次，重复 tag 拒绝，取消回调一次关闭。 */
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
        // 模拟 ViewRoot 的绘制通知；只调用 View.draw 不会分发该监听器。
        decor.viewTreeObserver.dispatchOnDraw(); decor.viewTreeObserver.dispatchOnDraw()
        assertEquals(1, callbacks.shown)
        host.cancel("id")
        activity.supportFragmentManager.executePendingTransactions()
        assertEquals(1, callbacks.closed)
        activityController.pause().stop().destroy()
    }

    /** 模拟恢复只有 arguments 而没有 callbacks 的 Fragment，应禁止显示旧窗口。 */
    @Test fun `restored fragment without live owner cannot show window`() {
        val activityController = Robolectric.buildActivity(FragmentActivity::class.java)
        activityController.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        val activity = activityController.setup().get()
        val fragment = WidgetGuideDialogFragment.create(PendingShow("old", GuideType.TOOLS), contents.content(GuideType.TOOLS))
        // 模拟进程/配置恢复：Android 能恢复 arguments，但没有活跃回调所有者。
        fragment.showNow(activity.supportFragmentManager, FragmentGuideHost.TAG)
        activity.supportFragmentManager.executePendingTransactions()
        assertFalse(fragment.dialog?.isShowing == true)
        activityController.pause().stop().destroy()
    }
}
