package com.carrie.widgetguide // 存储实现与页面回传协议分开。

import android.content.Context // 获取应用级 SharedPreferences。
import android.content.SharedPreferences // 一次 Editor 保存所有频次字段。
import kotlinx.coroutines.Dispatchers // 磁盘读写放到 IO 线程。
import kotlinx.coroutines.withContext // 等待写入结果但不阻塞主线程。

/** 只存展示历史和点击停止状态，不存本轮行为。 */
class GuideStore(context: Context) { // 实际项目已有 MMKV 时可替换此实现。
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences( // 进程内共用一个文件。
        "browser_widget_guide_frequency", Context.MODE_PRIVATE // 独立于废弃旧版数据的文件名。
    ) // 不读旧版的行为资格。

    private fun prefix(type: GuideType): String = when (type) { // 键名使用业务名。
        GuideType.COMMON_TOOLS -> "common_tools_widget" // 常用工具组件。
        GuideType.AI_QUICK_NEWS -> "ai_quick_news_widget" // AI 快看组件。
    } // 键名完整表达对应产品组件。

    suspend fun read(): GuideRecord = withContext(Dispatchers.IO) { // 在 IO 线程读取最新持久快照。
        fun typeRecord(type: GuideType): TypeRecord { // 同一方式读取两种组件。
            val name = prefix(type) // 用完整业务名构造三个键。
            return TypeRecord( // 组装某类型的独立记录。
                prefs.getInt("${name}_show_count", 0), // 已展示次数。
                prefs.getLong("${name}_last_shown_at_millis", 0L), // 上次展示毫秒时间。
                prefs.getBoolean("${name}_stopped_after_add_click", false) // 已点添加停止。
            ) // 完成某类型记录。
        } // 局部读取函数结束。
        val result = GuideRecord( // 一次读取双方记录和共享时间。
            typeRecord(GuideType.COMMON_TOOLS), // 常用工具。
            typeRecord(GuideType.AI_QUICK_NEWS), // AI 快看。
            prefs.getLong("last_any_widget_guide_shown_at_millis", 0L) // 两弹窗共用的最近展示时间。
        ) // 组装完成。
        val records = listOf(result.commonTools, result.aiQuickNews) // 验证实际已有频次记录。
        require(records.all { it.showCount in 0..3 && (it.showCount == 0 || it.lastShownAtMillis > 0L) }) { // 已展示不能缺时间。
            "Invalid widget guide type history" // 损坏状态不能当成新用户。
        } // 类型记录校验结束。
        require(result.lastAnyShownAtMillis >= 0L && (records.all { it.showCount == 0 } || result.lastAnyShownAtMillis >= records.maxOf { it.lastShownAtMillis })) { // 共享时间不能早于任一类型。
            "Invalid shared widget guide history" // 防止跨日额度被错误重置。
        } // 共享字段校验结束。
        result // 返回持久快照。
    } // IO 读取结束。

    suspend fun write(record: GuideRecord): Boolean = withContext(Dispatchers.IO) { // 串行调用者负责写入先后顺序。
        val editor = prefs.edit() // 将七个字段放进同一 Editor。
        fun put(type: GuideType, value: TypeRecord) { // 保存其中一种组件记录。
            val name = prefix(type) // 选用可读的业务前缀。
            editor.putInt("${name}_show_count", value.showCount) // 写已展示次数。
            editor.putLong("${name}_last_shown_at_millis", value.lastShownAtMillis) // 写该类型时间。
            editor.putBoolean("${name}_stopped_after_add_click", value.stopped) // 写该类型停止标志。
        } // 一类组件写入结束。
        put(GuideType.COMMON_TOOLS, record.commonTools) // 保存常用工具。
        put(GuideType.AI_QUICK_NEWS, record.aiQuickNews) // 保存 AI 快看。
        editor.putLong("last_any_widget_guide_shown_at_millis", record.lastAnyShownAtMillis) // 保存共享时间。
        editor.commit() // 校验磁盘提交，失败由管理器暂停自动弹窗。
    } // IO 写入结束。

    suspend fun clearForDebug(): Boolean = withContext(Dispatchers.IO) { // 只给 Debug 面板显式调用。
        prefs.edit().clear().commit() // 清除测试频次及停止状态。
    } // 清理结束。

    suspend fun rawSnapshot(): Map<String, Any?> = withContext(Dispatchers.IO) { // 调试页从实际 SP 文件读取原始键值。
        prefs.all.toSortedMap() // 返回独立文件的完整快照，不使用管理器的内存缓存。
    } // 原始记录读取结束。
} // 存储类结束。
