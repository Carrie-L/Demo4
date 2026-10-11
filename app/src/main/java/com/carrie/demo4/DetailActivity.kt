package com.carrie.demo4 // Demo 用一类 Activity 承载多个真实业务流程。

import android.app.Activity // 保持扫码原有取消结果码。
import android.content.Intent // 在业务结果中追加引导访问标记。
import android.os.Bundle // 页面配置重建后的 UI 状态。
import android.view.LayoutInflater // 热搜榜每页加载 XML 布局。
import android.view.View // 不相关控件按页面类型隐藏。
import android.view.ViewGroup // ViewPager2 页面的容器类型。
import androidx.activity.viewModels // 搜索链只保留内存状态。
import androidx.appcompat.app.AppCompatActivity // ARouter 页面与系统返回宿主。
import androidx.recyclerview.widget.RecyclerView // ViewPager2 的四项 Adapter。
import com.alibaba.android.arouter.facade.annotation.Route // 注册原项目路由路径。
import com.carrie.demo4.databinding.ActivityDetailBinding // 业务页 XML 控件。
import com.carrie.demo4.databinding.ItemHotChartBinding // 每类榜单的 XML 控件。
import com.carrie.widgetguide.GuideContent // 二级列表 B 弹窗资源。
import com.carrie.widgetguide.GuideType // AI 快看的完整类型名。

/** 普通页面不记录；只有实际关键页和原入口参与结果回传。 */
@Route(path = "/browser/page") // 多个业务页沿用 Demo 的 ARouter 入口。
class DetailActivity : AppCompatActivity() { // 页面结束只能沿原栈 finish。
    private lateinit var binding: ActivityDetailBinding // 当前页面布局引用。
    private lateinit var page: DemoPage // 由 Demo 内部路由指定页面类型。
    private val state: GuideReturnState by viewModels() // 搜索中转或 AI 二级页的在途返回。
    private val app get() = application as GuideApplication // 读取进程共享管理器。
    private var pendingAiReturn = false // AI 二级列表本次回传等待恢复。
    private var resumedForGuide = false // 使用页面实际恢复回调确认二级列表可见。
    private val outgoingResult = Intent() // 与现有业务结果合并使用的 Demo 返回 Intent。
    private var selectedToolTab = "bookmarks" // 书签和历史属于同一 Activity 的左右 tab。

