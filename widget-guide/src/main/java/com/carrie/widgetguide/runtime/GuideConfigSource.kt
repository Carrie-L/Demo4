package com.carrie.widgetguide.runtime

import com.carrie.widgetguide.core.GuideConfig
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * 云配接入契约：发布当前已验证配置。网络请求、缓存过期和重试由项目适配器负责。
 * config 初始值应为 GuideConfig()，防止加载期间误弹。
 */
interface GuideConfigSource {
    /** 当前已校验配置；新订阅者应立即获得当前值，更新后由 Controller 触发重算。 */
    val config: StateFlow<GuideConfig>
}

/**
 * 将 browserWidgetGuideRules 转为领域配置；任何格式错误均返回默认关闭配置。
 */
object GuideConfigParser {
    /** 缺失或非法配置均关闭；请求/缓存策略由外部云配适配器负责。 */
    fun parse(raw: String?): GuideConfig = try {
        val root = JSONObject(requireNotNull(raw)).getJSONObject("browserWidgetGuideRules")
        val enabled = root.get("enablePop")
        require(enabled is Boolean)
        if (!enabled) GuideConfig() else {
            val first = root.get("firstPopInterval")
            val second = root.get("secondPopInterval")
            require(first is Int && second is Int)
            GuideConfig(true, first, second)
        }
    } catch (_: Exception) { GuideConfig() }
}
