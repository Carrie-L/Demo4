package com.carrie.widgetguide.ui

import android.app.Dialog
import android.os.Bundle
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.carrie.widgetguide.R
import com.carrie.widgetguide.core.PendingShow
import com.carrie.widgetguide.databinding.DialogWidgetGuideBinding
import com.carrie.widgetguide.runtime.PresentationCallbacks

/**
 * XML 引导弹窗，负责渲染、首帧通知、跳转防连点和关闭通知，不直接操作存储。
 * Android 可能恢复 arguments，但不能恢复内存 callbacks；无所有者时禁止恢复旧窗口。
 */
class WidgetGuideDialogFragment : DialogFragment() {
    /**
     * 本次展示的控制器回调；只由活跃宿主注入，不写入 Bundle。
     */
    internal var callbacks: PresentationCallbacks? = null
    /**
     * 当前宿主的路由能力；不持久化 Activity 或路由闭包。
     */
    internal var navigator: WidgetGuideNavigator? = null
    /**
     * 从 arguments 读取展示 ID，用于宿主精确取消。
     */
    val presentationId: String get() = requireArguments().getString("id").orEmpty()
    /**
     * 首帧只上报一次，即使系统触发多次绘制/重复 onStart。
     */
    private var reported = false
    /**
     * 关闭回调去重标志，确保取消和正常 dismiss 不重复通知。
     */
    private var closed = false
    /**
     * 阻止连续点击发起多个添加入口；跳转失败后解除锁定。
     */
    private var navigating = false

    /**
     * 恢复时先检查所有者；在创建窗口前关闭 showsDialog，防止旧引导闪现。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统恢复的 Fragment 没有活跃预约所有者，必须在窗口出现前禁止展示。
        // 新控制器只恢复已完成行为和频控，不恢复未完成的页面链路。
        if (callbacks == null) showsDialog = false
    }

    /**
     * 从可恢复的参数填充 XML 内容和无障碍描述，绑定两种添加目标与关闭操作。
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogWidgetGuideBinding.inflate(layoutInflater)
        val args = requireArguments()
        binding.guideTitle.text = args.getString("title")
        binding.guideDescription.text = args.getString("description")
        binding.guidePreview.setImageResource(args.getInt("preview"))
        binding.guidePreview.contentDescription = args.getString("previewDescription")
        binding.addDesktop.setOnClickListener { navigate(args.getString("desktop").orEmpty()) }
        binding.addAssistant.setOnClickListener { navigate(args.getString("assistant").orEmpty()) }
        binding.closeGuide.setOnClickListener { dismiss() }
        return AlertDialog.Builder(requireContext()).setView(binding.root).create()
    }

    /**
     * 监听实际内容绘制；要求 decor 可见且 Dialog 正在展示，才通知 onShown。
     * 采用绘制回调作为本地“已展示”口径，不等价于硬件帧已提交或用户确实阅读。
     */
    override fun onStart() {
        super.onStart()
        if (callbacks == null) { dismissAllowingStateLoss(); return }
        if (reported) return
        val decor = dialog?.window?.decorView ?: return
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (!reported && decor.isShown && dialog?.isShowing == true) {
                    reported = true
                    callbacks?.onShown()
                    // 不能在 OnDraw 分发过程中直接移除监听器，投递到本帧回调之后。
                    decor.post { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(this) }
                }
            }
        }
        decor.viewTreeObserver.addOnDrawListener(listener)
    }

    /**
     * 安全调用路由：成功发起后关闭；返回 false 或异常则保留窗口并提示重试。
     * 无论跳转结果如何，已发生的首帧计数均不撤销，也不写入“已添加”状态。
     */
    private fun navigate(link: String) {
        if (navigating) return
        navigating = true
        val opened = try { navigator?.open(link) == true } catch (_: Exception) { false }
        if (opened) dismissAllowingStateLoss() else {
            navigating = false
            Toast.makeText(requireContext(), R.string.guide_navigation_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 先完成一次关闭通知，再交给 Fragment 生命周期；控制器据 shown 决定释放或保留次数。
     */
    override fun onDismiss(dialog: android.content.DialogInterface) {
        if (!closed) { closed = true; callbacks?.onClosed() }
        super.onDismiss(dialog)
    }

    /**
     * 集中构造 arguments，保持恢复所需的展示数据与进程内回调分离。
     */
    companion object {
        /**
         * 打包展示 ID 与纯内容；callbacks/navigator 由宿主另行注入。
         */
        fun create(request: PendingShow, content: GuideContent) = WidgetGuideDialogFragment().apply {
            arguments = bundleOf("id" to request.id, "title" to content.title,
                "description" to content.description, "preview" to content.previewRes,
                "previewDescription" to content.previewDescription,
                "desktop" to content.desktopDeeplink, "assistant" to content.assistantDeeplink)
        }
    }
}
