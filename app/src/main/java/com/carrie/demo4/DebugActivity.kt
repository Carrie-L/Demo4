package com.carrie.demo4 // 只在 Debug 开放的频次与路由验证面板。

import android.os.Bundle // Activity 生命周期数据。
import android.widget.Toast // 告知配置输入或存储写入失败。
import androidx.appcompat.app.AppCompatActivity // XML 页面宿主。
import androidx.lifecycle.lifecycleScope // 清理 SP 时避免阻塞 UI。
import com.alibaba.android.arouter.facade.annotation.Route // Debug 内部路由。
import com.carrie.demo4.databinding.ActivityDebugBinding // XML 控件绑定。
import com.carrie.widgetguide.GuideRules // 模拟后台下发的共享配置。
import kotlinx.coroutines.launch // 异步等待重置结果。
import java.time.Instant // 显示测试时钟。
import java.time.ZoneId // 使用设备当前时区显示测试日期。

/** 调试配置不会让已经返回且被拦截的旧行为补弹。 */
@Route(path = "/debug/guide") // 只在首页 Debug 按钮可达。
class DebugActivity : AppCompatActivity() { // 发布版立即退出。
    private val app get() = application as GuideApplication // 取进程唯一配置。

    override fun onCreate(savedInstanceState: Bundle?) { // 渲染配置和测试时间。
        super.onCreate(savedInstanceState) // 初始化 Activity。
        if (!BuildConfig.DEBUG) { finish(); return } // Release 禁止 Debug 操作。
        val binding = ActivityDebugBinding.inflate(layoutInflater) // 加载 XML 控件。
        setContentView(binding.root) // 显示开发面板。
        binding.root.applySystemInsets() // 保留系统栏安全边距。
        binding.enablePop.isChecked = app.rules.enable // 云配总开关。
        binding.firstInterval.setText(app.rules.firstPopInterval?.toString().orEmpty()) // 首次展示后间隔。
        binding.secondInterval.setText(app.rules.secondPopInterval?.toString().orEmpty()) // 第二次展示后间隔。
        binding.highlightToNews.isChecked = app.highlightDeeplink == "demo4://news/detail" // 控制暗词 deeplink 落点。
        fun updateTime() { // 查看当前 Debug 时间，不触发引导。
            binding.testTime.text = Instant.ofEpochMilli(app.nowMillis()).atZone(ZoneId.systemDefault()).toString() // 时区随系统。
        } // 时钟说明结束。
        updateTime() // 首次进入面板显示当前时间。
        binding.applyConfig.setOnClickListener { // 应用这份配置到两种弹窗。
            val first = binding.firstInterval.text.toString().toLongOrNull() // 读取固定天数。
            val second = binding.secondInterval.text.toString().toLongOrNull() // 读取第二间隔。
            if (first == null || second == null || first < 0 || second < 0) { // 不接受空值与负数。
                Toast.makeText(this, R.string.invalid_intervals, Toast.LENGTH_SHORT).show() // 告知正确格式。
            } else { // 两个间隔都合法。
                app.rules = GuideRules(binding.enablePop.isChecked, first, second) // 更新进程内云配。
                app.highlightDeeplink = if (binding.highlightToNews.isChecked) "demo4://news/detail" else "demo4://search/results" // 更新暗词实际落点。
                Toast.makeText(this, R.string.config_applied, Toast.LENGTH_SHORT).show() // 配置仅影响未来新返回。
            } // 配置校验结束。
        } // 应用配置监听结束。
        binding.advanceDay.setOnClickListener { app.advanceDayForDebug(); updateTime() } // 前进时钟不自动弹。
        binding.resetState.setOnClickListener { // 用户显式重置 Debug 数据。
            androidx.appcompat.app.AlertDialog.Builder(this) // 防止误触清除频次。
                .setMessage(R.string.confirm_reset) // 说明清理影响。
                .setNegativeButton(android.R.string.cancel, null) // 取消时不写存储。
                .setPositiveButton(android.R.string.ok) { _, _ -> // 确认才调用清理。
                    lifecycleScope.launch { // 在协程中等待 IO 提交。
                        if (app.resetForDebug()) updateTime() // 成功同步测试时钟显示。
                        else Toast.makeText(this@DebugActivity, R.string.reset_failed, Toast.LENGTH_LONG).show() // 写失败告知用户。
                    } // 清理协程结束。
                }.show() // 展示确认框。
        } // Debug 重置监听结束。
        binding.back.setOnClickListener { finish() } // 正常返回首页。
    } // 面板初始化结束。
} // Debug 面板结束。