    override fun onCreate(savedInstanceState: Bundle?) { // 按真实页面类型绑定内容。
        super.onCreate(savedInstanceState) // 完成系统初始化。
        val decoded = runCatching { DemoPage.valueOf(intent.getStringExtra("pageKind").orEmpty()) }.getOrNull() // 校验内部路由。
        if (decoded == null) { finish(); return } // 错误目标没有引导资格。
        page = decoded // 使用校验后的实际目标。
        selectedToolTab = savedInstanceState?.getString("selectedToolTab") ?: intent.getStringExtra("selectedTab") ?: "bookmarks" // 恢复书签/历史 tab。
        binding = ActivityDetailBinding.inflate(layoutInflater) // 加载复用 XML。
        setContentView(binding.root) // 显示当前业务页。
        binding.root.applySystemInsets() // 适配系统栏。
        binding.pageTitle.text = getString(when (page) { // 将实际页名称展示出来。
            DemoPage.SEARCH_ACTIVATION -> R.string.search_active // 搜索激活页。
            DemoPage.SEARCH_RESULTS -> R.string.search_results // 搜索结果页。
            DemoPage.SCANNER -> R.string.scan_page // 扫一扫页。
            DemoPage.TOOLS -> R.string.bookmarks_history // 书签历史容器。
            DemoPage.DOWNLOADS -> R.string.downloads // 下载页。
            DemoPage.NEWS_DETAIL -> R.string.news_detail // 资讯详情页。
            DemoPage.AI_SEARCH -> R.string.ai_search_page // AI 搜页面。
            DemoPage.AI_SECONDARY -> R.string.ai_secondary_page // AI 快看二级列表。
            DemoPage.GAME -> R.string.hot_games // 游戏榜普通落点。
            DemoPage.VIDEO -> R.string.hot_videos // 视频榜普通落点。
            DemoPage.OTHER -> R.string.other_page // 任意后续业务页。
        }) // 页面名称绑定结束。
        binding.activationPanel.visibility = if (page == DemoPage.SEARCH_ACTIVATION) View.VISIBLE else View.GONE // 搜索框和历史仅在激活页。
        binding.toolTabs.visibility = if (page == DemoPage.TOOLS) View.VISIBLE else View.GONE // 书签和历史共享页面。
        binding.aiSecondary.visibility = if (page == DemoPage.AI_SEARCH) View.VISIBLE else View.GONE // AI 搜进入二级列表。
        binding.aiArticle.visibility = if (page == DemoPage.AI_SECONDARY) View.VISIBLE else View.GONE // 二级列表打开资讯。
        binding.toResults.visibility = if (page == DemoPage.SEARCH_ACTIVATION) View.VISIBLE else View.GONE // 激活页搜索按钮。
        binding.toOther.visibility = if (page == DemoPage.SEARCH_RESULTS || page == DemoPage.NEWS_DETAIL || page == DemoPage.TOOLS || page == DemoPage.DOWNLOADS || page == DemoPage.SCANNER || page == DemoPage.OTHER) View.VISIBLE else View.GONE // 深层普通页。
        binding.toResults.setOnClickListener { searchFromActivation() } // 激活页搜索按钮到结果页。
        binding.searchSubmit.setOnClickListener { searchFromActivation() } // 顶部搜索按钮走同一逻辑。
        binding.searchHistory.setOnClickListener { searchFromActivation() } // 搜索历史跳结果页。
        binding.guessSearch.setOnClickListener { searchFromActivation() } // 猜你想搜跳结果页。
        binding.hotPager.adapter = HotChartAdapter { index -> // 横向四榜使用 ViewPager2。
            if (index == 1 || index == 2) searchFromActivation() // 电影电视与综艺进入搜索结果。
            else DemoNavigation.page(this, if (index == 0) DemoPage.GAME else DemoPage.VIDEO) // 游戏与视频不是搜索结果。
        } // 榜单点击绑定结束。
        binding.bookmarksTab.setOnClickListener { selectedToolTab = "bookmarks"; renderToolTab() } // 左书签不新建 Activity。
        binding.historyTab.setOnClickListener { selectedToolTab = "history"; renderToolTab() } // 右历史不新建 Activity。
        renderToolTab() // 恢复工具 tab UI。
        binding.aiSecondary.setOnClickListener { DemoNavigation.page(this, DemoPage.AI_SECONDARY) } // AI 搜入口不接收 B 回传。
        binding.aiArticle.setOnClickListener { openAiDetail() } // B 只在二级页收到资讯结果时检查。
        binding.toOther.setOnClickListener { DemoNavigation.page(this, DemoPage.OTHER) } // 任意中间页不写引导标记。
        binding.back.setOnClickListener { finish() } // 原栈返回，不重开首页。
        if (page == DemoPage.SEARCH_ACTIVATION && state.hasVisitedSearchResults) prepareSearchResult() // 旋转时保留本轮中转。
    } // 业务页创建结束。

    private fun renderToolTab() { // 一个容器内切换左右 tab。
        binding.bookmarksTab.isSelected = selectedToolTab == "bookmarks" // 标记左侧书签。
        binding.historyTab.isSelected = selectedToolTab == "history" // 标记右侧历史。
        binding.toolTabContent.text = getString(if (selectedToolTab == "bookmarks") R.string.bookmarks else R.string.history) // 显示选中列表。
    } // 工具 tab 渲染结束。

    private fun searchFromActivation() { // 只有激活页真正进入结果才具备回传条件。
        if (page != DemoPage.SEARCH_ACTIVATION || state.waitingRequestCode != null) return // 防止重复发起。
        state.waitingRequestCode = GuideResultContract.ACTIVATION_SEARCH // 激活页等待实际结果访问标记。
        app.recordGuideEvent("搜索激活页打开搜索结果，等待原栈返回") // 区分中转链路从未返回的情况。
        DemoNavigation.page(this, DemoPage.SEARCH_RESULTS, GuideResultContract.ACTIVATION_SEARCH) { clearWaiting(GuideResultContract.ACTIVATION_SEARCH) } // ARouter 保留业务调用关系。
    } // 搜索结果页打开结束。

