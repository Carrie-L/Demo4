package com.carrie.demo4

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alibaba.android.arouter.launcher.ARouter
import com.carrie.demo4.databinding.ActivityMainBinding
import com.carrie.widgetguide.core.*
import com.carrie.widgetguide.ui.*
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val app get() = application as GuideApplication
    private lateinit var guideHost: FragmentGuideHost
    private var rootId = ""
    private var mine = false
    private var channel = 0
    private var resumed = false
    private var version = 0L
    private val currentPage get() = PageRef(rootId + if (mine) ":mine" else ":home:$channel", when {
        mine -> PageKind.MINE
        channel == 1 -> PageKind.HOME_AI
        else -> PageKind.HOME_OTHER
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootId = savedInstanceState?.getString("rootId") ?: UUID.randomUUID().toString()
        mine = savedInstanceState?.getBoolean("mine") ?: false
        channel = savedInstanceState?.getInt("channel") ?: 0
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemInsets()
        guideHost = FragmentGuideHost(this, { HostSnapshot(rootId, version, when {
            mine -> Surface.MINE
            channel == 1 -> Surface.HOME_AI
            else -> Surface.HOME_OTHER
        }, resumed && !isFinishing && !supportFragmentManager.isStateSaved) }, GuideContentProvider { type ->
            val tools = type == GuideType.TOOLS
            GuideContent(getString(if (tools) R.string.tools_title else R.string.ai_title),
                getString(if (tools) R.string.tools_description else R.string.ai_description),
                if (tools) R.drawable.preview_tools else R.drawable.preview_news,
                getString(R.string.preview_description),
                "demo4://guide/widget/add?type=${if (tools) "tools" else "ai"}&target=desktop",
                "demo4://guide/widget/add?type=${if (tools) "tools" else "ai"}&target=assistant")
        }, DemoNavigation.navigator(this))
        binding.homeTab.setOnClickListener { mine = false; renderLocation() }
        binding.mineTab.setOnClickListener { mine = true; renderLocation() }
        listOf(binding.recommendChannel, binding.aiChannel, binding.sportsChannel, binding.localChannel)
            .forEachIndexed { index, button -> button.setOnClickListener { channel = index; renderLocation() } }
        binding.searchBox.setOnClickListener { open(PageKind.SEARCH_ACTIVE, EntryPoint.HOME_SEARCH_BOX) }
        binding.directSearch.setOnClickListener { open(PageKind.SEARCH_RESULTS, EntryPoint.HOME_SEARCH_BUTTON) }
        binding.newsCard.setOnClickListener { open(PageKind.NEWS_DETAIL,
            if (channel == 1) EntryPoint.AI_ARTICLE else EntryPoint.OTHER) }
        binding.bookmarks.setOnClickListener { open(PageKind.BOOKMARKS, EntryPoint.MINE_TOOL) }
        binding.history.setOnClickListener { open(PageKind.HISTORY, EntryPoint.MINE_TOOL) }
        binding.downloads.setOnClickListener { open(PageKind.DOWNLOADS, EntryPoint.MINE_TOOL) }
        binding.settings.setOnClickListener { ARouter.getInstance().build("/settings/main").navigation(this) }
        binding.debugPanel.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        binding.guideStatus.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        binding.debugPanel.setOnClickListener { ARouter.getInstance().build("/debug/guide").navigation(this) }
        renderLocation()
    }

    private fun open(kind: PageKind, entry: EntryPoint) = DemoNavigation.page(this, currentPage, kind, entry)

    private fun renderLocation() {
        version++
        binding.homeContent.visibility = if (mine) View.GONE else View.VISIBLE
        binding.mineContent.visibility = if (mine) View.VISIBLE else View.GONE
        binding.channelName.text = resources.getStringArray(R.array.channels)[channel]
        binding.homeTab.isSelected = !mine; binding.mineTab.isSelected = mine
        listOf(binding.recommendChannel, binding.aiChannel, binding.sportsChannel, binding.localChannel)
            .forEachIndexed { index, button -> button.isSelected = channel == index }
        binding.searchBox.visibility = if (channel == 0) View.VISIBLE else View.GONE
        binding.directSearch.visibility = if (channel == 0) View.VISIBLE else View.GONE
        binding.newsCard.text = getString(if (channel == 1) R.string.ai_article else R.string.regular_article)
        if (resumed) { app.controller.visible(currentPage); app.controller.refresh() }
    }

    override fun onPostResume() {
        super.onPostResume()
        resumed = true; version++
        app.controller.attach(guideHost)
        app.controller.visible(currentPage)
        app.controller.refresh()
        // Controller can be replaced by the debug-only reset while this Activity is stopped.
        statusJob?.cancel()
        statusJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                app.controller.status.collect { status ->
                    val state = status.state
                    binding.guideStatus.text = getString(R.string.guide_status,
                        state?.records?.count { it.type == GuideType.TOOLS } ?: 0,
                        state?.records?.count { it.type == GuideType.AI_NEWS } ?: 0,
                        state?.behaviors?.joinToString().orEmpty(), status.message)
                }
            }
        }
    }
    private var statusJob: kotlinx.coroutines.Job? = null

    override fun onPause() { resumed = false; version++; app.controller.refresh(); super.onPause() }
    override fun onDestroy() { app.controller.detach(guideHost); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("rootId", rootId); outState.putBoolean("mine", mine); outState.putInt("channel", channel)
        super.onSaveInstanceState(outState)
    }
}
