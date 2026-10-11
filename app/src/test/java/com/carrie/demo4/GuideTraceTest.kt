package com.carrie.demo4 // 日志上限与顺序的回归验证。

import androidx.test.core.app.ApplicationProvider // 获取真实测试 Application。
import org.junit.Assert.assertEquals // 保证事件队列有固定上限。
import org.junit.Assert.assertTrue // 核对最早和最新事件。
import org.junit.Test // JUnit4 测试入口。
import org.junit.runner.RunWith // 选择 Robolectric 环境。
import org.robolectric.RobolectricTestRunner // 无需设备即可运行 Debug 日志测试。
import org.robolectric.annotation.Config // 固定模拟 SDK。

/** 频繁页面回调只保留有限的当前进程日志，同时保留可读顺序。 */
@RunWith(RobolectricTestRunner::class) // 运行实际的 GuideApplication。
@Config(sdk = [33]) // 与返回链路测试使用同一 Android 模拟版本。
class GuideTraceTest { // 覆盖调试页可见的最近事件快照。
    @Test fun recentEventsKeepLastOneHundredTwentyEntries() { // 无限访问不会让调试日志占满内存。
        val app = ApplicationProvider.getApplicationContext<GuideApplication>() // 获取应用级日志实例。
        repeat(125) { app.recordGuideEvent("trace_case_${it + 1}") } // 超过队列上限五条。
        val events = app.recentGuideEvents() // 获取只读快照。
        assertEquals(120, events.size) // 只保留最近一百二十条。
        assertTrue(events.first().contains("trace_case_6")) // 最旧五条被删除。
        assertTrue(events.last().contains("trace_case_125")) // 最新节点可用于定位故障。
        assertTrue(app.lastGuideEvent == events.last()) // 页面首行与时间线最后一条一致。
    } // 有界顺序验证结束。
} // Debug 链路日志测试结束。
