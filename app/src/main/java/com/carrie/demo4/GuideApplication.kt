package com.carrie.demo4 // Demo 在进程中集中持有同一份配置与频次管理器。

import android.app.Application // Android 应用初始化入口。
import android.os.SystemClock // 使用单调时间区分测试时钟跳转和真实事件先后。
import android.util.Log // Debug 的 Logcat 使用固定标签检索。
import com.alibaba.android.arouter.launcher.ARouter // 保留原项目路由。
import com.carrie.widgetguide.GuidePolicy // Debug 时钟每天前进固定 24 小时。
import com.carrie.widgetguide.GuideRules // 云配快照的业务结构。
import com.carrie.widgetguide.GuideStore // 可读键名的本地 SP。
import com.carrie.widgetguide.WidgetGuideManager // 唯一的自动引导协调器。

/** Debug 面板模拟云配，正式接入时替换成已有后台配置来源。 */
class GuideApplication : Application() { // 不在 Application 中记录用户访问过哪些页面。
    companion object { const val GUIDE_LOG_TAG = "WidgetGuideTrace" } // adb logcat -s WidgetGuideTrace 可筛选链路。
    var rules = GuideRules(enable = false, firstPopInterval = 1L, secondPopInterval = 10L) // 默认关闭。
    var highlightDeeplink = "demo4://search/results" // 可在 Debug 切成资讯详情落点。
    var lastGuideEvent = "尚未收到相关页面的返回结果" // 仅保存本进程最近一条诊断，不形成下次展示资格。
        private set // 只能通过统一诊断入口更新。
    private var debugOffsetMillis = 0L // 只在当前进程推进测试时间。
    private val guideEvents = ArrayDeque<String>() // 仅进程内保存最近事件，不作为弹窗资格。
    private var guideEventSequence = 0L // 单进程事件序号帮助核对异步回调先后。
    lateinit var store: GuideStore // 单进程一个持久化读写入口。
        private set // 页面不能另建不同的存储实例。
    lateinit var guideManager: WidgetGuideManager // 两种组件使用同一实例。
        private set // 页面只调用一次返回触发 API。

    override fun onCreate() { // 初始化路由与本地存储。
        super.onCreate() // 先完成系统 Application 初始化。
        if (BuildConfig.DEBUG) { ARouter.openLog(); ARouter.openDebug() } // 开发构建显示路由日志。
        ARouter.init(this) // 注册 Demo 的页面地址。
        store = GuideStore(this) // 引导记录保存在独立 SP 文件。
        guideManager = WidgetGuideManager(store, { rules }, { nowMillis() }, { recordGuideEvent(it) }) // 频次每次使用最新配置与时间。
    } // 应用初始化结束。

    @Synchronized fun recordGuideEvent(message: String) { // 所有链路共用序号、时钟与 Logcat 标签。
        guideEventSequence += 1L // 每条事件使用唯一的本进程序号。
        val entry = "#${guideEventSequence} ${java.time.Instant.ofEpochMilli(nowMillis())} elapsed=${SystemClock.elapsedRealtime()}ms $message" // 同时展示虚拟时钟和单调时间。
        lastGuideEvent = entry // 记录最近节点，兼容原有诊断页与测试。
        if (!BuildConfig.DEBUG) return // Release 不保留详细行为链，也不输出调试日志。
        if (guideEvents.size == 120) guideEvents.removeFirst() // 固定上限避免长时间调试占用内存。
        guideEvents.addLast(entry) // 添加时间线，不写入本地频次文件。
        Log.d(GUIDE_LOG_TAG, entry) // Logcat 与页面展示同一条日志。
    } // 诊断事件记录结束。

    @Synchronized fun recentGuideEvents(): List<String> = guideEvents.toList() // 只返回快照，记录页不能修改缓存。

    fun nowMillis(): Long = System.currentTimeMillis() + debugOffsetMillis // Demo 时钟，正式项目取系统时间。

    fun advanceDayForDebug() { // Debug 显式推进冷却检查时点。
        check(BuildConfig.DEBUG) // Release 不暴露测试时间。
        debugOffsetMillis += GuidePolicy.DAY_MILLIS // 只改变之后的新行为计算时间。
        recordGuideEvent("debug.clock_advanced offsetMillis=$debugOffsetMillis") // 解释虚拟时钟变化后的冷却结果。
    } // 时间推进不会自动触发弹窗。

    suspend fun resetForDebug(): Boolean { // 显式清除测试状态。
        check(BuildConfig.DEBUG) // Release 不提供重置入口。
        val cleared = guideManager.clearForDebug() // 只清该模块存储。
        if (cleared) debugOffsetMillis = 0L // 与频次记录一起恢复测试时间。
        recordGuideEvent("debug.reset success=$cleared") // 可区分清理前次数与重新测试。
        return cleared // 返回磁盘写入结果。
    } // Debug 重置结束。
} // 应用入口结束。
