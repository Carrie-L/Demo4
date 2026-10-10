package com.carrie.demo4

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.android.arouter.facade.annotation.Route
import com.carrie.demo4.databinding.ActivitySettingsBinding
import com.carrie.widgetguide.core.*

@Route(path = "/settings/main")
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root); binding.root.applySystemInsets()
        binding.addWidget.setOnClickListener { DemoNavigation.navigator(this).open("demo4://guide/widget/add") }
        binding.back.setOnClickListener { finish() }
    }
    override fun onResume() {
        super.onResume()
        (application as GuideApplication).controller.visible(PageRef("settings", PageKind.OTHER))
    }
}
