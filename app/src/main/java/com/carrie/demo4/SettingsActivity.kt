package com.carrie.demo4 // 我的设置页手动添加入口。

import android.os.Build // 判断设备厂商是否荣耀。
import android.os.Bundle // Activity 创建参数。
import android.view.View // 非荣耀设备隐藏列表项。
import androidx.appcompat.app.AppCompatActivity // 使用现有 Android XML 页面。
import com.alibaba.android.arouter.facade.annotation.Route // 设置页面内部路由。
import com.carrie.demo4.databinding.ActivitySettingsBinding // 设置列表 XML 绑定。

/** 荣耀限制只用于设置入口，不控制自动弹窗。 */
@Route(path = "/settings/main") // 由我的设置按钮打开。
class SettingsActivity : AppCompatActivity() { // 不调用 WidgetGuideManager。
    override fun onCreate(savedInstanceState: Bundle?) { // 构建一次设置列表。
        super.onCreate(savedInstanceState) // 初始化 Activity。
        val binding = ActivitySettingsBinding.inflate(layoutInflater) // 加载 XML。
        setContentView(binding.root) // 展示设置内容。
        binding.root.applySystemInsets() // 避免系统栏遮挡。
        binding.addWidget.visibility = if (Build.MANUFACTURER.equals("HONOR", ignoreCase = true)) View.VISIBLE else View.GONE // 非荣耀隐藏入口。
        binding.addWidget.setOnClickListener { DemoNavigation.openAdd(this, "demo4://guide/widget/add") } // 手动 deeplink 不写频次。
        binding.back.setOnClickListener { finish() } // 正常原栈返回我的页。
    } // 设置页建立结束。
} // 设置页结束。
