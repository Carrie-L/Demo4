package com.carrie.widgetguide.storage

import com.carrie.widgetguide.core.*
import org.json.JSONArray
import org.json.JSONObject


/**
 * 版本 1 状态协议的唯一编解码入口；严格解析以避免损坏数据重置频控。
 */
object GuideStateCodec {
    /**
     * 编码版本、已完成行为、展示记录和可空预约；行为排序使同一状态输出稳定。
     * JSON 字段 shownAt/day 分别对应 shownAtMillis/localEpochDay。
     */
    fun encode(state: GuideState): String = JSONObject().apply {
        put("version", 1)
        put("behaviors", JSONArray(state.behaviors.map { it.name }.sorted()))
        put("records", JSONArray().apply {
            state.records.forEach { record -> put(JSONObject().apply {
                put("id", record.id); put("type", record.type.name)
                put("shownAt", record.shownAtMillis); put("day", record.localEpochDay)
            }) }
        })
        put("pending", state.pending?.let { JSONObject().put("id", it.id).put("type", it.type.name) } ?: JSONObject.NULL)
    }.toString()

    /**
     * 恢复版本 1 状态，并校验枚举、记录 ID 唯一、预约不与展示记录冲突等约束。
     * 遇到不兼容版本或非法内容直接抛异常，不能返回 GuideState() 绕过次数限制。
     */
    fun decode(raw: String): GuideState {
        val json = JSONObject(raw)
        require(json.get("version") == 1) { "Unsupported guide state schema" }
        require(json.has("pending")) { "Incomplete guide state" }
        val flags = json.getJSONArray("behaviors")
        val behaviors = (0 until flags.length()).map { Behavior.valueOf(flags.getString(it)) }.toSet()
        val array = json.getJSONArray("records")
        val records = (0 until array.length()).map { index ->
            val record = array.getJSONObject(index)
            ShowRecord(record.getString("id"), GuideType.valueOf(record.getString("type")),
                record.getLong("shownAt"), record.getLong("day")).also {
                require(it.id.isNotBlank() && it.shownAtMillis >= 0)
                require(it.localEpochDay in -365_000_000L..365_000_000L)
            }
        }
        require(records.map { it.id }.distinct().size == records.size) { "Duplicate display IDs" }
        val pending = if (json.isNull("pending")) null else json.getJSONObject("pending").let {
            PendingShow(it.getString("id"), GuideType.valueOf(it.getString("type"))).also { p ->
                require(p.id.isNotBlank() && records.none { record -> record.id == p.id })
            }
        }
        return GuideState(behaviors, records, pending)
    }
}
