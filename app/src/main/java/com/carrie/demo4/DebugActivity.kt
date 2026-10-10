package com.carrie.demo4

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.alibaba.android.arouter.facade.annotation.Route
import com.carrie.demo4.databinding.ActivityDebugBinding
import com.carrie.widgetguide.core.*
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 开发验证面板：修改内存云配、前进测试时钟、显式清空测试状态。
 * 入口和 Activity 双重限制为 Debug，正式产品不能提供清除频控的业务入口。
 */
@Route(path = "/debug/guide")
class DebugActivity : AppCompatActivity() {
    /**
     * 读取最新应用级依赖；重置会替换 controller。
     */
    private val app get() = application as GuideApplication
    /**
     * 绑定调试操作，非 Debug 立即退出；非法间隔不更新当前配置。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return }
        val binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root); binding.root.applySystemInsets()
        val config = app.configSource.config.value
        binding.enablePop.isChecked = config.enablePop
        binding.firstInterval.setText(config.firstPopInterval.toString())
        binding.secondInterval.setText(config.secondPopInterval.toString())
        /**
         * 展示注入时钟的当前时间，帮助验证冷却与自然日限制。
         */
        fun updateTime() { binding.testTime.text = Instant.ofEpochMilli(app.clock.now().millis).atZone(app.clock.now().zone).toString() }
        updateTime()
        binding.applyConfig.setOnClickListener {
            val first = binding.firstInterval.text.toString().toIntOrNull()
            val second = binding.secondInterval.text.toString().toIntOrNull()
            if (first == null || second == null || first < 0 || second < 0) {
                Toast.makeText(this, R.string.invalid_intervals, Toast.LENGTH_SHORT).show()
            } else {
                app.configSource.config.value = GuideConfig(binding.enablePop.isChecked, first, second)
                Toast.makeText(this, R.string.config_applied, Toast.LENGTH_SHORT).show()
            }
        }
        binding.advanceDay.setOnClickListener { app.advanceDayForDebug(); updateTime() }
        binding.resetState.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this).setMessage(R.string.confirm_reset)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> lifecycleScope.launch {
                    runCatching { app.resetForDebug() }.onSuccess { updateTime() }.onFailure {
                        Toast.makeText(this@DebugActivity, R.string.reset_failed, Toast.LENGTH_LONG).show()
                    }
                } }.show()
        }
        binding.back.setOnClickListener { finish() }
    }
    /**
     * 将调试页作为 OTHER 上报，以正确切断未完成搜索链路。
     */
    override fun onResume() {
        super.onResume()
        if (BuildConfig.DEBUG) app.controller.visible(PageRef("debug", PageKind.OTHER))
    }
}
