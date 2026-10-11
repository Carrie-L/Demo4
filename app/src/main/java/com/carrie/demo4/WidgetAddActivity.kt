package com.carrie.demo4 // 已有快服务添加界面的 Demo 替代落点。

import android.os.Bundle // 生命周期参数。
import androidx.appcompat.app.AppCompatActivity // 显示添加目标。
import com.alibaba.android.arouter.facade.annotation.Route // Demo 添加页内部路由。
import com.carrie.demo4.databinding.ActivityWidgetAddBinding // 添加页 XML。

/** 这里只显示收到的 deeplink 目标，不伪造小组件已添加。 */
@Route(path = "/widget/add") // 弹窗左右按钮和设置 item 共同打开。
class WidgetAddActivity : AppCompatActivity() { // 不反馈成功状态给引导模块。
    override fun onCreate(savedInstanceState: Bundle?) { // 显示实际路由参数。
        super.onCreate(savedInstanceState) // 系统创建完成。
        val binding = ActivityWidgetAddBinding.inflate(layoutInflater) // 加载 XML。
        setContentView(binding.root) // 设置添加落地页。
        binding.root.applySystemInsets() // 安全区处理。
        binding.destination.text = getString( // 展示 deeplink 的组件类型与位置。
            R.string.add_destination, // 产品验收文本模板。
            intent.getStringExtra("type") ?: "choose", // 设置页没有指定组件。
            intent.getStringExtra("target") ?: "choose" // 设置页没有指定桌面或负一屏。
        ) // 添加页文本结束。
        binding.back.setOnClickListener { finish() } // 返回原 Activity。
    } // 落地页初始化结束。
} // 添加页结束。
