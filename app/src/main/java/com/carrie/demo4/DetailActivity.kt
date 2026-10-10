package com.carrie.demo4

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.android.arouter.facade.annotation.Route
import com.carrie.demo4.databinding.ActivityDetailBinding
import com.carrie.widgetguide.core.*

@Route(path = "/browser/page")
class DetailActivity : AppCompatActivity() {
    private lateinit var page: PageRef
    private val controller get() = (application as GuideApplication).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root); binding.root.applySystemInsets()
        val kind = runCatching { PageKind.valueOf(intent.getStringExtra("pageKind").orEmpty()) }.getOrNull()
        val id = intent.getStringExtra("pageId")
        if (kind == null || id == null) { finish(); return }
        page = PageRef(id, kind)
        if (savedInstanceState == null) {
            val source = runCatching {
                PageRef(requireNotNull(intent.getStringExtra("fromId")),
                    PageKind.valueOf(requireNotNull(intent.getStringExtra("fromKind"))))
            }.getOrNull()
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
        binding.returnMain.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        binding.back.setOnClickListener { finish() }
    }

    private fun open(kind: PageKind, entry: EntryPoint = EntryPoint.OTHER) = DemoNavigation.page(this, page, kind, entry)
    override fun onResume() { super.onResume(); if (::page.isInitialized) controller.visible(page) }
}
