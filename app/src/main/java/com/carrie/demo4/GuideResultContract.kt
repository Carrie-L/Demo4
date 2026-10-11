package com.carrie.demo4 // 只在相关业务页面传递一次访问结果。

import androidx.lifecycle.ViewModel // 旋转期间保存本轮尚未完成的返回关系。

/** 本 Demo 的几种真实业务页面；普通中间页不参与访问判断。 */
enum class DemoPage { SEARCH_ACTIVATION, SEARCH_RESULTS, SCANNER, TOOLS, DOWNLOADS, NEWS_DETAIL, AI_SEARCH, AI_SECONDARY, OTHER, GAME, VIDEO } // 仅 Demo 复用 Activity 时用来切换 UI。

/** requestCode 标识原调用关系，visit 标识实际访问的关键页。 */
object GuideResultContract { // 常量集中说明，避免散落魔法数字。
    const val EXTRA_VISIT = "browser.widget.guide_visit" // 追加在原业务返回 Intent 的独立字段。
    const val SEARCH_RESULTS = "search_results" // 搜索结果实际显示。
    const val TOOLS = "tools" // 书签历史容器或下载实际显示。
    const val SCAN = "scan" // 扫一扫页面实际显示。
    const val AI_DETAIL = "ai_detail" // AI 快看入口的资讯详情实际显示。
    const val HOME_SEARCH = 6101 // 首页直达结果或打开激活页。
    const val HOME_SCAN = 6102 // 首页进入扫一扫。
    const val MINE_TOOLS = 6103 // 我的进入工具。
    const val HOME_AI_DETAIL = 6104 // 首页 AI 快看进入资讯详情。
    const val SECONDARY_AI_DETAIL = 6105 // AI 快看二级页进入资讯详情。
    const val ACTIVATION_SEARCH = 6110 // 激活页打开搜索结果。

    fun expectedVisit(requestCode: Int): String? = when (requestCode) { // 只接收该入口期望的实际落点。
        HOME_SEARCH, ACTIVATION_SEARCH -> SEARCH_RESULTS // 搜索必须访问结果页。
        HOME_SCAN -> SCAN // 扫描只要求访问扫一扫页。
        MINE_TOOLS -> TOOLS // 我的工具使用一个容器或下载页。
        HOME_AI_DETAIL, SECONDARY_AI_DETAIL -> AI_DETAIL // AI 资讯按原入口展示 B。
        else -> null // 未知请求码不产生弹窗。
    } // 对应关系结束。
} // 返回协议结束。

/** 普通 ViewModel 只保存当前页面尚未返回的请求。 */
class GuideReturnState : ViewModel() { // 进程重建后不会复活旧行为资格。
    var waitingRequestCode: Int? = null // 回调消费后立刻清空。
    var hasVisitedSearchResults = false // 只在搜索激活页中转结果访问标记。
} // 本轮内存状态结束。