    private fun openAiDetail() { // AI 二级页直接持有 B 的触发返回。
        if (page != DemoPage.AI_SECONDARY || state.waitingRequestCode != null) return // 只允许一轮资讯在途。
        state.waitingRequestCode = GuideResultContract.SECONDARY_AI_DETAIL // 二级列表等待资讯结果。
        app.recordGuideEvent("AI 快看二级页打开资讯详情，等待原栈返回") // 诊断二级页结果链路。
        DemoNavigation.page(this, DemoPage.NEWS_DETAIL, GuideResultContract.SECONDARY_AI_DETAIL) { clearWaiting(GuideResultContract.SECONDARY_AI_DETAIL) } // 原栈返回到二级列表。
    } // AI 二级资讯打开结束。

    private fun clearWaiting(code: Int) { // 路由失败不能卡住下一次点击。
        runOnUiThread { if (state.waitingRequestCode == code) state.waitingRequestCode = null } // 只清对应在途请求。
    } // 失败清理结束。

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { // 由 Android 按原调用关系送回。
        super.onActivityResult(requestCode, resultCode, data) // 保留其它业务处理。
        val marker = data?.getStringExtra(GuideResultContract.EXTRA_VISIT) // 保存实际页面传回的访问标记。
        app.recordGuideEvent("业务页 ${page.name} 收到返回：requestCode=$requestCode，resultCode=$resultCode，等待=${state.waitingRequestCode}，标记=$marker") // 便于检查搜索中转或 AI 详情。
        if (state.waitingRequestCode != requestCode) { app.recordGuideEvent("业务页忽略返回：requestCode 与当前等待不一致"); return } // 忽略不是此页面发起的返回。
        state.waitingRequestCode = null // 先消费一次，防重复回调。
        if (marker != GuideResultContract.expectedVisit(requestCode)) { app.recordGuideEvent("业务页不传递：关键页访问标记不匹配"); return } // 必须有实际关键页标记。
        when (requestCode) { // 不同入口处理不同展示位置。
            GuideResultContract.ACTIVATION_SEARCH -> { state.hasVisitedSearchResults = true; prepareSearchResult() } // 先转交给激活页。
            GuideResultContract.SECONDARY_AI_DETAIL -> { pendingAiReturn = true; consumeAiIfReady() } // 返回二级页时才检查 B。
        } // 返回协议分发结束。
    } // 页面回传处理结束。

    override fun onResume() { // 接收在本次恢复前送达的子页面结果。
        super.onResume() // 维持 AndroidX 生命周期正常顺序。
        resumedForGuide = true // 此时真实页面已进入恢复回调。
        // 已收到的结果在 onPostResume 处理，确保弹窗窗口已就绪。
    } // 二级页恢复结束。

    private fun prepareSearchResult() { // 激活页只传递访问过结果这一事实。
        outgoingResult.putExtra(GuideResultContract.EXTRA_VISIT, GuideResultContract.SEARCH_RESULTS) // 不把游戏或视频当结果。
        setResult(Activity.RESULT_OK, outgoingResult) // 此时不 finish，等用户正常返回首页。
    } // 搜索中转结束。

    override fun onPostResume() { // 确认关键页面真的显示过。
        super.onPostResume() // 先完成系统恢复。
        resumedForGuide = true // 覆盖先 postResume 后结果的系统回调顺序。
        val marker = when (page) { // 只有关键页写结果标记。
            DemoPage.SEARCH_RESULTS -> GuideResultContract.SEARCH_RESULTS // 搜索结果已显示。
            DemoPage.TOOLS, DemoPage.DOWNLOADS -> GuideResultContract.TOOLS // 我的工具页已显示。
            DemoPage.SCANNER -> GuideResultContract.SCAN // 扫一扫页已显示，不要求扫码成功。
            DemoPage.NEWS_DETAIL -> GuideResultContract.AI_DETAIL // 资讯页已显示，入口仍须校验请求码。
            else -> null // 激活页、游戏、视频和普通页面不写标记。
        } // 关键页类型判断结束。
        if (marker != null) { // 真正进入关键页后准备返回数据。
            outgoingResult.putExtra(GuideResultContract.EXTRA_VISIT, marker) // 只追加引导字段。
            setResult(if (page == DemoPage.SCANNER) Activity.RESULT_CANCELED else Activity.RESULT_OK, outgoingResult) // 扫码取消也保留访问事实。
        } // 返回数据准备结束。
        consumeAiIfReady() // 只有二级列表有待处理资讯结果时才检查。
    } // 页面恢复结束。

