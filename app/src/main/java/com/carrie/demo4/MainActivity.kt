package com.carrie.demo4 // 首页和我的页由同一个 Activity 承载。

import android.content.Intent // 读取页面返回的访问标记。
import android.os.Bundle // 保存两个 tab 的 UI 位置。
import android.view.View // 显示和隐藏对应内容。
import androidx.activity.viewModels // 普通 ViewModel 保留旋转时的在途请求。
import androidx.appcompat.app.AppCompatActivity // 页面宿主和返回回调。
import androidx.lifecycle.lifecycleScope // Debug 诊断读盘使用页面协程。
import kotlinx.coroutines.launch // 读取完成后显示调试次数。
import com.carrie.demo4.databinding.ActivityMainBinding // 首页 XML 绑定。
import com.carrie.widgetguide.GuideContent // 两类弹窗的截图与文案。
import com.carrie.widgetguide.GuideType // 类型名描述具体产品组件。

/** 只有从自身发起并匹配访问标记的返回，才产生一次弹窗检查。 */
class MainActivity : AppCompatActivity() { // 冷启没有 pendingReturn，不能弹窗。
    private lateinit var binding: ActivityMainBinding // 当前页面的 ViewBinding。
    private val app get() = application as GuideApplication // 读取唯一管理器及 Debug 配置。
    private val guideReturn: GuideReturnState by viewModels() // 不将行为资格保存进 SP。
    private var mine = false // 底部位置，false 为首页。
    private var channel = 0 // 顶部频道：0 推荐、1 AI 快看、2 体育、3 地区。
    private var pendingReturn: Int? = null // 仅在本次回调与页面恢复之间交接。
    private var resumedForGuide = false // onPostResume 标记窗口已就绪，避免 Lifecycle 状态派发先后丢失结果。

