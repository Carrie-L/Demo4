package com.carrie.widgetguide // 自动引导的唯一进程级协调入口。

import android.app.Activity // 判断当前展示宿主是否还有效。
import android.app.Dialog // 使用普通 Dialog，避免旧机会在重建后自动恢复。
import android.util.Log // 记录存储和 deeplink 故障。
import android.view.Gravity // 把引导放到宿主页面底部。
import android.view.ViewGroup // 设置弹窗宽高。
import androidx.appcompat.app.AppCompatActivity // 当前业务宿主类型。
import com.carrie.widgetguide.databinding.DialogWidgetGuideBinding // XML 的类型安全绑定。
import kotlinx.coroutines.CoroutineScope // 在应用进程内顺序完成必要写入。
import kotlinx.coroutines.Dispatchers // 在主线程进行窗口与内存状态变更。
import kotlinx.coroutines.SupervisorJob // 单次故障不取消后续诊断能力。
import kotlinx.coroutines.launch // 展示和点击都按顺序调用 IO 存储。
import java.time.ZoneId // 共享自然日使用设备当前时区。

/** 两类引导共用的文案、截图和左右添加链接。 */
data class GuideContent( // 数据由宿主根据组件类型提供。
    val title: String, // 引导标题。
    val description: String, // 简短引导文字。
    val previewDrawable: Int, // 组件截图资源。
    val previewAccessibility: String, // 截图的无障碍描述。
    val desktopDeeplink: String, // 左侧添加到桌面链接。
    val assistantDeeplink: String // 右侧添加到负一屏链接。
) // 不含组件“是否添加成功”的状态。

