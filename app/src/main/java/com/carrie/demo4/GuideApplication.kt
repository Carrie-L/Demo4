package com.carrie.demo4 // Demo 在进程中集中持有同一份配置与频次管理器。

import android.app.Application // Android 应用初始化入口。
import com.alibaba.android.arouter.launcher.ARouter // 保留原项目路由。
import com.carrie.widgetguide.GuidePolicy // Debug 时钟每天前进固定 24 小时。
import com.carrie.widgetguide.GuideRules // 云配快照的业务结构。
import com.carrie.widgetguide.GuideStore // 可读键名的本地 SP。
import com.carrie.widgetguide.WidgetGuideManager // 唯一的自动引导协调器。

/** Debug 面板模拟云配，正式接入时替换成已有后台配置来源。 */
class GuideApplication : Application() { // 不在 Application 中记录用户访问过哪些页面。
    var rules = GuideRules(enable = false, firstPopInterval = 1L, secondPopInterval = 10L) // 默认关闭。
    var highlightDeeplink = "demo4://search/results" // 可在 Debug 切成资讯详情落点。
    private var debugOffsetMillis = 0L // 只在当前进程推进测试时间。
    lateinit var store: GuideStore // 单进程一个持久化读写入口。
        private set // 页面不能另建不同的存储实例。
    lateinit var guideManager: WidgetGuideManager // 两种组件使用同一实例。
        private set // 页面只调用一次返回触发 API。

    override fun onCreate() { // 初始化路由与本地存储。
        super.onCreate() // 先完成系统 Application 初始化。
        if (BuildConfig.DEBUG) { ARouter.openLog(); ARouter.openDebug() } // 开发构建显示路由日志。
        ARouter.init(this) // 注册 Demo 的页面地址。
        store = GuideStore(this) // 引导记录保存在独立 SP 文件。
        guideManager = WidgetGuideManager(store, { rules }, { nowMillis() }) // 频次每次使用最新配置与时间。
    } // 应用初始化结束。

    fun nowMillis(): Long = System.currentTimeMillis() + debugOffsetMillis // Demo 时钟，正式项目取系统时间。

    fun advanceDayForDebug() { // Debug 显式推进冷却检查时点。
        check(BuildConfig.DEBUG) // Release 不暴露测试时间。
        debugOffsetMillis += GuidePolicy.DAY_MILLIS // 只改变之后的新行为计算时间。
    } // 时间推进不会自动触发弹窗。

    suspend fun resetForDebug(): Boolean { // 显式清除测试状态。
        check(BuildConfig.DEBUG) // Release 不提供重置入口。
        val cleared = guideManager.clearForDebug() // 只清该模块存储。
        if (cleared) debugOffsetMillis = 0L // 与频次记录一起恢复测试时间。
        return cleared // 返回磁盘写入结果。
    } // Debug 重置结束。
} // 应用入口结束。
