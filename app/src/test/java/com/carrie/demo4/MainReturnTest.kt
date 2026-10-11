package com.carrie.demo4 // 回归结果回调与页面恢复状态不同步的故障。

import android.app.Activity // 模拟关键页面的正常返回结果。
import android.content.Intent // 传递实际结果页的访问标记。
import androidx.lifecycle.Lifecycle // 人为模拟生命周期状态还停在 STARTED。
import androidx.lifecycle.LifecycleRegistry // 检查被测 Activity 的同步边界。
import androidx.lifecycle.ViewModelProvider // 模拟首页已发起一次请求。
import org.junit.Assert.assertTrue // 检查回传真正交到频控管理器。
import org.junit.Test // JUnit4 用例入口。
import org.junit.runner.RunWith // 选择 Android 模拟运行器。
import org.robolectric.Robolectric // 完整创建真实首页 Activity。
import org.robolectric.RobolectricTestRunner // 在 JVM 上执行页面生命周期。
import org.robolectric.annotation.Config // 固定受测 SDK 版本。

/** 系统结果到达时 Lifecycle 状态可能尚未同步，不能因此丢掉有效返回。 */
@RunWith(RobolectricTestRunner::class) // 使用真实 AppCompatActivity 的回调。
@Config(sdk = [33]) // 选择支持当前资源的 Robolectric Android 环境。
class MainReturnTest { // 覆盖原实现只看 Lifecycle.State.RESUMED 的遗漏。
    @Test fun resultAfterActivityResumedIsConsumedEvenIfLifecycleStateHasNotCaughtUp() { // 返回链路只在本轮触发一次。
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get() // 完成首页首次启动和恢复。
        val app = activity.application as GuideApplication // 观察唯一管理器的最近决策。
        app.rules = com.carrie.widgetguide.GuideRules(enable = true, firstPopInterval = 1, secondPopInterval = 10) // 模拟已在开发验证面板应用开关。
        ViewModelProvider(activity)[GuideReturnState::class.java].waitingRequestCode = GuideResultContract.HOME_SEARCH // 首页确实发起了结果请求。
        (activity.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.STARTED // 模拟 AndroidX 状态事件延迟。
        val data = Intent().putExtra(GuideResultContract.EXTRA_VISIT, GuideResultContract.SEARCH_RESULTS) // 结果页确认已访问。
        val method = MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java) // 反射调用受保护的系统返回回调。
        method.isAccessible = true // 测试仍通过真实页面方法处理结果。
        method.invoke(activity, GuideResultContract.HOME_SEARCH, Activity.RESULT_OK, data) // 在恢复过的首页交付本轮结果。
        val postResume = MainActivity::class.java.getDeclaredMethod("onPostResume") // 触发系统紧接着的窗口就绪回调。
        postResume.isAccessible = true // 允许测试模拟受保护的系统回调。
        postResume.invoke(activity) // 在 Lifecycle 仍为 STARTED 时完成本轮恢复。
        assertTrue(app.lastGuideEvent, app.lastGuideEvent.contains("COMMON_TOOLS")) // 必须进入管理器而不是因状态延迟保持零次。
    } // 状态不同步回归用例结束。
} // 页面回传测试结束。
