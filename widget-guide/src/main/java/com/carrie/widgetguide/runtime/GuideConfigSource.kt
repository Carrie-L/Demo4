package com.carrie.widgetguide.runtime

import com.carrie.widgetguide.core.GuideConfig
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

interface GuideConfigSource { val config: StateFlow<GuideConfig> }

object GuideConfigParser {
    /** Malformed or missing config is disabled. Fetch/cache policy belongs to the cloud adapter. */
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