    private fun consumeAiIfReady() { // 二级列表当轮只触发一次 B。
        if (!pendingAiReturn || page != DemoPage.AI_SECONDARY || !resumedForGuide) return // 不满足入口位置就等待紧邻恢复。
        pendingAiReturn = false // 先清标记，冷却未到不能补弹。
        app.recordGuideEvent("AI 快看二级页返回有效：开始检查配置和本地频次") // 诊断页面记录到达管理器的节点。
        app.guideManager.tryShow(this, GuideType.AI_QUICK_NEWS, aiContent(), { page == DemoPage.AI_SECONDARY && resumedForGuide && !isFinishing && !isDestroyed }, DemoNavigation::openAdd) // 弹在二级页底部。
    } // B 的二级入口消费结束。

    private fun aiContent(): GuideContent = GuideContent( // 复用与首页 AI 一致的组件内容。
        getString(R.string.ai_title), // B 引导标题。
        getString(R.string.ai_description), // B 功能介绍。
        R.drawable.preview_news, // AI 组件截图占位图。
        getString(R.string.preview_description), // 图片无障碍文字。
        "demo4://guide/widget/add?type=ai&target=desktop", // 左边桌面链接。
        "demo4://guide/widget/add?type=ai&target=assistant" // 右边负一屏链接。
    ) // 二级页 B 内容结束。

    override fun onStop() { // 离开二级页立即丢弃本轮未消费结果。
        pendingAiReturn = false // 后台恢复不能突然弹窗。
        app.guideManager.dismissForOwner(this) // 本页 Dialog 不在恢复后重建。
        super.onStop() // 完成系统生命周期回调。
    } // 页面离开结束。

    override fun onPause() { // 业务页不可见时拒绝异步结束后的窗口展示。
        resumedForGuide = false // 不在离开页面后补弹。
        super.onPause() // 保留系统暂停回调。
    } // 页面暂停结束。

    override fun onSaveInstanceState(outState: Bundle) { // 只保存工具 tab UI。
        outState.putString("selectedToolTab", selectedToolTab) // 左右 tab 在旋转后保持原样。
        super.onSaveInstanceState(outState) // 不持久化待弹机会。
    } // 页面 UI 状态保存结束。

    private inner class HotChartAdapter(private val onChartClick: (Int) -> Unit) : RecyclerView.Adapter<HotChartAdapter.ChartHolder>() { // ViewPager2 横向四榜。
        inner class ChartHolder(val item: ItemHotChartBinding) : RecyclerView.ViewHolder(item.root) // 持有单页 XML。
        override fun getItemCount(): Int = 4 // 游戏、电影电视、综艺、视频。
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChartHolder { // 从 XML 加载榜单页。
            val item = ItemHotChartBinding.inflate(LayoutInflater.from(parent.context), parent, false) // 创建横向一页。
            return ChartHolder(item) // 返回可复用 ViewHolder。
        } // 创建一页结束。
        override fun onBindViewHolder(holder: ChartHolder, position: Int) { // 显示位置对应的榜单。
            val title = intArrayOf(R.string.hot_games, R.string.hot_films, R.string.hot_variety, R.string.hot_videos)[position] // 四榜顺序。
            holder.item.chartTitle.setText(title) // 标题。
            holder.item.chartItem.setText(R.string.hot_chart_item) // 演示列表项。
            holder.item.chartItem.setOnClickListener { if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) onChartClick(holder.bindingAdapterPosition) } // 忽略回收期无效位置。
        } // 榜单页绑定结束。
    } // 横向榜单适配结束。
} // 业务页结束。
