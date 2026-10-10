package com.carrie.demo4

import android.app.Application
import android.util.Log
import com.alibaba.android.arouter.launcher.ARouter
import com.carrie.widgetguide.core.*
import com.carrie.widgetguide.runtime.*
import com.carrie.widgetguide.storage.*
import kotlinx.coroutines.flow.MutableStateFlow

class DemoConfigSource : GuideConfigSource {
    override val config = MutableStateFlow(GuideConfig())
}

class GuideApplication : Application() {
    val configSource = DemoConfigSource()
    private var offsetMillis = 0L
    val clock = GuideClock { SystemGuideClock.now().let { it.copy(millis = it.millis + offsetMillis) } }
    lateinit var repository: GuideRepository
        private set
    lateinit var controller: WidgetGuideController
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) { ARouter.openLog(); ARouter.openDebug() }
        ARouter.init(this)
        repository = PersistentGuideRepository(SharedPreferencesStateStore(this))
        createController()
    }

    private fun createController() {
        controller = WidgetGuideController(repository, configSource, clock,
            reportError = { Log.e("WidgetGuide", "Automatic guide paused", it) })
    }

    fun advanceDayForDebug() {
        check(BuildConfig.DEBUG)
        offsetMillis += GuidePolicy.DAY_MILLIS
        controller.refresh()
    }

    suspend fun resetForDebug() {
        check(BuildConfig.DEBUG)
        controller.close()
        repository.update { GuideState() }
        offsetMillis = 0
        createController()
    }
}
