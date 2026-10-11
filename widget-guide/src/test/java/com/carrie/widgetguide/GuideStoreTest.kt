package com.carrie.widgetguide // 验证本地字段名与两种组件的独立持久化。

import android.content.Context // 访问测试 SharedPreferences。
import androidx.test.core.app.ApplicationProvider // Robolectric 应用上下文。
import kotlinx.coroutines.runBlocking // 等待 IO 读写真实提交。
import org.junit.After // 清除测试留下的存储记录。
import org.junit.Assert.assertEquals // 比对频次与时间。
import org.junit.Assert.assertFalse // 验证不会写单字母前缀。
import org.junit.Assert.assertTrue // 验证停止标志与共享时间。
import org.junit.Test // JUnit4 单元测试。
import org.junit.runner.RunWith // 选择 Robolectric 执行。
import org.robolectric.RobolectricTestRunner // 无需设备即可验证 SP。

/** SP 用可读业务键存七个字段，进程内读写不需要行为资格。 */
@RunWith(RobolectricTestRunner::class) // 使用 Android SharedPreferences 的测试实现。
class GuideStoreTest { // 验证真实读写而不是复制频次函数。
    private val context: Context get() = ApplicationProvider.getApplicationContext() // 每个用例的测试应用。
    private val store: GuideStore get() = GuideStore(context) // 与正式工程共用同一实现。

    @After fun tearDown() = runBlocking { // 测试之间不能共享展示次数。
        assertTrue(store.clearForDebug()) // 清理独立频次文件。
    } // 结束清理。

    @Test fun descriptiveKeysAndIndependentStopsSurviveNewStoreInstance() = runBlocking { // 真实 SP 重新实例化后仍持久。
        val shown = GuideRecord( // 常用工具展示一次并点击添加。
            commonTools = TypeRecord(showCount = 1, lastShownAtMillis = 1234L, stopped = true), // 只停常用工具。
            aiQuickNews = TypeRecord(), // AI 快看保持初始状态。
            lastAnyShownAtMillis = 1234L // 当天共享次数消耗。
        ) // 待保存的展示记录结束。
        assertTrue(store.write(shown)) // 磁盘提交成功。
        assertEquals(shown, GuideStore(context).read()) // 新实例能读回七个字段。
        val prefs = context.getSharedPreferences("browser_widget_guide_frequency", Context.MODE_PRIVATE) // 直接查看物理键名。
        assertTrue(prefs.contains("common_tools_widget_stopped_after_add_click")) // 明确的常用工具键。
        assertTrue(prefs.contains("ai_quick_news_widget_stopped_after_add_click")) // 明确的 AI 快看键。
        assertTrue(prefs.contains("last_any_widget_guide_shown_at_millis")) // 明确的共享展示键。
        assertFalse(prefs.contains("a_stopped")) // 不使用含糊字母作为键。
        assertFalse(prefs.contains("b_stopped")) // 两类都使用完整产品名。
    } // 持久化与键名测试结束。

    @Test fun clearRestoresInitialRecord() = runBlocking { // 用户清数据的结果可以通过文件重置模拟。
        assertTrue(store.write(GuideRecord(aiQuickNews = TypeRecord(2, 1234L), lastAnyShownAtMillis = 1234L))) // 存在旧次数。
        assertTrue(store.clearForDebug()) // Demo 显式清空此频次文件。
        assertEquals(GuideRecord(), GuideStore(context).read()) // 不留下两类历史或共享时间。
    } // 清理测试结束。
} // SP 持久化单测结束。