    override fun onCreate(savedInstanceState: Bundle?) { // 建立首页、我的及其业务入口。
        super.onCreate(savedInstanceState) // 恢复系统页面生命周期。
        mine = savedInstanceState?.getBoolean("mine") ?: false // 只恢复 UI tab。
        channel = savedInstanceState?.getInt("channel") ?: 0 // 只恢复顶部频道。
        binding = ActivityMainBinding.inflate(layoutInflater) // 加载 Kotlin + XML 页面。
        setContentView(binding.root) // 显示主页面。
        binding.root.applySystemInsets() // 适配状态栏和导航栏。
        binding.homeTab.setOnClickListener { mine = false; pendingReturn = null; app.recordGuideEvent("home.tab_selected tab=home channel=$channel pending_cleared=true"); renderLocation() } // 切回首页不补弹。
        binding.mineTab.setOnClickListener { mine = true; pendingReturn = null; app.recordGuideEvent("home.tab_selected tab=mine pending_cleared=true"); renderLocation() } // 切到我的不补弹。
        listOf(binding.recommendChannel, binding.aiChannel, binding.sportsChannel, binding.localChannel) // 四个顶部频道。
            .forEachIndexed { index, button -> button.setOnClickListener { channel = index; pendingReturn = null; app.recordGuideEvent("home.channel_selected channel=$channel pending_cleared=true"); renderLocation() } } // 切 tab 丢弃过期结果。
        binding.searchBox.setOnClickListener { open(DemoPage.SEARCH_ACTIVATION, GuideResultContract.HOME_SEARCH) } // 搜索框到激活页。
        binding.directSearch.setOnClickListener { open(DemoPage.SEARCH_RESULTS, GuideResultContract.HOME_SEARCH) } // 搜索按钮到结果页。
        binding.highlightKeyword.setOnClickListener { // 暗词根据实际 deeplink 决定落点。
            if (guideReturn.waitingRequestCode != null) return@setOnClickListener // 避免多次点击。
            guideReturn.waitingRequestCode = GuideResultContract.HOME_SEARCH // 首页等待一次搜索类返回。
            app.recordGuideEvent("首页打开高亮暗词，等待搜索结果返回") // 便于排查 deeplink 实际落点。
            DemoNavigation.highlight(this, app.highlightDeeplink) { clearWaiting(GuideResultContract.HOME_SEARCH) } // 只认结果页标记。
        } // 高亮暗词入口结束。
        binding.scan.setOnClickListener { open(DemoPage.SCANNER, GuideResultContract.HOME_SCAN) } // 扫一扫返回首页检查 A。
        binding.newsCard.setOnClickListener { // 首页 AI 与普通频道新闻入口不同。
            if (channel == 1) open(DemoPage.NEWS_DETAIL, GuideResultContract.HOME_AI_DETAIL) // AI 列表资讯可触发 B。
            else DemoNavigation.page(this, DemoPage.NEWS_DETAIL) // 普通资讯不触发引导。
        } // 资讯入口结束。
        binding.aiSearchEntry.setOnClickListener { DemoNavigation.page(this, DemoPage.AI_SEARCH) } // AI 搜进入其自己的页面。
        binding.bookmarks.setOnClickListener { open(DemoPage.TOOLS, GuideResultContract.MINE_TOOLS, "bookmarks") } // 共用工具页左 tab。
        binding.history.setOnClickListener { open(DemoPage.TOOLS, GuideResultContract.MINE_TOOLS, "history") } // 共用工具页右 tab。
        binding.downloads.setOnClickListener { open(DemoPage.DOWNLOADS, GuideResultContract.MINE_TOOLS) } // 下载工具页。
        binding.btnLearnActivityResult.setOnClickListener { startActivity(Intent(this, LearnAActivity::class.java)) } // 学习 registerForActivityResult 入口。
        binding.settings.setOnClickListener { DemoNavigation.settings(this) } // 我的设置入口。
        binding.debugPanel.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE // Debug 仅展示验证面板。
        binding.guideStatus.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE // Debug 才显示次数。
        binding.debugPanel.setOnClickListener { DemoNavigation.debug(this) } // 配置与时钟调试。
        renderLocation() // 初始推荐 tab 只画 UI，不检查弹窗。
        app.recordGuideEvent("home.created tab=${if (mine) "mine" else "home"} channel=$channel pending=false") // 冷启和重建均没有旧行为资格。
    } // 主页面创建结束。

    private fun clearWaiting(code: Int) { // 路由失败时释放当前等待关系。
        runOnUiThread { if (guideReturn.waitingRequestCode == code) { guideReturn.waitingRequestCode = null; app.recordGuideEvent("home.route_failed waiting_cleared requestCode=$code") } } // 异步回调也回主线程。
    } // 路由失败处理结束。

    private fun open(kind: DemoPage, code: Int, tab: String? = null) { // 相关页面共用一套启动方式。
        if (guideReturn.waitingRequestCode != null) return // 一次只接收一个结果关系。
        guideReturn.waitingRequestCode = code // 标记当前原入口正等待此业务返回。
        app.recordGuideEvent("首页打开 ${kind.name}，等待 requestCode=$code 的原栈返回") // 没有结果回调时也能看到起点。
        DemoNavigation.page(this, kind, code, tab) { clearWaiting(code) } // ARouter 启动并关联 requestCode。
    } // 页面启动结束。

