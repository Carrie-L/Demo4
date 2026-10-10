package com.carrie.demo4

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.android.arouter.facade.annotation.Route
import com.carrie.demo4.databinding.ActivitySettingsBinding
import com.carrie.widgetguide.core.*

/**
 * 手动添加入口：直接调用 navigator，不经过自动资格判断，也不增加展示次数。
 */
@Route(path = "/settings/main")
class SettingsActivity : AppCompatActivity() {
    /**
     * 设置页按钮进入通用添加选择页，type/target 缺省为 choose。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root); binding.root.applySystemInsets()
        binding.addWidget.setOnClickListener { DemoNavigation.navigator(this).open("demo4://guide/widget/add") }
        binding.back.setOnClickListener { finish() }
    }
    /**
     * 上报普通业务页，避免从搜索激活绕经设置后还能沿用旧链路。
     */
    override fun onResume() {
        super.onResume()
        (application as GuideApplication).controller.visible(PageRef("settings", PageKind.OTHER))
    }
}
