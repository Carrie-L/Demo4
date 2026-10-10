package com.carrie.widgetguide.ui

import androidx.annotation.DrawableRes
import com.carrie.widgetguide.core.GuideType

data class GuideContent(
    val title: String,
    val description: String,
    @DrawableRes val previewRes: Int,
    val previewDescription: String,
    val desktopDeeplink: String,
    val assistantDeeplink: String
)

fun interface GuideContentProvider { fun content(type: GuideType): GuideContent }
fun interface WidgetGuideNavigator { fun open(deeplink: String): Boolean }
