package com.carrie.widgetguide.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.carrie.widgetguide.core.*
import com.carrie.widgetguide.runtime.GuideConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
/** 在 Robolectric API 29 上验证真实 SP、严格 JSON 协议和配置解析。 */
class PersistenceTest {
    /** 重建 SP 仓库仍能读回行为和记录，验证完整状态持久化。 */
    @Test fun `SP survives repository recreation with the entire state`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-${System.nanoTime()}"
        val expected = GuideState(setOf(Behavior.HOME_SEARCH), listOf(ShowRecord("id", GuideType.TOOLS, 1234, 0)))
        PersistentGuideRepository(SharedPreferencesStateStore(context, name), Dispatchers.Unconfined).update { expected }
        assertEquals(expected, PersistentGuideRepository(SharedPreferencesStateStore(context, name), Dispatchers.Unconfined).read())
    }
    /** pending 编解码保持预约身份，不能转换成展示记录。 */
    @Test fun `pending reservation round trips without becoming a display`() {
        val state = GuideState(pending = PendingShow("p", GuideType.AI_NEWS))
        assertEquals(state, GuideStateCodec.decode(GuideStateCodec.encode(state)))
    }
    /** 损坏 JSON 必须抛出错误，不能返回空状态重置频控。 */
    @Test fun `corrupt storage does not become empty state`() = runTest {
        val store = object : StringStateStore {
            override fun read() = "invalid JSON"
            override fun write(value: String) = true
        }
        val result = runCatching { PersistentGuideRepository(store, Dispatchers.Unconfined).read() }
        assertTrue(result.isFailure)
    }
    /** 模拟内存已更新但落盘失败；首次及同值重试都必须报告失败。 */
    @Test fun `failed write is reported even when backend RAM contains new value`() = runTest {
        val store = object : StringStateStore {
            var raw: String? = null
            override fun read() = raw
            override fun write(value: String): Boolean { raw = value; return false }
        }
        val repo = PersistentGuideRepository(store, Dispatchers.Unconfined)
        val first = runCatching { repo.update { it.copy(behaviors = setOf(Behavior.MINE_TOOL)) } }
        assertTrue(first.exceptionOrNull() is IOException)
        // 同值重试也必须再次写盘，不能相信此前失败后已被更新的内存副本。
        val second = runCatching { repo.update { it } }
        assertTrue(second.exceptionOrNull() is IOException)
    }
    /** 未知版本必须拒绝，防止新版数据被旧版当成初始状态。 */
    @Test fun `unknown schema is rejected`() {
        assertTrue(runCatching { GuideStateCodec.decode("""{"version":2,"behaviors":[],"records":[],"pending":null}""") }.isFailure)
    }
    /** 缺失、类型错误、负数、溢出云配均关闭，合法配置正常解析。 */
    @Test fun `cloud parse rejects missing wrong type negative and overflow values`() {
        for (raw in listOf(null, "{}", "bad",
            """{"browserWidgetGuideRules":{"enablePop":"true","firstPopInterval":1,"secondPopInterval":3}}""",
            """{"browserWidgetGuideRules":{"enablePop":true,"firstPopInterval":-1,"secondPopInterval":3}}""",
            """{"browserWidgetGuideRules":{"enablePop":true,"firstPopInterval":999999999999,"secondPopInterval":3}}""")) {
            assertFalse(GuideConfigParser.parse(raw).enablePop)
        }
        assertEquals(GuideConfig(true, 1, 3), GuideConfigParser.parse(
            """{"browserWidgetGuideRules":{"enablePop":true,"firstPopInterval":1,"secondPopInterval":3}}"""))
    }
}
