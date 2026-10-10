package com.carrie.demo4

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.android.arouter.facade.annotation.Route
import com.carrie.demo4.databinding.ActivityWidgetAddBinding
import com.carrie.widgetguide.core.*

/**
 * 添加路由的验证落地页，仅显示 type 和 target，不实现真正小组件添加。
 * 正式迁移应改为已有桌面/负一屏添加能力，不得把打开本页当作添加成功。
 */
@Route(path = "/widget/add")
class WidgetAddActivity : AppCompatActivity() {
    /**
     * 显示收到的组件类型和目标位置，缺省 choose 表示手动选择入口。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityWidgetAddBinding.inflate(layoutInflater)
        setContentView(binding.root); binding.root.applySystemInsets()
        binding.destination.text = getString(R.string.add_destination,
            intent.getStringExtra("type") ?: "choose", intent.getStringExtra("target") ?: "choose")
        binding.back.setOnClickListener { finish() }
    }
    /**
     * 上报普通业务页；不会设置 everAdded，也不会永久关闭自动引导。
     */
    override fun onResume() {
        super.onResume()
        (application as GuideApplication).controller.visible(PageRef("widget-add", PageKind.OTHER))
    }
}
