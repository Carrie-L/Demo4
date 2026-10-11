# B 分支：Kotlin + XML 代码接入说明

本说明对应已经写入 B 分支的源码。初次读代码可先看[图解代码导读](CODE_WALKTHROUGH.md)；[系统设计](SYSTEM_DESIGN.md) 讲业务规则，[测试与风险](ACCEPTANCE.md) 讲验证边界。代码中的业务步骤逐行添加中文注释；理解一次返回的最好方式是按下面的调用顺序读代码。

## 1. 先找这五处代码

| 文件 | 作用 |
|---|---|
| `app/.../GuideResultContract.kt` | 定义访问标记和固定 requestCode；`GuideReturnState` 只在本轮内存保存等待状态 |
| `app/.../DemoNavigation.kt` | 继续使用 ARouter；相关入口调用 `navigation(activity, requestCode)`，普通中间页照常打开 |
| `app/.../MainActivity.kt` | 首页 / 我的页接收结果，核对底部与顶部 tab，在本次返回恢复时检查 A 或 B |
| `app/.../DetailActivity.kt` | 搜索激活页中转结果、关键页 `setResult`、AI 二级列表接收资讯结果 |
| `widget-guide/.../GuideRules.kt`、`GuideStore.kt`、`WidgetGuideManager.kt` | 纯频次判断、可读键名 SP 存储、单实例底部 Dialog |

以上路径的 `...` 分别为 `src/main/java/com/carrie/demo4` 和 `src/main/java/com/carrie/widgetguide`。界面布局是 `app/src/main/res/layout/activity_main.xml`、`activity_detail.xml`、`item_hot_chart.xml` 及 `widget-guide/src/main/res/layout/dialog_widget_guide.xml`。

## 2. 实际的数据如何逐级返回

首页点击搜索框时，`MainActivity.open(SEARCH_ACTIVATION, HOME_SEARCH)` 先将 `waitingRequestCode` 设为 `HOME_SEARCH`，再通过 ARouter 打开激活页并请求结果。

激活页的搜索按钮、历史、猜你想搜、电影电视榜和综艺榜统一调用 `searchFromActivation()`。结果页 `onPostResume` 确认实际进入后，将 `SEARCH_RESULTS` 放进结果 Intent。用户在结果页打开其他普通页面，再逐层返回时，这些普通页面既不记录访问，也不添加引导字段。

结果页结束时，激活页 `onActivityResult` 读到本次 `ACTIVATION_SEARCH` 和 `SEARCH_RESULTS`，调用 `prepareSearchResult()` 准备自己的结果。激活页结束时，首页同样读取 `HOME_SEARCH` 和 `SEARCH_RESULTS`，当场消费一次。只有这个时候才调用 `WidgetGuideManager.tryShow`。

| 路径 | 接收结果的原入口 | 访问标记 | 展示位置 |
|---|---|---|---|
| 首页搜索按钮 / 激活页搜索 | 首页 | `SEARCH_RESULTS` | 首页非 AI tab，常用工具 |
| 首页扫一扫 | 首页 | `SCAN` | 首页非 AI tab，常用工具 |
| 我的书签 / 历史 / 下载 | 我的页所处 `MainActivity` | `TOOLS` | 我的页，常用工具 |
| 首页 AI 资讯 | 首页 | `AI_DETAIL` | 首页 AI tab，AI 快看 |
| AI 搜 → AI 快看二级 → 资讯 | AI 二级列表 `DetailActivity` | `AI_DETAIL` | AI 二级列表，AI 快看 |

AI 搜到二级列表的打开方式不请求引导结果；B 不会传回 AI 搜。高亮暗词按 `highlightDeeplink` 实际打开结果页或普通资讯：只有结果页的 `SEARCH_RESULTS` 才能匹配 `HOME_SEARCH`。游戏与视频榜打开普通页面，没有搜索结果标记。书签与历史在同一个 `TOOLS` Activity 的左右 tab，通过 `selectedTab` 决定初始位置。

## 3. 返回字段解释

| 字段 / 值 | 含义 |
|---|---|
| `EXTRA_VISIT` | 唯一引导附加字段的键；可以和原业务返回数据共存 |
| `SEARCH_RESULTS` | 实际进入搜索结果页 |
| `TOOLS` | 实际进入书签历史容器或下载页 |
| `SCAN` | 实际进入扫一扫页，不要求扫描成功 |
| `AI_DETAIL` | 实际进入资讯详情页，还要核对发起入口 |
| `HOME_SEARCH` / `HOME_SCAN` / `MINE_TOOLS` | 首页搜索、扫一扫、我的工具发起的结果关系 |
| `HOME_AI_DETAIL` / `SECONDARY_AI_DETAIL` | 首页 AI、AI 二级列表各自发起的资讯结果关系 |
| `ACTIVATION_SEARCH` | 激活页内部发起结果页，用于向首页中转 |
| `waitingRequestCode` | 普通 ViewModel 中当前等待的请求码；结果一到就清空 |
| `hasVisitedSearchResults` | 同一次激活页内至少去过一次结果页，旋转时保留 |
| `pendingReturn` / `pendingAiReturn` | 返回回调与紧接着的宿主恢复之间的一次性交接 |

