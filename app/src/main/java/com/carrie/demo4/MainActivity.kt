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

/**
 * 验证主页面：底部首页/我的共用 Activity，首页另有四个频道。
 * 负责将真实可见位置映射到 PageRef 和 HostSnapshot，不在页面内实现业务频控。
 */
class MainActivity : AppCompatActivity() {
    /**
     * 主页面 XML 的类型安全绑定，仅由当前 Activity 持有。
     */
    private lateinit var binding: ActivityMainBinding
    /**
     * 访问进程级依赖；使用 getter 保证调试重置后拿到新控制器。
     */
    private val app get() = application as GuideApplication
    /**
     * 当前 Activity 的展示适配器，销毁时从控制器解除连接。
     */
    private lateinit var guideHost: FragmentGuideHost
    /**
     * 逻辑主页面实例 ID，配置重建时从 Bundle 恢复。
     */
    private var rootId = ""
    /**
     * 底部位置：true 为我的，false 为首页。
     */
    private var mine = false
    /**
     * 首页频道下标：0 推荐（默认）、1 AI 快看、2 体育、3 地区。
     */
    private var channel = 0
    /**
     * 已完成 onPostResume 且尚未 onPause，作为能否展示的条件之一。
     */
    private var resumed = false
    /**
     * 每次位置/前后台有效性更新后递增，防止 IO 期间离开又回来的旧请求通过。
     */
    private var version = 0L
    /**
     * 将同一 Activity 内的 Tab/频道编码为不同逻辑页面实例。
     * 例如 root:home:0 和 root:home:1；不能只用 rootId 代表所有频道。
     */
    private val currentPage get() = PageRef(rootId + if (mine) ":mine" else ":home:$channel", when {
        mine -> PageKind.MINE
        channel == 1 -> PageKind.HOME_AI
        else -> PageKind.HOME_OTHER
    })

    /**
     * 恢复页面身份和选中位置，组装宿主与内容提供者，绑定业务入口。
     * 页面点击只发起路由；行为由目标页面实际进入后上报。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootId = savedInstanceState?.getString("rootId") ?: UUID.randomUUID().toString()
        mine = savedInstanceState?.getBoolean("mine") ?: false
        channel = savedInstanceState?.getInt("channel") ?: 0
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemInsets()
        // snapshot lambda 每次读取最新位置；GuideContentProvider 仅负责对应 A/B 的内容。
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
        // 首页/我的与首页频道都必须经过 renderLocation 更新业务位置。
        binding.homeTab.setOnClickListener { mine = false; renderLocation() }
        binding.mineTab.setOnClickListener { mine = true; renderLocation() }
        listOf(binding.recommendChannel, binding.aiChannel, binding.sportsChannel, binding.localChannel)
            .forEachIndexed { index, button -> button.setOnClickListener { channel = index; renderLocation() } }
        // 入口类型在可信点击处理处指定，目标页不能仅凭自身 PageKind 推断行为。
        binding.searchBox.setOnClickListener { open(PageKind.SEARCH_ACTIVE, EntryPoint.HOME_SEARCH_BOX) }
        binding.directSearch.setOnClickListener { open(PageKind.SEARCH_RESULTS, EntryPoint.HOME_SEARCH_BUTTON) }
        binding.newsCard.setOnClickListener { open(PageKind.NEWS_DETAIL,
            if (channel == 1) EntryPoint.AI_ARTICLE else EntryPoint.OTHER) }
        binding.bookmarks.setOnClickListener { open(PageKind.BOOKMARKS, EntryPoint.MINE_TOOL) }
        binding.history.setOnClickListener { open(PageKind.HISTORY, EntryPoint.MINE_TOOL) }
        binding.downloads.setOnClickListener { open(PageKind.DOWNLOADS, EntryPoint.MINE_TOOL) }
        binding.settings.setOnClickListener { ARouter.getInstance().build("/settings/main").navigation(this) }
        // 调试入口和状态文本仅 Debug 可见；DebugActivity 本身还有 Release 防护。
        binding.debugPanel.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        binding.guideStatus.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        binding.debugPanel.setOnClickListener { ARouter.getInstance().build("/debug/guide").navigation(this) }
        renderLocation()
    }

    /**
     * 使用当前逻辑页面作为真实来源，不根据将要跳到哪里反推来源。
     */
    private fun open(kind: PageKind, entry: EntryPoint) = DemoNavigation.page(this, currentPage, kind, entry)

    /**
     * 刷新 Tab/频道 UI 并递增位置版本；仅页面已恢复可见时报告可见页。
     * 首页搜索控件只在推荐频道展示，但已完成搜索行为可在首页其他频道用于 A。
     */
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

    /**
     * 页面恢复后连接宿主、上报实际位置、请求资格重算，并订阅诊断状态。
     * FragmentManager 已可提交后再提供展示能力，避免在保存状态阶段显示窗口。
     */
    override fun onPostResume() {
        super.onPostResume()
        resumed = true; version++
        app.controller.attach(guideHost)
        app.controller.visible(currentPage)
        app.controller.refresh()
        // 本页停止期间，调试重置可能已经替换 Controller，因此重新建立订阅。
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
    /**
     * 诊断流收集任务；每次恢复重建订阅，以兼容调试面板替换控制器。
     */
    private var statusJob: kotlinx.coroutines.Job? = null

    /**
     * 立即使快照失效并触发取消/重算，底部 Tab 仍选中不意味着页面可展示。
     */
    override fun onPause() { resumed = false; version++; app.controller.refresh(); super.onPause() }
    /**
     * 解除当前宿主连接，避免进程级控制器继续持有已销毁 Activity。
     */
    override fun onDestroy() { app.controller.detach(guideHost); super.onDestroy() }
    /**
     * 仅保存 UI 身份与选中位置；行为、次数由 SP 管理，未完成搜索链路不随 Bundle 恢复。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("rootId", rootId); outState.putBoolean("mine", mine); outState.putInt("channel", channel)
        super.onSaveInstanceState(outState)
    }
}
