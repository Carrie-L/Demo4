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
class PersistenceTest {
    @Test fun `SP survives repository recreation with the entire state`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-${System.nanoTime()}"
        val expected = GuideState(setOf(Behavior.HOME_SEARCH), listOf(ShowRecord("id", GuideType.TOOLS, 1234, 0)))
        PersistentGuideRepository(SharedPreferencesStateStore(context, name), Dispatchers.Unconfined).update { expected }
        assertEquals(expected, PersistentGuideRepository(SharedPreferencesStateStore(context, name), Dispatchers.Unconfined).read())
    }
    @Test fun `pending reservation round trips without becoming a display`() {
        val state = GuideState(pending = PendingShow("p", GuideType.AI_NEWS))
        assertEquals(state, GuideStateCodec.decode(GuideStateCodec.encode(state)))
    }
    @Test fun `corrupt storage does not become empty state`() = runTest {
        val store = object : StringStateStore {
            override fun read() = "invalid JSON"
            override fun write(value: String) = true
        }
        val result = runCatching { PersistentGuideRepository(store, Dispatchers.Unconfined).read() }
        assertTrue(result.isFailure)
    }
    @Test fun `failed write is reported even when backend RAM contains new value`() = runTest {
        val store = object : StringStateStore {
            var raw: String? = null
            override fun read() = raw
            override fun write(value: String): Boolean { raw = value; return false }
        }
        val repo = PersistentGuideRepository(store, Dispatchers.Unconfined)
        val first = runCatching { repo.update { it.copy(behaviors = setOf(Behavior.MINE_TOOL)) } }
        assertTrue(first.exceptionOrNull() is IOException)
        // Retrying equal state must still attempt a durable write rather than trusting the RAM copy.
        val second = runCatching { repo.update { it } }
        assertTrue(second.exceptionOrNull() is IOException)
    }
    @Test fun `unknown schema is rejected`() {
        assertTrue(runCatching { GuideStateCodec.decode("""{"version":2,"behaviors":[],"records":[],"pending":null}""") }.isFailure)
    }
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
