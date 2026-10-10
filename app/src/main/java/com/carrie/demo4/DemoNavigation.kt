package com.carrie.demo4

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.alibaba.android.arouter.launcher.ARouter
import com.carrie.widgetguide.core.*
import com.carrie.widgetguide.ui.WidgetGuideNavigator
import java.util.UUID

object DemoNavigation {
    fun page(context: Context, from: PageRef, to: PageKind, entry: EntryPoint = EntryPoint.OTHER) {
        ARouter.getInstance().build("/browser/page")
            .withString("pageId", UUID.randomUUID().toString())
            .withString("pageKind", to.name)
            .withString("fromId", from.id).withString("fromKind", from.kind.name)
            .withString("entry", entry.name).withString("navigationId", UUID.randomUUID().toString())
            .navigation(context)
    }

    fun navigator(context: Context) = WidgetGuideNavigator { deeplink ->
        val uri = Uri.parse(deeplink)
        if (uri.scheme != "demo4" || uri.host != "guide" || uri.path != "/widget/add") false
        else {
            ARouter.getInstance().build("/widget/add")
                .withString("type", uri.getQueryParameter("type") ?: "choose")
                .withString("target", uri.getQueryParameter("target") ?: "choose")
                .navigation(context)
            true
        }
    }
}

fun View.applySystemInsets() {
    val startLeft = paddingLeft; val startTop = paddingTop
    val startRight = paddingRight; val startBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(startLeft + bars.left, startTop + bars.top, startRight + bars.right, startBottom + bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
