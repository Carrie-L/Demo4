package com.carrie.demo4

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.android.arouter.facade.annotation.Route
import com.carrie.demo4.databinding.ActivityLearnCBinding

/**
 * Activity C: 关键落点页面。
 * 对应 ARouter 路由路径：/learn/pageC
 * 1. 盖章【visit_c】打卡凭证。
 * 2. 即使继续打开深层页面 Activity D 再返回，也不影响已有的【visit_c】凭证。
 * 3. 将【visit_c】凭证回传给调用方（Activity B 或直达的 Activity A）。
 */
@Route(path = "/learn/pageC")
class LearnCActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLearnCBinding

    private val launchDLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 从 D 页面返回，不破坏 C 页面的打卡凭证状态
            binding.tvReceivedInfo.text = getString(R.string.status_returned_from_d)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLearnCBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemInsets()

        val reqCode = intent.getIntExtra(LearnAActivity.EXTRA_REQUEST_CODE, -1)
        val msg = intent.getStringExtra(LearnAActivity.EXTRA_REQUEST_MSG) ?: "无"

        binding.tvReceivedInfo.text = getString(R.string.status_received_param, "ReqCode: $reqCode\nMsg: $msg\n关键页【visit_c】已盖章完成！")

        // 前往深层页面 Activity D (/learn/pageD)
        binding.btnLaunchD.setOnClickListener {
            val intent = Intent(this, LearnDActivity::class.java)
            launchDLauncher.launch(intent)
        }

        // 携带【visit_c】凭证返回上一页 (B 或 A)
        binding.btnReturnWithStamp.setOnClickListener {
            val resultIntent = Intent().apply {
                putExtra(LearnAActivity.EXTRA_VISIT, LearnAActivity.VISIT_C)
                putExtra(LearnAActivity.EXTRA_RESULT_DATA, "来自 C 的确认结果")
                putExtra(LearnAActivity.EXTRA_REQUEST_CODE, reqCode)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        // 返回【结果1】+【visit_c】凭证给 Activity B
        binding.btnSendResult1.setOnClickListener {
            val resultIntent = Intent().apply {
                putExtra(LearnAActivity.EXTRA_RESULT_DATA, "结果1")
                putExtra(LearnAActivity.EXTRA_VISIT, LearnAActivity.VISIT_C)
                putExtra(LearnAActivity.EXTRA_REQUEST_CODE, reqCode)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        // 取消 / 不返回结果
        binding.btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//
//        val originCode = intent.getIntExtra("originCode", -1)
//
//        // 当用户完成关键操作（或页面成功加载）时，给 Intent 盖上 "visit_c" 戳
//        val resultIntent = Intent().apply {
//            putExtra("EXTRA_VISIT", "visit_c") // 关键打卡凭证！
//            putExtra("originCode", originCode)
//        }
//        setResult(RESULT_OK, resultIntent)
//    }
}
