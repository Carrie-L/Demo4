package com.carrie.demo4

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.android.arouter.facade.annotation.Route
import com.carrie.demo4.databinding.ActivityWidgetAddBinding
import com.carrie.widgetguide.core.*

/** Verification endpoint only. Replace deeplinks with the two existing widget products on migration. */
@Route(path = "/widget/add")
class WidgetAddActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityWidgetAddBinding.inflate(layoutInflater)
        setContentView(binding.root); binding.root.applySystemInsets()
        binding.destination.text = getString(R.string.add_destination,
            intent.getStringExtra("type") ?: "choose", intent.getStringExtra("target") ?: "choose")
        binding.back.setOnClickListener { finish() }
    }
    override fun onResume() {
        super.onResume()
        (application as GuideApplication).controller.visible(PageRef("widget-add", PageKind.OTHER))
    }
}
