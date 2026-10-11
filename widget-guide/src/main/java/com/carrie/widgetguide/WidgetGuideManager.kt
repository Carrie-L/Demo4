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
    private val currentTimeMillis: () -> Long = System::currentTimeMillis, // Debug 可注入测试时间。
    private val onDiagnostic: (String) -> Unit = {} // Debug 宿主可记录最近一次弹窗判断。
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
        openDeeplink: (Activity, String) -> Boolean, // 用户点击添加后调用的现有跳转。
        onRecorded: () -> Unit = {} // 成功写入展示记录后通知宿主刷新计数。
    ) { // 被拦截后直接丢弃这轮返回结果。
        onDiagnostic("guide.attempt type=${type.name} owner=${activity.javaClass.simpleName} busy=$busy paused=$paused") // 每轮有效返回都可在时间线上看到入口。
        if (busy || paused) { onDiagnostic("自动引导未检查：${if (paused) "本进程存储故障暂停" else "已有弹窗或写入正在处理"}"); return } // 不排队，也不补弹。
        busy = true // 先锁定本轮，避免磁盘读取期间同时收到第二次返回。
        scope.launch { // 回到主线程后再复核宿主。
            try { // 读写异常一律禁止误弹。
                onDiagnostic("guide.history_read source=${if (record == null) "sp" else "memory"} type=${type.name}") // 判断本次数据来自真实读盘还是进程缓存。
                val history = record ?: store.read().also { record = it } // 首次读取 SP，后续用本进程快照。
                val now = currentTimeMillis() // 频次判断与本次展示用同一时钟来源。
                val rules = currentRules() // 获取此次返回使用的配置。
                onDiagnostic("guide.policy_input type=${type.name} enabled=${rules.enable} count=${if (type == GuideType.COMMON_TOOLS) history.commonTools.showCount else history.aiQuickNews.showCount} stopped=${if (type == GuideType.COMMON_TOOLS) history.commonTools.stopped else history.aiQuickNews.stopped} lastOwn=${if (type == GuideType.COMMON_TOOLS) history.commonTools.lastShownAtMillis else history.aiQuickNews.lastShownAtMillis} lastAny=${history.lastAnyShownAtMillis} now=$now") // 不记录搜索词，仅记录决策必要字段。
                if (!canDisplayHere() || activity.isFinishing || activity.isDestroyed) { // 异步读盘期间宿主可能已经离开。
                    onDiagnostic("${type.name} 未弹：页面或入口 tab 已不在前台") // 把丢弃原因留给调试页面。
                    busy = false // 解锁后也不留下待弹任务。
                    return@launch // 不在下次 onResume 重试。
                } // 本轮有效性复核结束。
                if (!GuidePolicy.canShow(type, rules, history, now, ZoneId.systemDefault())) { // 规则或次数可能阻止本轮展示。
                    onDiagnostic("${type.name} 未弹：${policyBlockReason(type, rules, history, now)}；本类型次数=${if (type == GuideType.COMMON_TOOLS) history.commonTools.showCount else history.aiQuickNews.showCount}") // 保留频次判断原因。
                    busy = false // 释放正在检查的机会。
                    return@launch // 冷却后仍须重新完成页面行为。
                } // 频控拒绝结束。
                onDiagnostic("${type.name} 规则通过，准备显示窗口") // 区分回传正常与窗口故障。
                showDialog(activity, type, content, canDisplayHere, openDeeplink, onRecorded) // 建立一次弹窗。
            } catch (error: Exception) { // 读取损坏或窗口出错不能当成新用户。
                paused = true // 本进程停止后续自动展示。
                busy = false // 异常时释放排他标志。
                Log.e("WidgetGuide", "Automatic guide paused", error) // 留下诊断信息。
                onDiagnostic("${type.name} 未弹：读写记录或显示窗口异常：${error.javaClass.simpleName} ${error.message.orEmpty()}") // Debug 页面显示异常原因。
            } // 本轮展示尝试结束。
        } // 异步读取协程结束。
    } // 返回结果触发入口结束。

    private fun policyBlockReason(type: GuideType, rules: GuideRules, history: GuideRecord, now: Long): String { // 使用同一次决策的时间解释频控拒绝。
        val own = if (type == GuideType.COMMON_TOOLS) history.commonTools else history.aiQuickNews // 获取此弹窗独立记录。
        if (!rules.enable) return "配置 enable 为 false" // 配置总开关拒绝。
        if (own.stopped) return "此组件已点击添加并永久停止" // 两类停止状态互不影响。
        if (own.showCount >= 3) return "此组件已达最多 3 次" // 不存在第四次。
        if (now <= 0L || now < history.lastAnyShownAtMillis) return "测试时钟无效或早于共享展示时间 now=$now lastAny=${history.lastAnyShownAtMillis}" // 不把时钟倒退误认作新一天。
        if (history.lastAnyShownAtMillis > 0L) { // 检查共享本地自然日限额。
            val shownDate = java.time.Instant.ofEpochMilli(history.lastAnyShownAtMillis).atZone(ZoneId.systemDefault()).toLocalDate() // 最近一次展示日期。
            val today = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate() // 使用同一次频控判断的测试时间。
            if (shownDate == today) return "A/B 在本地日期 $today 已展示一次" // 本类型也受共享自然日限制。
        } // 共享频控原因结束。
        if (own.lastShownAtMillis <= 0L || now < own.lastShownAtMillis) return "本类型展示时间无效或时钟倒退 lastOwn=${own.lastShownAtMillis} now=$now" // 损坏记录保守拒绝。
        val days = if (own.showCount == 1) rules.firstPopInterval else rules.secondPopInterval // 用本类型下一次所需间隔。
        if (days == null || days < 0L || days > Long.MAX_VALUE / GuidePolicy.DAY_MILLIS) return "本类型间隔未配置或不合法 days=$days" // 坏配置不能放行。
        return "本类型冷却未到 elapsed=${now - own.lastShownAtMillis}ms required=${days * GuidePolicy.DAY_MILLIS}ms" // 精确定位还差多久。
    } // 频控诊断结束。

    /** 宿主离开页面时关闭所属窗口，不保留展示资格。 */
    fun dismissForOwner(owner: Activity) { // Main 和二级列表在 onStop 调用。
        if (activeOwner === owner) { onDiagnostic("guide.owner_stopped owner=${owner.javaClass.simpleName} dialog_dismissed=true"); activeDialog?.dismiss() } // 只关闭本宿主的弹窗。
    } // 关闭时的频次已经在 onShow 计入。

    /** Debug 面板读取次数时不触发任何弹窗。 */
    suspend fun snapshot(): GuideRecord = record ?: store.read().also { record = it } // 共享相同存储快照。

    /** Debug 显式清空测试记录，正式页面不调用。 */
    suspend fun clearForDebug(): Boolean { // Debug 面板先结束当前窗口。
        activeDialog?.dismiss() // 关闭旧弹窗。
        if (persisting) return false // 防止还在写入的旧记录覆盖清理。
        val success = store.clearForDebug() // 仅清引导频次文件。
        if (success) { record = GuideRecord(); paused = false; busy = false } // 恢复调试初态。
        onDiagnostic("guide.debug_clear success=$success persisting=$persisting") // 记录主动重置后的真实落盘结果。
        return success // 把真实写入结果交给面板。
    } // 测试数据清理结束。

    /** 为一次有效返回建立普通底部 Dialog。 */
    private fun showDialog( // 标记只在这一次 Dialog 的闭包中存在。
        activity: AppCompatActivity, // 弹窗持有者。
        type: GuideType, // 用于独立展示和停止。
        content: GuideContent, // 由页面提供产品资源。
        canDisplayHere: () -> Boolean, // onShow 再确认位置。
        openDeeplink: (Activity, String) -> Boolean, // 点击后才调用跳转。
        onRecorded: () -> Unit // 计数成功后更新宿主调试 UI。
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
            onDiagnostic("guide.dialog_dismissed type=${type.name} counted=$counted clicked=$clicked persisting=$persisting") // 空白取消与点击添加可从日志区分。
            if (activeDialog === dialog) { activeDialog = null; activeOwner = null } // 清除 Activity 引用。
            if (!persisting) busy = false // 必要写入完成后才允许下一次尝试。
        } // 关闭监听结束。
        dialog.setOnShowListener { // Android 确认窗口开始展示时记次。
            if (counted) return@setOnShowListener // 同一个窗口重复回调不能重复计数。
            counted = true // 本 Dialog 此后只能计一次。
            val now = currentTimeMillis() // 展示时刻是本类型冷却起点。
            onDiagnostic("guide.dialog_on_show type=${type.name} at=$now") // 只有本节点发生后才可能增加次数。
            val before = checkNotNull(record) // 记录必须已成功读取。
            val own = if (type == GuideType.COMMON_TOOLS) before.commonTools else before.aiQuickNews // 取本类型计数。
            val updated = own.copy(showCount = own.showCount + 1, lastShownAtMillis = now) // 累加该类型一次。
            val after = if (type == GuideType.COMMON_TOOLS) before.copy(commonTools = updated, lastAnyShownAtMillis = now) // A 更新共享时间。
                        else before.copy(aiQuickNews = updated, lastAnyShownAtMillis = now) // B 更新共享时间。
            record = after // 先更新内存，后续尝试不会忽略本次展示。
            onDiagnostic("guide.persist_show_start type=${type.name} count=${updated.showCount} at=$now") // 区分已经显示与已成功落盘。
            persisting = true // 取消时仍等待持久化完成。
            scope.launch { // 顺序完成这次展示记录。
                try { // 存储失败要进入暂停状态。
                    if (!store.write(after)) { paused = true; onDiagnostic("${type.name} 已显示但 SP 写入失败"); Log.e("WidgetGuide", "Show record was not saved") } // 不伪造持久化成功。
                    else { onDiagnostic("${type.name} 已显示并写入 SP：第 ${updated.showCount} 次"); onRecorded() } // 确认磁盘提交成功后更新 UI。
                } catch (error: Exception) { paused = true; onDiagnostic("${type.name} 已显示但写入异常：${error.message.orEmpty()}"); Log.e("WidgetGuide", "Show record failed", error) } // 捕获 IO 错误。
                finally { // 关闭过程中不能把 busy 永久挂住。
                    persisting = false // 展示写入已经结束。
                    if (dialog.isShowing) { binding.addDesktop.isEnabled = true; binding.addAssistant.isEnabled = true } // 允许手动添加。
                    else busy = false // 用户已取消时直接释放排他标志。
                } // 展示记录处理结束。
            } // 展示写入协程结束。
        } // 展示监听结束。
        fun add(link: String, target: String) { // 两个添加按钮共用停止逻辑。
            if (!counted || persisting || clicked) return // 展示未保存或已处理则忽略连点。
            clicked = true // 立即防止另一个按钮重复触发。
            onDiagnostic("guide.add_clicked type=${type.name} target=$target count=${checkNotNull(record).let { if (type == GuideType.COMMON_TOOLS) it.commonTools.showCount else it.aiQuickNews.showCount }}") // 只记录目标类别，不记录完整 deeplink。
            binding.addDesktop.isEnabled = false // 锁定左侧按钮。
            binding.addAssistant.isEnabled = false // 锁定右侧按钮。
            val before = checkNotNull(record) // 拿到最新的计数快照。
            val after = if (type == GuideType.COMMON_TOOLS) before.copy(commonTools = before.commonTools.copy(stopped = true)) // 只停止常用工具。
                        else before.copy(aiQuickNews = before.aiQuickNews.copy(stopped = true)) // 只停止 AI 快看。
            record = after // deeplink 失败也不能恢复停止标志。
            persisting = true // 写停止标志期间保持 busy。
            scope.launch { // 先持久化，再打开快服务。
                try { // 点击操作不依赖添加是否成功。
                    if (!store.write(after)) { paused = true; onDiagnostic("guide.persist_stop_failed type=${type.name} target=$target"); Log.e("WidgetGuide", "Stop flag was not saved") } // 存储失败本进程继续停止。
                    else onDiagnostic("guide.persist_stop_success type=${type.name} target=$target") // SP 已确认此类型永久停止。
                } catch (error: Exception) { paused = true; onDiagnostic("guide.persist_stop_error type=${type.name} error=${error.javaClass.simpleName}"); Log.e("WidgetGuide", "Stop flag failed", error) } // 记录磁盘错误。
                finally { persisting = false; dialog.dismiss() } // 关窗并释放对 Activity 的长期引用。
                if (!activity.isFinishing && !activity.isDestroyed) { // 宿主存活才尝试跳转。
                    try { val opened = openDeeplink(activity, link); onDiagnostic("guide.add_navigation type=${type.name} target=$target requested=$opened"); if (!opened) Log.e("WidgetGuide", "Add deeplink unavailable") } // 失败仍已停止此类型。
                    catch (error: Exception) { onDiagnostic("guide.add_navigation_error type=${type.name} target=$target error=${error.javaClass.simpleName}"); Log.e("WidgetGuide", "Add deeplink failed", error) } // 捕获路由异常。
                } // 宿主检查结束。
            } // 停止写入与 deeplink 协程结束。
        } // 通用添加处理结束。
        binding.addDesktop.setOnClickListener { add(content.desktopDeeplink, "desktop") } // 左边添加到桌面。
        binding.addAssistant.setOnClickListener { add(content.assistantDeeplink, "assistant") } // 右边添加到负一屏。
        if (!canDisplayHere() || activity.isFinishing || activity.isDestroyed) { onDiagnostic("${type.name} 未弹：显示前页面已经离开"); busy = false; return } // 展示前最后复核。
        activeDialog = dialog // 记录本次窗口用于宿主停止时关闭。
        activeOwner = activity // 只绑定当前原入口。
        dialog.show() // 展示回调负责真正计次。
        dialog.window?.setGravity(Gravity.BOTTOM) // 在当前页面底部出现。
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) // 铺满页面宽度。
    } // Dialog 建立结束。
} // 管理器结束。