    private fun renderLocation() { // 只按当前选择渲染 UI。
        binding.homeContent.visibility = if (mine) View.GONE else View.VISIBLE // 首页与我的互斥。
        binding.mineContent.visibility = if (mine) View.VISIBLE else View.GONE // 展示我的工具。
        binding.homeTab.isSelected = !mine // 标记底部首页。
        binding.mineTab.isSelected = mine // 标记底部我的。
        binding.channelName.text = resources.getStringArray(R.array.channels)[channel] // 当前频道名称。
        listOf(binding.recommendChannel, binding.aiChannel, binding.sportsChannel, binding.localChannel) // 顶部四频道。
            .forEachIndexed { index, button -> button.isSelected = channel == index } // 只高亮当前 tab。
        val isRecommend = channel == 0 // Demo 的搜索区位于推荐。
        binding.searchRow.visibility = if (isRecommend) View.VISIBLE else View.GONE // 搜索框、按钮、扫一扫共一行。
        binding.highlightKeyword.visibility = if (isRecommend) View.VISIBLE else View.GONE // 暗词按配置实际落点导航。
        binding.newsCard.visibility = if (channel == 1) View.VISIBLE else View.GONE // AI 资讯列表入口。
        binding.aiSearchEntry.visibility = if (channel == 1) View.VISIBLE else View.GONE // AI 搜的二级流程。
    } // UI 刷新结束。

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { // Android 按原调用关系交付结果。
        super.onActivityResult(requestCode, resultCode, data) // 保留 AppCompat 及原业务结果分发。
        val marker = data?.getStringExtra(GuideResultContract.EXTRA_VISIT) // 诊断页展示实际返回的关键页标记。
        app.recordGuideEvent("首页收到返回：requestCode=$requestCode，resultCode=$resultCode，等待=${guideReturn.waitingRequestCode}，标记=$marker") // 留下本轮结果证据。
        if (guideReturn.waitingRequestCode != requestCode) { app.recordGuideEvent("首页忽略返回：requestCode 与当前等待不一致"); return } // 忽略不是此轮发起的结果。
        guideReturn.waitingRequestCode = null // 先消费，再检查标记，防止重复回调。
        val expected = GuideResultContract.expectedVisit(requestCode) ?: return // 未知请求码不做引导。
        if (marker != expected) { app.recordGuideEvent("首页不弹：需要访问 $expected，实际返回 $marker"); return } // 实际落点必须正确。
        pendingReturn = requestCode // 只交给本次紧邻的恢复阶段。
        app.recordGuideEvent("home.result_valid requestCode=$requestCode resumedForGuide=$resumedForGuide tab=${if (mine) "mine" else "home"} channel=$channel") // 区分等待恢复与立即消费。
        consumePendingIfReady() // 若已经恢复，立即处理；否则等 onPostResume。
    } // 返回结果处理结束。

    override fun onPostResume() { // 可能先收到结果，后完成宿主恢复。
        super.onPostResume() // 确保系统恢复回调完成。
        resumedForGuide = true // 有些设备在此阶段才交付 Activity 结果。
        if (pendingReturn != null || guideReturn.waitingRequestCode != null) app.recordGuideEvent("home.post_resume pending=$pendingReturn waiting=${guideReturn.waitingRequestCode} channel=$channel mine=$mine") // 只记录相关链路的恢复节点。
        consumePendingIfReady() // 冷启没有 pending，不会调用管理器。
        refreshDebugStatus() // 仅刷新可见诊断文字，不触发引导。
    } // 页面恢复完成。

    private fun consumePendingIfReady() { // 一次结果最多请求一次自动引导。
        if (!resumedForGuide) return // 尚不可展示时留给紧接的恢复。
        val request = pendingReturn ?: return // 没有此轮回传，直接结束。
        pendingReturn = null // 频次未到也不保留行为资格。
        if (!isExpectedLocation(request)) { app.recordGuideEvent("home.location_rejected requestCode=$request mine=$mine channel=$channel resumed=$resumedForGuide finishing=$isFinishing destroyed=$isDestroyed"); return } // 入口 tab 不匹配就放弃。
        val type = if (request == GuideResultContract.HOME_AI_DETAIL) GuideType.AI_QUICK_NEWS else GuideType.COMMON_TOOLS // 只按本入口决定类型。
        app.recordGuideEvent("首页返回有效：${type.name}，开始检查配置和本地频次") // 供本地记录页判断链路是否到达管理器。
        app.guideManager.tryShow(this, type, content(type), { isExpectedLocation(request) }, DemoNavigation::openAdd) { refreshDebugStatus() } // 落盘成功后更新首页计数。
    } // 本轮返回消费结束。

