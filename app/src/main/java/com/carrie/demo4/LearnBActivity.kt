package com.carrie.demo4

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.carrie.demo4.databinding.ActivityLearnBBinding

/**
 * Activity B: 中转页。
 * 对应 ARouter 路由路径：/learn/pageB
 * 1. 接收 Activity A 的请求。
 * 2. 启动 Activity C 并监听 C 是否给出了【visit_c】凭证。
 * 3. 只有从 C 返回且获取到【visit_c】凭证时，才透传结果给 Activity A；若从 B 直接返回，则不给 A 凭证。
 */
@Route(path = "/learn/pageB")
class LearnBActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLearnBBinding
    private var receivedReqCodeFromA: Int = -1
    private var visitProofFromC: String? = null

    /* =================================================================================
     * 新实现：监听 Activity C 的【visit_c】打卡凭证
     * ================================================================================= */
    private val launchCLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val visitProof = result.data?.getStringExtra(LearnAActivity.EXTRA_VISIT)
                val returnedData = result.data?.getStringExtra(LearnAActivity.EXTRA_RESULT_DATA) ?: "结果1"

                if (visitProof == LearnAActivity.VISIT_C) {
                    visitProofFromC = visitProof
                    binding.tvStatus.text = getString(R.string.status_b_received_c, REQUEST_CODE_B_TO_C, returnedData)
                    binding.btnSendResult2.isEnabled = true
                } else {
                    binding.tvStatus.text = getString(R.string.status_b_no_c)
                    binding.btnSendResult2.isEnabled = false
                }
            } else {
                binding.tvStatus.text = getString(R.string.status_b_no_c)
                binding.btnSendResult2.isEnabled = false
            }
        }

    /* =================================================================================
     * 保留原基础实现（仅供对比学习）：
     *
     * private val launchCLauncherOriginal =
     *     registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
     *         if (result.resultCode == RESULT_OK) {
     *             val returnedData = result.data?.getStringExtra(LearnAActivity.EXTRA_RESULT_DATA)
     *             if (returnedData == "结果1") {
     *                 binding.tvStatus.text = getString(R.string.status_b_received_c, REQUEST_CODE_B_TO_C, returnedData)
     *                 binding.btnSendResult2.isEnabled = true
     *             }
     *         }
     *     }
     * ================================================================================= */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLearnBBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemInsets()

        receivedReqCodeFromA = intent.getIntExtra(LearnAActivity.EXTRA_REQUEST_CODE, -1)
        val msgFromA = intent.getStringExtra(LearnAActivity.EXTRA_REQUEST_MSG) ?: "无"

        binding.tvReceivedInfo.text = getString(R.string.status_received_param, "ReqCode: $receivedReqCodeFromA\nMsg: $msgFromA")

        // 前往 Activity C (/learn/pageC)
        binding.btnLaunchC.setOnClickListener {
            val intent = Intent(this, LearnCActivity::class.java).apply {
                putExtra(LearnAActivity.EXTRA_REQUEST_CODE, REQUEST_CODE_B_TO_C)
                putExtra(LearnAActivity.EXTRA_REQUEST_MSG, "来自 Activity B 的请求 (ReqCode: $REQUEST_CODE_B_TO_C)")
            }
            binding.tvStatus.text = getString(R.string.status_waiting_c)
            launchCLauncher.launch(intent)
        }

        // 直接返回 Activity A (不经过 C，未获得 visit_c 凭证)
        binding.btnDirectReturn.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        // 携带【visit_c】凭证与【结果2】返回 Activity A
        binding.btnSendResult2.setOnClickListener {
            val resultIntent = Intent().apply {
                putExtra(LearnAActivity.EXTRA_RESULT_DATA, "结果2")
                putExtra(LearnAActivity.EXTRA_REQUEST_CODE, receivedReqCodeFromA)
                putExtra(LearnAActivity.EXTRA_VISIT, visitProofFromC)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }


    }

//    private val launcherC = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
//        if (result.resultCode == RESULT_OK) {
//            // 把 C 回传的打卡凭证，原封不动设置给 A
//            setResult(RESULT_OK, result.data)
//            finish() // 自动销毁 B，退回 A
//        }
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        originCode = intent.getIntExtra("originCode", -1)
//    }
//
//    fun openC() {
//        // 跳转 C 时，把 originCode 继续传给 C
//        val intent = ARouter.getInstance()
//            .build("/demo/pageC")
//            .withInt("originCode", originCode)
//            .getIntent(this)
//
//        launcherC.launch(intent)
//    }

    companion object {
        const val REQUEST_CODE_B_TO_C = 2002
    }
}
