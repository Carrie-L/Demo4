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

@Route(path = "/debug/guide")
class DebugActivity : AppCompatActivity() {
    private val app get() = application as GuideApplication
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return }
        val binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root); binding.root.applySystemInsets()
        val config = app.configSource.config.value
        binding.enablePop.isChecked = config.enablePop
        binding.firstInterval.setText(config.firstPopInterval.toString())
        binding.secondInterval.setText(config.secondPopInterval.toString())
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
    override fun onResume() {
        super.onResume()
        if (BuildConfig.DEBUG) app.controller.visible(PageRef("debug", PageKind.OTHER))
    }
}
