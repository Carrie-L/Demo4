package com.carrie.demo4

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.android.arouter.facade.annotation.Route
import com.carrie.demo4.databinding.ActivityDetailBinding
import com.carrie.widgetguide.core.*

/**
 * 复用一个验证 Activity 承载搜索激活、结果、工具、资讯和普通中间页。
 * 业务类型由 pageKind 区分，页面实例由 pageId 区分；正式项目由各真实页面接入。
 */
@Route(path = "/browser/page")
class DetailActivity : AppCompatActivity() {
    /**
     * 当前目标页面的身份，后续继续导航时成为下一事件的来源。
     */
    private lateinit var page: PageRef
    /**
     * 访问当前进程控制器，避免缓存调试重置前的旧实例。
     */
    private val controller get() = (application as GuideApplication).controller

    /**
     * 解析内部路由参数，首次创建才上报已进入事件；旋转重建不生成新导航。
     * 当前适配器按 standard Activity 编写，singleTop/singleTask 的 onNewIntent 需另行接入。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root); binding.root.applySystemInsets()
        val kind = runCatching { PageKind.valueOf(intent.getStringExtra("pageKind").orEmpty()) }.getOrNull()
        val id = intent.getStringExtra("pageId")
        if (kind == null || id == null) { finish(); return }
        page = PageRef(id, kind)
        // 同一 Intent 在配置重建时会再次可读，但不能将重建当作新行为。
        if (savedInstanceState == null) {
            val source = runCatching {
                PageRef(requireNotNull(intent.getStringExtra("fromId")),
                    PageKind.valueOf(requireNotNull(intent.getStringExtra("fromKind"))))
            }.getOrNull()
            // 这些参数由内部 DemoNavigation 生成；Activity 不对外导出，正式外部 deeplink 不应直接信任。
            if (source != null) controller.entered(NavigationEvent(
                requireNotNull(intent.getStringExtra("navigationId")), source, page,
                EntryPoint.valueOf(requireNotNull(intent.getStringExtra("entry")))))
        }
        binding.pageTitle.text = getString(when (kind) {
            PageKind.SEARCH_ACTIVE -> R.string.search_active
            PageKind.SEARCH_RESULTS -> R.string.search_results
            PageKind.BOOKMARKS -> R.string.bookmarks
            PageKind.HISTORY -> R.string.history
            PageKind.DOWNLOADS -> R.string.downloads
            PageKind.NEWS_DETAIL -> R.string.news_detail
            else -> R.string.other_page
        })
        binding.toSearchActive.setOnClickListener { open(PageKind.SEARCH_ACTIVE) }
        binding.toResults.setOnClickListener { open(PageKind.SEARCH_RESULTS,
            if (kind == PageKind.SEARCH_ACTIVE) EntryPoint.SEARCH_SUBMIT else EntryPoint.OTHER) }
        binding.toOther.setOnClickListener { open(PageKind.OTHER) }
        // 清除上方验证页并复用主页面，保留首页频道和底部 Tab 选中状态。
        binding.returnMain.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        binding.back.setOnClickListener { finish() }
    }

    /**
     * 从当前实际页面继续导航；默认 OTHER，只有搜索激活页提交时才传 SEARCH_SUBMIT。
     */
    private fun open(kind: PageKind, entry: EntryPoint = EntryPoint.OTHER) = DemoNavigation.page(this, page, kind, entry)
    /**
     * 进入事件之后报告可见页；返回该页面只更新可见身份，不重复上报导航。
     */
    override fun onResume() { super.onResume(); if (::page.isInitialized) controller.visible(page) }
}
