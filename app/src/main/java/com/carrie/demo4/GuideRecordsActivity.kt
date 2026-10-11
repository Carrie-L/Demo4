package com.carrie.demo4 // 直接查看设备上的弹窗频次文件。

import android.os.Bundle // Activity 创建入口。
import androidx.appcompat.app.AppCompatActivity // 调试页宿主。
import androidx.lifecycle.lifecycleScope // 读取 SP 不阻塞界面。
import com.alibaba.android.arouter.facade.annotation.Route // 与其它应用内页面使用相同导航框架。
import com.carrie.demo4.databinding.ActivityGuideRecordsBinding // 记录页的 XML 绑定。
import kotlinx.coroutines.launch // 页面恢复时重新读取磁盘。
import java.time.Instant // 把毫秒时间转成人可读时间。
import java.time.ZoneId // 与频控使用同一设备时区。

/** 每次进入和手动刷新都直接读取 SP，展示原始键和值以及最后一次判断。 */
@Route(path = "/debug/guide-records") // 仅应用内部的 Debug 按钮可访问。
class GuideRecordsActivity : AppCompatActivity() { // 仅 Debug 可从配置页面进入。
    private lateinit var binding: ActivityGuideRecordsBinding // 当前页面控件。
    private val app get() = application as GuideApplication // 与弹窗共享同一个本地文件。

    override fun onCreate(savedInstanceState: Bundle?) { // 构造只读记录页面。
        super.onCreate(savedInstanceState) // 初始化页面生命周期。
        if (!BuildConfig.DEBUG) { finish(); return } // 正式构建不开放调试记录。
        binding = ActivityGuideRecordsBinding.inflate(layoutInflater) // 加载记录页布局。
        setContentView(binding.root) // 显示可滚动的内容。
        binding.root.applySystemInsets() // 避开状态栏和导航栏。
        binding.refresh.setOnClickListener { refreshRecords() } // 用户可看到最新落盘状态。
        binding.back.setOnClickListener { finish() } // 原页面栈返回开发验证面板。
    } // 页面初始化结束。

    override fun onResume() { // 回到记录页时重新读取持久文件。
        super.onResume() // 保留系统生命周期。
        refreshRecords() // 从 SP 读取，不依赖首页上一次的计数文字。
    } // 页面恢复结束。

    private fun refreshRecords() { // 同时展示配置、事件和实际持久内容。
        lifecycleScope.launch { // 与异步的写入在同一主线程回显。
            val raw = runCatching { app.store.rawSnapshot() }.getOrElse { // SP 读取异常时展示错误。
                binding.recordText.text = "读取 SP 失败：${it.message.orEmpty()}" // 不把损坏数据当成零次。
                return@launch // 保留错误，停止本次渲染。
            } // 获取真实文件中的全部键值。
            val parsed = runCatching { app.store.read() } // 再按正式规则解码以发现损坏字段。
            val now = app.nowMillis() // 与弹窗经理相同的测试时钟。
            val zone = ZoneId.systemDefault() // 每日共享额度使用的时区。
            fun readable(millis: Long): String = if (millis <= 0L) "无" else Instant.ofEpochMilli(millis).atZone(zone).toString() // 展示毫秒和当地日期。
            val output = buildString { // 把状态排列成可复制阅读的文本。
                appendLine("最近一次返回/判断：${app.lastGuideEvent}") // 明确原因与链路最后抵达的节点。
                appendLine("当前规则：enable=${app.rules.enable}，first=${app.rules.firstPopInterval} 天，second=${app.rules.secondPopInterval} 天") // 当前进程配置。
                appendLine("当前测试时间：${readable(now)}（$now）") // 展示测试时钟和原始毫秒。
                appendLine("SP 文件：browser_widget_guide_frequency") // 对照 adb 的文件名。
                appendLine("原始键值：") // 下方完全来自本地文件。
                if (raw.isEmpty()) appendLine("（文件为空：从未成功展示或已清空）") // 区分未写入与零次。
                raw.forEach { (key, value) -> appendLine("$key = $value") } // 不隐藏共享时间与停止标志。
                appendLine() // 分隔原始数据与解读。
                parsed.onSuccess { record -> // 校验通过时展示易读时间。
                    appendLine("常用工具：${record.commonTools.showCount}/3 次；停止=${record.commonTools.stopped}；上次=${readable(record.commonTools.lastShownAtMillis)}") // A 独立记录。
                    appendLine("AI 快看：${record.aiQuickNews.showCount}/3 次；停止=${record.aiQuickNews.stopped}；上次=${readable(record.aiQuickNews.lastShownAtMillis)}") // B 独立记录。
                    appendLine("A/B 共享最近展示：${readable(record.lastAnyShownAtMillis)}") // 同一自然日最多展示一次。
                } // 解释当前快照结束。
                parsed.onFailure { appendLine("记录校验失败：${it.message.orEmpty()}") } // 损坏时保留原始键供排查。
            } // 状态文本组装结束。
            binding.recordText.text = output // 显示最新读盘结果。
        } // 读盘协程结束。
    } // 记录刷新结束。
} // 只读数据页面结束。
