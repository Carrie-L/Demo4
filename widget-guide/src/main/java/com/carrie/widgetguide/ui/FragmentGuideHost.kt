package com.carrie.widgetguide.ui

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.carrie.widgetguide.core.*
import com.carrie.widgetguide.runtime.*


/**
 * 使用 DialogFragment 的宿主适配器，迁移时可在这里接统一弹窗管理器。
 * @param activity 当前持有 FragmentManager 的 Activity。
 * @param snapshotProvider 获取最新业务位置/有效性快照，禁止返回写盘前缓存。
 * @param contents 文案、截图和 deeplink 的提供者。
 * @param navigator 已有添加能力的路由适配器。
 * @param managerAvailable 统一管理器是否允许立即展示；默认验证宿主不额外排队。
 */
class FragmentGuideHost(
    private val activity: FragmentActivity,
    private val snapshotProvider: () -> HostSnapshot,
    private val contents: GuideContentProvider,
    private val navigator: WidgetGuideNavigator,
    private val managerAvailable: () -> Boolean = { true }
) : GuideHost {
    /**
     * 直接获取宿主实时快照，让控制器在 IO 之后能够发现变化。
     */
    override val snapshot get() = snapshotProvider()

    /**
     * 再次检查生命周期、Fragment 保存状态、管理器名额和 tag 占用。
     * showNow 同步接纳，不把“成功调用 showNow”当作展示次数；计数仍等待 OnDraw。
     */
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

    /**
     * 只关闭匹配 ID 的本模块弹窗；允许状态丢失是为了取消窗口，不恢复过期展示。
     */
    override fun cancel(presentationId: String) {
        val fragment = activity.supportFragmentManager.findFragmentByTag(TAG) as? WidgetGuideDialogFragment
        if (fragment?.presentationId == presentationId) fragment.dismissAllowingStateLoss()
    }

    /**
     * 固定 tag 防止同一 FragmentManager 内同时存在两个本模块弹窗。
     */
    companion object { const val TAG = "browser.widget.guide" }
}
