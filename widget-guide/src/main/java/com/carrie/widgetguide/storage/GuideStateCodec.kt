package com.carrie.widgetguide.storage

import com.carrie.widgetguide.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Strict decoding: corrupt data/unknown versions must never silently reset the frequency cap. */
object GuideStateCodec {
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
