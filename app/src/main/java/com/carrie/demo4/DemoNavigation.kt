package com.carrie.demo4 // 路由只负责打开页面，返回仍走系统栈。

import android.app.Activity // 带 requestCode 的 ARouter 需要当前 Activity。
import android.net.Uri // 演示暗词和添加页 deeplink 的实际解析。
import android.view.View // 保留原布局的 Insets 辅助方法。
import androidx.core.view.ViewCompat // 对根视图安装系统栏 Insets。
import androidx.core.view.WindowInsetsCompat // 读取系统栏与刘海尺寸。
import com.alibaba.android.arouter.facade.Postcard // 接收路由回调中的目标。
import com.alibaba.android.arouter.facade.callback.NavigationCallback // 路由失败时清除等待关系。
import com.alibaba.android.arouter.launcher.ARouter // 保留项目原有跳转框架。

/** 相关页面通过 ARouter 打开，绝不为了返回再 startActivity(Main)。 */
object DemoNavigation { // Demo 单处封装路由地址。
    private fun trace(activity: Activity, event: String) { (activity.application as? GuideApplication)?.recordGuideEvent(event) } // 只记录页面类型和请求码，不记录搜索词或 deeplink 参数。
    fun settings(activity: Activity) { ARouter.getInstance().build("/settings/main").navigation(activity) } // 我的设置页。
    fun debug(activity: Activity) { ARouter.getInstance().build("/debug/guide").navigation(activity) } // Debug 配置面板。
    fun records(activity: Activity) { ARouter.getInstance().build("/debug/guide-records").navigation(activity) } // 查看真实本地频次键值。
    fun page(activity: Activity, kind: DemoPage, requestCode: Int? = null, selectedTab: String? = null, onFailure: () -> Unit = {}) { // 入口可请求 Android 结果。
        trace(activity, "route.request page=${kind.name} requestCode=$requestCode source=${activity.javaClass.simpleName} tab=$selectedTab") // 记录导航请求与返回关联。
        val route = ARouter.getInstance().build("/browser/page").withString("pageKind", kind.name) // 实际打开的页面类型。
        if (selectedTab != null) route.withString("selectedTab", selectedTab) // 书签和历史复用同一工具页。
        if (requestCode == null) route.navigation(activity) // 普通中间页不需要回传。
        else route.navigation(activity, requestCode, object : NavigationCallback { // 相关入口请求结果。
            override fun onFound(postcard: Postcard?) { trace(activity, "route.found page=${kind.name} requestCode=$requestCode") } // 路由存在不代表已访问关键页。
            override fun onLost(postcard: Postcard?) { trace(activity, "route.lost page=${kind.name} requestCode=$requestCode"); onFailure() } // 路由失败清除等待状态。
            override fun onArrival(postcard: Postcard?) { trace(activity, "route.arrived page=${kind.name} requestCode=$requestCode") } // 到达路由不能触发弹窗。
            override fun onInterrupt(postcard: Postcard?) { trace(activity, "route.interrupted page=${kind.name} requestCode=$requestCode"); onFailure() } // 拦截导航同样清理等待。
        }) // ARouter 的结果启动结束。
    } // 通用业务页导航结束。

    fun highlight(activity: Activity, deeplink: String, onFailure: () -> Unit) { // 高亮暗词由 deeplink 决定真实目标。
        val uri = Uri.parse(deeplink) // 解析 Demo 配置给出的内部链接。
        val destination = when ("${uri.scheme}://${uri.host}${uri.path}") { // 只信任白名单路径。
            "demo4://search/results" -> DemoPage.SEARCH_RESULTS // 搜索结果会回传 SEARCH_RESULTS。
            "demo4://news/detail" -> DemoPage.NEWS_DETAIL // 普通资讯不回传搜索标记。
            else -> null // 不认识的 deeplink 不创造访问结果。
        } // deeplink 分流结束。
        trace(activity, "highlight.resolved destination=${destination?.name ?: "unsupported"}") // 不打印高亮词和完整链接。
        if (destination == null) onFailure() // 解析失败时释放入口等待。
        else page(activity, destination, GuideResultContract.HOME_SEARCH, onFailure = onFailure) // 始终按实际页面标记验证。
    } // 暗词导航结束。

    fun openAdd(activity: Activity, deeplink: String): Boolean { // 弹窗和设置页共用添加页分发。
        val uri = Uri.parse(deeplink) // 从产品提供的链接解析目标。
        if (uri.scheme != "demo4" || uri.host != "guide" || uri.path != "/widget/add") { trace(activity, "add.route_invalid"); return false } // Demo 只接受已实现链接。
        trace(activity, "add.route_requested source=${activity.javaClass.simpleName} target=${uri.getQueryParameter("target")}") // 记录目标类别，不记录完整链接。
        ARouter.getInstance().build("/widget/add") // 不请求结果，不查询组件添加状态。
            .withString("type", uri.getQueryParameter("type") ?: "choose") // 演示组件类型。
            .withString("target", uri.getQueryParameter("target") ?: "choose") // 演示桌面或负一屏。
            .navigation(activity) // 用户点击后的现有路由行为。
        return true // true 只表示已请求打开，不代表添加成功。
    } // 添加 deeplink 分发结束。
} // 路由辅助结束。

/** 保留原工程的安全区适配，避免内容压到状态栏和导航栏。 */
fun View.applySystemInsets() { // 在每个根视图设置一次。
    val left = paddingLeft // 保存初始左边距。
    val top = paddingTop // 保存初始上边距。
    val right = paddingRight // 保存初始右边距。
    val bottom = paddingBottom // 保存初始下边距。
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets -> // Insets 重发时不累加旧值。
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) // 包含导航栏和刘海。
        view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom) // 恢复基准后加入安全区。
        insets // 保留后续分发能力。
    } // Insets 监听结束。
    ViewCompat.requestApplyInsets(this) // 请求初次同步系统栏尺寸。
} // 根视图适配结束。
