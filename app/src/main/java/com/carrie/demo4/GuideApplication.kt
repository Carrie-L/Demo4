package com.carrie.demo4

import android.app.Application
import android.util.Log
import com.alibaba.android.arouter.launcher.ARouter
import com.carrie.widgetguide.core.*
import com.carrie.widgetguide.runtime.*
import com.carrie.widgetguide.storage.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 验证工程的内存云配源。初始关闭，调试面板可修改；重启恢复默认值。
 * 正式环境用现有云配 SDK 的适配器替换，保持 StateFlow 契约。
 */
class DemoConfigSource : GuideConfigSource {
    /**
     * 当前配置快照；不会和行为/次数一起写入引导 SP。
     */
    override val config = MutableStateFlow(GuideConfig())
}

/**
 * 验证宿主的依赖组装入口，进程内共用一个仓库和控制器。
 */
class GuideApplication : Application() {
    /**
     * 进程共享的调试云配。
     */
    val configSource = DemoConfigSource()
    /**
     * 仅当前进程有效的测试时间偏移，不改变系统时钟，也不持久化。
     */
    private var offsetMillis = 0L
    /**
     * 系统时间加调试偏移；时区仍跟随系统，正式迁移直接用 SystemGuideClock。
     */
    val clock = GuideClock { SystemGuideClock.now().let { it.copy(millis = it.millis + offsetMillis) } }
    /**
     * 唯一状态仓库，持有 applicationContext 对应的 SP。
     */
    lateinit var repository: GuideRepository
        private set
    /**
     * 唯一事件控制器；调试重置会关闭旧实例后替换。
     */
    lateinit var controller: WidgetGuideController
        private set

    /**
     * 初始化 ARouter、SP 仓库及控制器；仅 Debug 开启路由日志。
     */
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) { ARouter.openLog(); ARouter.openDebug() }
        ARouter.init(this)
        repository = PersistentGuideRepository(SharedPreferencesStateStore(this))
        createController()
    }

    /**
     * 组装依赖并把不可恢复的存储/展示异常接入日志。
     */
    private fun createController() {
        controller = WidgetGuideController(repository, configSource, clock,
            reportError = { Log.e("WidgetGuide", "Automatic guide paused", it) })
    }

    /**
     * 测试时钟前进固定 24h 后重新评估；Release 调用会被 check 拒绝。
     */
    fun advanceDayForDebug() {
        check(BuildConfig.DEBUG)
        offsetMillis += GuidePolicy.DAY_MILLIS
        controller.refresh()
    }

    /**
     * 仅 Debug 显式清空测试状态；先关闭旧事件循环，防止旧请求写回已重置的数据。
     * 此操作清除频控和行为，正式产品没有自动重置入口。
     */
    suspend fun resetForDebug() {
        check(BuildConfig.DEBUG)
        controller.close()
        repository.update { GuideState() }
        offsetMillis = 0
        createController()
    }
}