/** 同一次 Dialog 的展示回调最多计数一次；新的 Dialog 可以另计一次。 */
class WidgetGuideManager( // 应用进程只创建一个实例。
    private val store: GuideStore, // SP 或相同语义的 MMKV 存储。
    private val currentRules: () -> GuideRules, // 获取此刻最新云配。
    private val currentTimeMillis: () -> Long = System::currentTimeMillis // Debug 可注入测试时间。
) { // 不持久化用户行为资格。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) // 必要写入不受 Activity 销毁取消。
    private var record: GuideRecord? = null // 本进程最新频次快照。
    private var paused = false // 存储故障后本进程暂停自动引导。
    private var busy = false // 一个弹窗或写入在途时拒绝其他弹窗。
    private var activeDialog: Dialog? = null // 只引用当前正在展示的窗口。
    private var activeOwner: Activity? = null // 用于宿主停止时安全关闭窗口。
    private var persisting = false // 等待磁盘写入时不释放 busy。

    /** 只在有效页面结果返回后调用；拒绝时不会缓存此次机会。 */
    fun tryShow( // 页面负责选择类型与提供可见位置判断。
        activity: AppCompatActivity, // 底部弹窗所在页面。
        type: GuideType, // COMMON_TOOLS 或 AI_QUICK_NEWS。
        content: GuideContent, // 与类型匹配的图片、文案、链接。
        canDisplayHere: () -> Boolean, // 复核页面与 tab 的可见状态。
        openDeeplink: (Activity, String) -> Boolean // 用户点击添加后调用的现有跳转。
    ) { // 被拦截后直接丢弃这轮返回结果。
        if (busy || paused) return // 不排队，也不补弹。
        busy = true // 先锁定本轮，避免磁盘读取期间同时收到第二次返回。
        scope.launch { // 回到主线程后再复核宿主。
            try { // 读写异常一律禁止误弹。
                val history = record ?: store.read().also { record = it } // 首次读取 SP，后续用本进程快照。
                val now = currentTimeMillis() // 频次判断与本次展示用同一时钟来源。
                if (!canDisplayHere() || activity.isFinishing || activity.isDestroyed || !GuidePolicy.canShow( // 严格复核位置和频次。
                        type, currentRules(), history, now, ZoneId.systemDefault()
                    )) { // 不满足任何一项就结束本次机会。
                    busy = false // 解锁后也不留下待弹任务。
                    return@launch // 不在下次 onResume 重试。
                } // 本轮有效性复核结束。
                showDialog(activity, type, content, canDisplayHere, openDeeplink) // 建立一次弹窗。
            } catch (error: Exception) { // 读取损坏或窗口出错不能当成新用户。
                paused = true // 本进程停止后续自动展示。
                busy = false // 异常时释放排他标志。
                Log.e("WidgetGuide", "Automatic guide paused", error) // 留下诊断信息。
            } // 本轮展示尝试结束。
        } // 异步读取协程结束。
    } // 返回结果触发入口结束。

    /** 宿主离开页面时关闭所属窗口，不保留展示资格。 */
    fun dismissForOwner(owner: Activity) { // Main 和二级列表在 onStop 调用。
        if (activeOwner === owner) activeDialog?.dismiss() // 只关闭本宿主的弹窗。
    } // 关闭时的频次已经在 onShow 计入。

    /** Debug 面板读取次数时不触发任何弹窗。 */
    suspend fun snapshot(): GuideRecord = record ?: store.read().also { record = it } // 共享相同存储快照。

    /** Debug 显式清空测试记录，正式页面不调用。 */
    suspend fun clearForDebug(): Boolean { // Debug 面板先结束当前窗口。
        activeDialog?.dismiss() // 关闭旧弹窗。
        if (persisting) return false // 防止还在写入的旧记录覆盖清理。
        val success = store.clearForDebug() // 仅清引导频次文件。
        if (success) { record = GuideRecord(); paused = false; busy = false } // 恢复调试初态。
        return success // 把真实写入结果交给面板。
    } // 测试数据清理结束。

    /** 为一次有效返回建立普通底部 Dialog。 */
    private fun showDialog( // 标记只在这一次 Dialog 的闭包中存在。
        activity: AppCompatActivity, // 弹窗持有者。
        type: GuideType, // 用于独立展示和停止。
        content: GuideContent, // 由页面提供产品资源。
        canDisplayHere: () -> Boolean, // onShow 再确认位置。
        openDeeplink: (Activity, String) -> Boolean // 点击后才调用跳转。
    ) { // 此时 busy 已经为 true。
        val binding = DialogWidgetGuideBinding.inflate(activity.layoutInflater) // 加载复用 XML。
        val dialog = Dialog(activity) // 普通 Dialog 不自动恢复上次机会。
        var counted = false // 防止同一个 Dialog 重复 onShow 造成两次计数。
        var clicked = false // 左右按钮只能处理一次点击。
        binding.guideTitle.text = content.title // 绑定当前组件标题。
        binding.guideDescription.text = content.description // 绑定说明。
        binding.widgetPreview.setImageResource(content.previewDrawable) // 绑定组件截图。
        binding.widgetPreview.contentDescription = content.previewAccessibility // 截图无障碍说明。
        binding.addDesktop.isEnabled = false // 等展示记录落盘后才能点击。
        binding.addAssistant.isEnabled = false // 避免停止写入与展示写入竞态。
        dialog.setContentView(binding.root) // 使用相同 XML 展示两类引导。
        dialog.setCanceledOnTouchOutside(true) // 点击空白只取消，不设置停止标志。
        dialog.setOnDismissListener { // 取消、添加或宿主停止都会走这里。
            if (activeDialog === dialog) { activeDialog = null; activeOwner = null } // 清除 Activity 引用。
            if (!persisting) busy = false // 必要写入完成后才允许下一次尝试。
        } // 关闭监听结束。
        dialog.setOnShowListener { // Android 确认窗口开始展示时记次。
            if (counted) return@setOnShowListener // 同一个窗口重复回调不能重复计数。
            counted = true // 本 Dialog 此后只能计一次。
            val now = currentTimeMillis() // 展示时刻是本类型冷却起点。
            val before = checkNotNull(record) // 记录必须已成功读取。
            val own = if (type == GuideType.COMMON_TOOLS) before.commonTools else before.aiQuickNews // 取本类型计数。
            val updated = own.copy(showCount = own.showCount + 1, lastShownAtMillis = now) // 累加该类型一次。
            val after = if (type == GuideType.COMMON_TOOLS) before.copy(commonTools = updated, lastAnyShownAtMillis = now) // A 更新共享时间。
                        else before.copy(aiQuickNews = updated, lastAnyShownAtMillis = now) // B 更新共享时间。
            record = after // 先更新内存，后续尝试不会忽略本次展示。
            persisting = true // 取消时仍等待持久化完成。
            scope.launch { // 顺序完成这次展示记录。
                try { // 存储失败要进入暂停状态。
                    if (!store.write(after)) { paused = true; Log.e("WidgetGuide", "Show record was not saved") } // 不伪造持久化成功。
                } catch (error: Exception) { paused = true; Log.e("WidgetGuide", "Show record failed", error) } // 捕获 IO 错误。
                finally { // 关闭过程中不能把 busy 永久挂住。
                    persisting = false // 展示写入已经结束。
                    if (dialog.isShowing) { binding.addDesktop.isEnabled = true; binding.addAssistant.isEnabled = true } // 允许手动添加。
                    else busy = false // 用户已取消时直接释放排他标志。
                } // 展示记录处理结束。
            } // 展示写入协程结束。
        } // 展示监听结束。
        fun add(link: String) { // 两个添加按钮共用停止逻辑。
            if (!counted || persisting || clicked) return // 展示未保存或已处理则忽略连点。
            clicked = true // 立即防止另一个按钮重复触发。
            binding.addDesktop.isEnabled = false // 锁定左侧按钮。
            binding.addAssistant.isEnabled = false // 锁定右侧按钮。
            val before = checkNotNull(record) // 拿到最新的计数快照。
            val after = if (type == GuideType.COMMON_TOOLS) before.copy(commonTools = before.commonTools.copy(stopped = true)) // 只停止常用工具。
                        else before.copy(aiQuickNews = before.aiQuickNews.copy(stopped = true)) // 只停止 AI 快看。
            record = after // deeplink 失败也不能恢复停止标志。
            persisting = true // 写停止标志期间保持 busy。
            scope.launch { // 先持久化，再打开快服务。
                try { // 点击操作不依赖添加是否成功。
                    if (!store.write(after)) { paused = true; Log.e("WidgetGuide", "Stop flag was not saved") } // 存储失败本进程继续停止。
                } catch (error: Exception) { paused = true; Log.e("WidgetGuide", "Stop flag failed", error) } // 记录磁盘错误。
                finally { persisting = false; dialog.dismiss() } // 关窗并释放对 Activity 的长期引用。
                if (!activity.isFinishing && !activity.isDestroyed) { // 宿主存活才尝试跳转。
                    try { if (!openDeeplink(activity, link)) Log.e("WidgetGuide", "Add deeplink unavailable") } // 失败仍已停止此类型。
                    catch (error: Exception) { Log.e("WidgetGuide", "Add deeplink failed", error) } // 捕获路由异常。
                } // 宿主检查结束。
            } // 停止写入与 deeplink 协程结束。
        } // 通用添加处理结束。
        binding.addDesktop.setOnClickListener { add(content.desktopDeeplink) } // 左边添加到桌面。
        binding.addAssistant.setOnClickListener { add(content.assistantDeeplink) } // 右边添加到负一屏。
        if (!canDisplayHere() || activity.isFinishing || activity.isDestroyed) { busy = false; return } // 展示前最后复核。
        activeDialog = dialog // 记录本次窗口用于宿主停止时关闭。
        activeOwner = activity // 只绑定当前原入口。
        dialog.show() // 展示回调负责真正计次。
        dialog.window?.setGravity(Gravity.BOTTOM) // 在当前页面底部出现。
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) // 铺满页面宽度。
    } // Dialog 建立结束。
} // 管理器结束。
