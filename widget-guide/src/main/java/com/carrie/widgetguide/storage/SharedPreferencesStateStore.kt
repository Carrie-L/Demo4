package com.carrie.widgetguide.storage

import android.content.Context
import android.annotation.SuppressLint


/**
 * 默认存储实现。使用 applicationContext 避免持有 Activity。
 * @param context 用于取得应用私有 SP。
 * @param name 独立 SP 文件名，默认 browser_widget_guide；备份规则须排除此文件。
 */
class SharedPreferencesStateStore(context: Context, name: String = "browser_widget_guide") : StringStateStore {
    /**
     * 全部状态仅占 state_v1 一个 key，不拆分写入次数、日期、行为。
     */
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    /**
     * 返回完整状态字符串；null 表示此文件尚未记录状态。
     */
    override fun read(): String? = preferences.getString("state_v1", null)
    // 保留 commit 的布尔结果，用于上层失败恢复。
    /**
     * 必须检查 commit() 的布尔结果；KTX edit(commit = true) 不返回这个结果。
     * 方法本身同步阻塞，调用者 PersistentGuideRepository 保证在 IO 上执行。
     */
    @SuppressLint("UseKtx")
    override fun write(value: String): Boolean = preferences.edit().putString("state_v1", value).commit()
}
