package com.carrie.demo4

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.carrie.demo4.databinding.ActivityLearnABinding

/**
 * Activity A: 链式跳转发起方 / 终点确认宿主。
 * 对应 ARouter 路由路径：/learn/pageA
 * 支持 registerForActivityResult + visit_c 关键页打卡凭证校验。
 */
@Route(path = "/learn/pageA")
class LearnAActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLearnABinding

    /* =================================================================================
     * 新实现：registerForActivityResult + 关键页 (visit_c) 打卡凭证校验
     * ================================================================================= */
    private val launchPathLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val visitProof = result.data?.getStringExtra(EXTRA_VISIT)

            if ((result.resultCode == RESULT_OK) && (visitProof == VISIT_C)) {
                // 确认链路中成功经过了关键页 C（获取到 visit_c 凭证），弹出对话框！
                binding.tvStatus.text = getString(R.string.status_visit_c_success)
                showVisitCDialog()
            } else {
                // 未经过关键页 C，或中途直接返回，不弹窗！
                binding.tvStatus.text = getString(R.string.status_visit_c_failed, result.resultCode)
            }
        }

    /* =================================================================================
     * 保留原基础实现（仅供对比学习）：
     *
     * private val launchBLauncherOriginal =
     *     registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
     *         if (result.resultCode == RESULT_OK) {
     *             val returnedData = result.data?.getStringExtra(EXTRA_RESULT_DATA)
     *             val returnedReqCode = result.data?.getIntExtra(EXTRA_REQUEST_CODE, -1) ?: -1
     *             binding.tvStatus.text = getString(R.string.status_success_received, returnedReqCode, returnedData ?: "")
     *             if (returnedData == "结果2") { showResultDialog() }
     *         } else {
     *             binding.tvStatus.text = getString(R.string.status_no_result, result.resultCode)
     *         }
     *     }
     * ================================================================================= */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLearnABinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemInsets()

        // 路径1：A -> B -> C (目标为 /learn/pageB)
        binding.btnLaunchPath1.setOnClickListener {
            val intent = Intent(this, LearnBActivity::class.java).apply {
                putExtra(EXTRA_REQUEST_CODE, REQUEST_CODE_A_TO_B)
                putExtra(EXTRA_REQUEST_MSG, "来自 Activity A 的请求 (路径1)")
            }
            binding.tvStatus.text = getString(R.string.status_waiting_return)
            launchPathLauncher.launch(intent)
        }

        // 路径2：A -> C (直达关键页 /learn/pageC)
        binding.btnLaunchPath2.setOnClickListener {
            val intent = Intent(this, LearnCActivity::class.java).apply {
                putExtra(EXTRA_REQUEST_CODE, REQUEST_CODE_A_TO_C)
                putExtra(EXTRA_REQUEST_MSG, "来自 Activity A 的请求 (路径2直达 C)")
            }
            binding.tvStatus.text = getString(R.string.status_waiting_return)
            launchPathLauncher.launch(intent)
        }
    }

    private fun showVisitCDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_result_received)
            .setMessage(getString(R.string.dialog_msg_visit_c))
            .setPositiveButton(R.string.dialog_ok) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    // 注册回调
//    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
//        if (result.resultCode == RESULT_OK) {
//            val visitProof = result.data?.getStringExtra("EXTRA_VISIT")
//            // 关键校验：只有拿到了 C 盖章的 "visit_c" 凭证，才允许弹窗！
//            if (visitProof == "visit_c") {
//                showDialog("确认从 A 出发并成功访问了 C，弹出引导窗！")
//            }
//        }
//    }
//
//    fun openB() {
//        // 使用 ARouter 生成 Intent，交给 registerForActivityResult 启动
//        val intent = ARouter.getInstance()
//            .build("/demo/pageB")
//            .withInt("originCode", 6101) // 告诉 B，这次是从 A 发起的
//            .getIntent(this)
//
//        launcher.launch(intent)
//    }

    companion object {
        const val REQUEST_CODE_A_TO_B = 1001
        const val REQUEST_CODE_A_TO_C = 1002
        const val EXTRA_REQUEST_CODE = "extra_request_code"
        const val EXTRA_REQUEST_MSG = "extra_request_msg"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_VISIT = "extra_visit"
        const val VISIT_C = "visit_c"
    }
}
