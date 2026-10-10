package com.carrie.demo4

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.alibaba.android.arouter.launcher.ARouter
import com.carrie.widgetguide.core.*
import com.carrie.widgetguide.ui.WidgetGuideNavigator
import java.util.UUID

/**
 * 验证宿主的路由适配层：负责生成可信的页面身份和入口信息，不在点击时确认行为。
 * 正式项目可替换为现有 ARouter 封装；核心模块不依赖 ARouter。
 */
object DemoNavigation {
    /**
     * 打开一个新的验证业务页；目标 DetailActivity 初次创建时上报 NavigationEvent。
     * @param context 发起导航的页面上下文。
     * @param from 当前真实可见页实例，作为 fromId/fromKind 传递。
     * @param to 目标业务类型，作为 pageKind 传递；pageId 为此次新页面生成。
     * @param entry 用户点击的业务入口，默认 OTHER 不授予特殊行为资格。
     * pageId 标识页面实例；navigationId 标识导航动作，两者必须分别生成。
     * 路由失败时目标页不会创建，因此不会上报成功进入。
     */
    fun page(context: Context, from: PageRef, to: PageKind, entry: EntryPoint = EntryPoint.OTHER) {
        ARouter.getInstance().build("/browser/page")
            // pageId/pageKind 组合为目标 PageRef；每次打开新实例生成新 ID。
            .withString("pageId", UUID.randomUUID().toString())
            .withString("pageKind", to.name)
            // 来源使用当前可见实例，不能用路由地址或 Activity 类名替代。
            .withString("fromId", from.id).withString("fromKind", from.kind.name)
            // 入口说明跳转原因；navigationId 单独标识这次动作，用于重复上报去重。
            .withString("entry", entry.name).withString("navigationId", UUID.randomUUID().toString())
            .navigation(context)
    }

    /**
     * 创建添加入口适配器，仅接受 demo4://guide/widget/add。
     * 参数 type：tools / ai / choose；target：desktop / assistant / choose。
     * 缺省 choose 用于设置页手动入口。返回 true 只表示已调用路由，不代表小组件添加成功。
     * 迁移时替换为正式 deeplink，并按真实路由回调/错误处理定义跳转是否已接受。
     */
    fun navigator(context: Context) = WidgetGuideNavigator { deeplink ->
        val uri = Uri.parse(deeplink)
        if (uri.scheme != "demo4" || uri.host != "guide" || uri.path != "/widget/add") false
        else {
            ARouter.getInstance().build("/widget/add")
                .withString("type", uri.getQueryParameter("type") ?: "choose")
                .withString("target", uri.getQueryParameter("target") ?: "choose")
                .navigation(context)
            true
        }
    }
}

/**
 * 以初始 padding 为基准添加系统栏和刘海 Insets，避免重复分发时累计边距。
 * 由每个验证页面设置内容后调用一次，处理 edge-to-edge 下的内容遮挡。
 */
fun View.applySystemInsets() {
    val startLeft = paddingLeft; val startTop = paddingTop
    val startRight = paddingRight; val startBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(startLeft + bars.left, startTop + bars.top, startRight + bars.right, startBottom + bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