    private fun isExpectedLocation(request: Int): Boolean { // 不需要记录全局页面栈。
        val active = resumedForGuide && !isFinishing && !isDestroyed // 按实际页面恢复和销毁状态复核。
        return active && when (request) { // 检查当前两个层级的 tab。
            GuideResultContract.HOME_SEARCH, GuideResultContract.HOME_SCAN -> !mine && channel != 1 // A 位于首页非 AI tab。
            GuideResultContract.MINE_TOOLS -> mine // 工具 A 位于我的底部。
            GuideResultContract.HOME_AI_DETAIL -> !mine && channel == 1 // B 位于首页 AI tab。
            else -> false // 其他结果不属于主页面。
        } // 位置判断结束。
    } // 展示位置检查结束。

    private fun content(type: GuideType): GuideContent { // 两类组件共用底部 XML。
        val tools = type == GuideType.COMMON_TOOLS // 选择常用工具或 AI 快看资源。
        val component = if (tools) "tools" else "ai" // Demo 添加路由参数。
        return GuideContent( // 提供当前类型的文案、截图和两种链接。
            getString(if (tools) R.string.tools_title else R.string.ai_title), // 引导标题。
            getString(if (tools) R.string.tools_description else R.string.ai_description), // 功能说明。
            if (tools) R.drawable.preview_tools else R.drawable.preview_news, // 对应截图占位资源。
            getString(R.string.preview_description), // 图片无障碍描述。
            "demo4://guide/widget/add?type=$component&target=desktop", // 左边添加到桌面。
            "demo4://guide/widget/add?type=$component&target=assistant" // 右边添加到负一屏。
        ) // 内容绑定结束。
    } // 内容提供结束。

    private fun refreshDebugStatus() { // Debug 可观察已记录次数。
        if (!BuildConfig.DEBUG) return // 正式构建不显示状态。
        val record = app.guideManager // 获取同一个管理器。
        lifecycleScope.launch { // 读取磁盘可能需要 IO。
            val state = runCatching { record.snapshot() }.getOrNull() ?: return@launch // 损坏记录不伪造次数。
            binding.guideStatus.text = getString(R.string.guide_status, state.commonTools.showCount, state.aiQuickNews.showCount) // 显示真实次数。
        } // Debug 状态查询结束。
    } // 状态渲染结束。

    override fun onStop() { // 离开原宿主时丢弃未消费结果。
        if (pendingReturn != null || guideReturn.waitingRequestCode != null) app.recordGuideEvent("home.stopped pending_dropped=$pendingReturn waiting=${guideReturn.waitingRequestCode}") // 业务页打开时等待码仍保留，本轮 pending 必须丢弃。
        pendingReturn = null // 后台再回来不能凭旧结果弹窗。
        app.guideManager.dismissForOwner(this) // 关闭当前宿主正在显示的底部 Dialog。
        super.onStop() // 完成系统生命周期回调。
    } // 宿主离开结束。

    override fun onPause() { // 离开页面后异步读盘不能再打开弹窗。
        if (pendingReturn != null) app.recordGuideEvent("home.paused pending=$pendingReturn") // 诊断快速离开导致的资格丢弃。
        resumedForGuide = false // 当前宿主已不适合展示。
        super.onPause() // 保留系统暂停派发。
    } // 暂停处理结束。

    override fun onSaveInstanceState(outState: Bundle) { // 只保存 UI 选中位置。
        outState.putBoolean("mine", mine) // 恢复底部首页 / 我的。
        outState.putInt("channel", channel) // 恢复首页顶部频道。
        super.onSaveInstanceState(outState) // 不保存待弹资格。
    } // UI 状态保存结束。
} // 首页和我的页结束。
