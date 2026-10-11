package com.carrie.demo4

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.android.arouter.facade.annotation.Route
import com.carrie.demo4.databinding.ActivityLearnDBinding

/**
 * Activity D: 示范深层页面。
 * 从 C 页面进入 D 页面后返回 C，不会丢失 C 页面已完成的【visit_c】打卡凭证。
 */
@Route(path = "/learn/pageD")
class LearnDActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLearnDBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLearnDBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemInsets()

        binding.btnBackToC.setOnClickListener {
            // 返回 C 页面
            setResult(RESULT_OK)
            finish()
        }
    }
}