这些字段都不是持久的“将来可弹窗资格”。宿主切 tab、停止、进程重启或一次检查被拒绝时，机会消失。首页冷启只有 UI 恢复，没有此次有效返回，因此不会弹。

`onActivityResult` 中先执行 `super`，然后匹配自己的等待请求码与实际访问标记。扫码业务可以继续使用 `RESULT_CANCELED`；引导只查看附加访问字段，不篡改扫码结果。接入生产项目时，应在原结果 Intent 中追加字段，不能用新的 Intent 覆盖业务已有 extras。

## 4. ARouter 和业务 Promise

`DemoNavigation.page()` 对有关的入口调用 `Postcard.navigation(activity, requestCode, callback)`。导航失败或被拦截时，回调清除本轮等待关系；`onArrival` 不弹窗。系统返回键、返回手势或页面按钮的 `finish()` 将结果交还原调用方，代码没有“返回时启动 MainActivity”或清栈路径。

原生 `startActivityForResult()` 不返回 Promise，`.thenAccept` 是项目封装。若生产项目有现成封装，可把本实现 `onActivityResult` 的请求码和访问标记匹配搬进真正的页面返回回调；不要把 `onArrival` 当成返回。当前 Demo 直接使用已验证的 ARouter 1.5.2 requestCode API。

## 5. 管理器频次和存储

`GuidePolicy.canShow` 的调用前提是本次真实访问并回到正确宿主。它仅使用最新配置、当前时间、设备时区和七个持久字段。次数 0 立即允许；次数 1 比较 `firstPopInterval × 24h`；次数 2 比较 `secondPopInterval × 24h`；次数 3 永不再弹。配置中的 `thirdPopInterval` 不参与任何判断。

| 类型 | SP 前缀 | 三个独立字段 |
|---|---|---|
| 常用工具 | `common_tools_widget` | `show_count`、`last_shown_at_millis`、`stopped_after_add_click` |
| AI 快看 | `ai_quick_news_widget` | 同上，属于 AI 快看自己的键 |

另有共享键 `last_any_widget_guide_shown_at_millis`，保证两类合计本地自然日最多展示一次。所有键集中在 `GuideStore.kt`，没有用单字母 A/B 当键名。存储文件是 `browser_widget_guide_frequency`，清除应用全部数据可以重置它。云配和 Debug 时钟不写入这里。

`WidgetGuideManager` 在当前 Dialog 的 `onShow` 回调里用 `counted` Boolean 防止**同一个 Dialog 的重复回调**把展示次数加两次；下一轮行为产生的新 Dialog 可以正常加一次。窗口显示一次就计次，空白取消与系统返回也不回滚。存储写入在 IO 线程执行，在写入完成前禁用两个添加按钮。

点击任一按钮先把当前类型 `stopped` 设为 true，再写本地记录，最后调用 deeplink。链接打不开仍停止当前类型；另一类型的停止位不受影响。设置页手动入口直接调用同一 deeplink 分发方法，不调用引导管理器，只有荣耀设备展示设置 item。

## 6. XML 页面如何验证

在 Debug 面板开启 `enable`，设置前两段间隔；切换高亮暗词的结果或资讯实际 deeplink；按普通返回操作验证。

- 首页推荐：搜索框、搜索按钮、扫一扫并列，另有高亮暗词按钮。首页默认推荐 tab。
- 搜索激活：顶部搜索框和按钮、搜索历史、猜你想搜、四榜横向 ViewPager2。电影电视和综艺进入结果，游戏和视频进入非结果页。
- 我的：书签和历史进入同一个 `TOOLS` Activity 的左右 tab，下载独立入口。
- 首页 AI：资讯进入详情；AI 搜进入二级列表，二级列表访问资讯时 B 弹在二级列表。
- 设置：非荣耀隐藏添加 item；荣耀点击直接打开添加页，不记自动弹窗次数。

Debug 面板的配置与时钟只在当前进程有效；推进一天不触发弹窗，要重新访问并沿原栈返回。页面截图使用仓库原有占位 drawable；正式集成时替换为产品截图和快服务 deeplink。

源代码每个新增 Kotlin 语句都附中文说明，XML 新节点紧邻中文注释。未来合入真实应用时，可保留这些解释行为原因的注释，并把 Demo 的具体资源和云配适配为生产依赖。
