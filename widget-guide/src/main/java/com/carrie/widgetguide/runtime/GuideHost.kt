package com.carrie.widgetguide.runtime

import com.carrie.widgetguide.core.HostSnapshot
import com.carrie.widgetguide.core.PendingShow

/**
 * 主线程展示边界，隔离具体 DialogFragment 和项目统一弹窗管理器。
 * tryShow 必须同步接受或拒绝，不能保存旧 show 闭包稍后执行。
 * 管理器忙时返回 false，名额恢复时通知 controller.refresh() 重新走完整资格判断。
 */
interface GuideHost {
    /**
     * 返回当前稳定快照；读取不能增加 version 或产生其他副作用。
     */
    val snapshot: HostSnapshot
    /**
     * 尝试立即展示 request。true 表示接纳，不表示首帧已绘制；false 必须不留下窗口。
     * 接纳后实际首帧回调 onShown，最终关闭回调 onClosed；取消也要闭合回调生命周期。
     */
    fun tryShow(request: PendingShow, callbacks: PresentationCallbacks): Boolean
    /**
     * 取消指定展示 ID 的窗口/未绘制请求，并最终回调 onClosed；不得误取消其他 ID。
     */
    fun cancel(presentationId: String)
}

/**
 * 一次接纳请求的主线程回调；onShown 可在 onClosed 前发生，关闭后不得再伪造展示。
 */
interface PresentationCallbacks {
    /**
     * 内容第一次实际可见绘制时调用，不能在入队、show()、onCreateDialog 时提前调用。
     */
    fun onShown()
    /**
     * 窗口结束时恰好一次；从未绘制的取消也必须调用，供控制器释放预约。
     */
    fun onClosed()
}

/**
 * 诊断状态。message 是可读原因，state 是最近确认快照；加载完成前 state 可为 null。
 */
data class GuideStatus(val message: String, val state: com.carrie.widgetguide.core.GuideState? = null)
