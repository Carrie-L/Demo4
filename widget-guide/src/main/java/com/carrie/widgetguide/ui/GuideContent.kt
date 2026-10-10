package com.carrie.widgetguide.ui

import androidx.annotation.DrawableRes
import com.carrie.widgetguide.core.GuideType

/**
 * 引导的纯展示内容，由正式产品提供。
 * @property title 弹窗标题。
 * @property description 组件用途说明。
 * @property previewRes 预览图片的 Drawable 资源 ID；仓库内是 XML 示意图。
 * @property previewDescription 图片无障碍描述，供屏幕阅读器朗读。
 * @property desktopDeeplink 添加到桌面的路由地址。
 * @property assistantDeeplink 添加到负一屏的路由地址；具体厂商能力由现有业务负责。
 */
data class GuideContent(
    val title: String,
    val description: String,
    @DrawableRes val previewRes: Int,
    val previewDescription: String,
    val desktopDeeplink: String,
    val assistantDeeplink: String
)

/**
 * 根据 A/B 类型提供内容；不读取频控、不决定是否可以展示。
 */
fun interface GuideContentProvider {
    /** 返回指定组件类型的完整展示内容；调用发生在主线程，不应执行网络或磁盘加载。 */
    fun content(type: GuideType): GuideContent
}
/**
 * 打开已有添加入口；open 返回是否接受跳转，不代表已经成功添加组件。
 */
fun interface WidgetGuideNavigator {
    /** 主线程请求打开指定添加入口；未接受返回 false，异常由弹窗转为失败提示。 */
    fun open(deeplink: String): Boolean
}
