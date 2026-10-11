# B 分支：Kotlin + XML 接入说明

配套 [系统设计](SYSTEM_DESIGN.md)。本文件是后续编码示例，不是已接入源码；省略 imports、页面原有 UI 和依赖注入。路由、页面名、ViewBinding 名按正式工程替换。所有返回仍使用原有系统返回 / `finish()`。

## 1. 返回协议：一个标记字段，几个固定请求码

```kotlin
/** 引导附加返回协议。请求码只需在接收它们的 Activity 内避免冲突。 */
object GuideResultContract {
    // 返回 Intent 的附加字段；不要使用 Intent 的全部 extras 覆盖业务返回。
    const val EXTRA_VISIT = "browser.widget.guide_visit"

    // 实际显示过的关键页类型，避免“点了搜索入口”就被当成完成搜索。
    const val SEARCH_RESULTS = "search_results"
    const val TOOLS = "tools"
    const val SCAN = "scan"
    const val AI_DETAIL = "ai_detail"

    const val HOME_SEARCH = 6101       // 首页直达结果或打开搜索激活页。
    const val HOME_SCAN = 6102         // 首页扫一扫。
    const val MINE_TOOLS = 6103        // 我的书签、历史、下载，共用一次 A 检查。
    const val HOME_AI_DETAIL = 6104    // 首页 AI 快看打开资讯。
    const val SECONDARY_AI_DETAIL = 6105 // AI 二级列表打开资讯，结果归二级列表。
    const val ACTIVATION_SEARCH = 6110 // 激活页内部打开结果页，只负责中转。

    /** 每个返回入口只接受自己期望的关键页标记。 */
    fun expectedVisit(requestCode: Int): String? = when (requestCode) {
        HOME_SEARCH, ACTIVATION_SEARCH -> SEARCH_RESULTS
        HOME_SCAN -> SCAN
        MINE_TOOLS -> TOOLS
        HOME_AI_DETAIL, SECONDARY_AI_DETAIL -> AI_DETAIL
        else -> null
    }
}

/** 只保存当前打开尚未返回的请求；普通 ViewModel，不使用 SavedStateHandle。 */
class GuideReturnState : ViewModel() {
    var waitingRequestCode: Int? = null
}
```

| 字段 | 含义 | 不代表什么 |
|---|---|---|
| `requestCode` | 谁发起的哪类业务返回，由 Android 交还原 Activity | 页面 ID、全局跳转历史 |
| `EXTRA_VISIT` | 结果 Intent 中附加字段的键名 | 云配字段、持久化键 |
| `SEARCH_RESULTS` 等值 | 实际访问过的关键页面类型 | 组件添加成功 |
| `waitingRequestCode` | 当前入口尚未消费的业务返回关系 | 已获得永久弹窗资格 |

不再传旧方案的 `pageId`、`fromId`、`navigationId`，不广播所有页面事件。

## 2. 入口仍用 ARouter 打开

下面示例放在首页所属 Activity 中。示意路由沿用项目实际地址替换。

```kotlin
private val guideReturn: GuideReturnState by viewModels()

private fun openSearchActivation() {
    // 防止连续点击同时打开两个引导相关页面。
    if (guideReturn.waitingRequestCode != null) return
    guideReturn.waitingRequestCode = GuideResultContract.HOME_SEARCH

    ARouter.getInstance()
        .build("/search/activation")
        // 第一个参数必须是当前 Activity；第二个参数建立结果回传关系。
        .navigation(this, GuideResultContract.HOME_SEARCH)
}
```

正式接入还需在现有路由失败 / 中断 / 异常处理里清空对应等待字段，否则失败后会阻挡下次点击。异步路由失败回调切回主线程后处理。成功的 `onArrival` 不清空等待字段，也不触发弹窗。

其余入口只改变路由与请求码：

| 点击 | 路由目标 | 请求码 |
|---|---|---|
| 首页搜索按钮 | 搜索结果页 | `HOME_SEARCH` |
| 首页高亮暗词 | deeplink 解析后的实际内部页面 | `HOME_SEARCH`，最后仍须匹配结果页标记 |
| 首页扫一扫 | 扫一扫页 | `HOME_SCAN` |
| 我的书签 / 历史 | 同一个容器，传入原有初始 tab 参数 | `MINE_TOOLS` |
| 我的下载 | 下载页 | `MINE_TOOLS` |
| 首页 AI 资讯 | 资讯详情 | `HOME_AI_DETAIL` |
| AI 二级列表资讯 | 资讯详情 | `SECONDARY_AI_DETAIL`，由二级列表 Activity 发起 |

Fragment tab 的按钮可委托宿主 Activity 打开；使用 `navigation(requireActivity(), code)` 时结果归 Activity，不能期待自动进入 Fragment 的结果回调。

高亮暗词如果经过统一 deeplink 分发器，让分发器保留当前 Activity 和 requestCode，并对实际内部目标使用带结果的打开方式。若最终是资讯，回传的 `AI_DETAIL` 与首页期望的 `SEARCH_RESULTS` 不匹配，因此不弹。外部页面、失败路由没有搜索访问标记。

如果分发器是独立的中转 Activity，需要在这一个中转点按已有返回协议转交结果；不要只在点击暗词时写“搜索已完成”。这不要求其他业务页接入引导。

## 3. 关键页准备结果，正常返回时自动交付

以原本没有业务返回数据的搜索结果页为例：

```kotlin
// 页面自己的返回数据。若原页面已有数据对象，请复用原对象。
private val outgoingResult = Intent()

override fun onPostResume() {
    super.onPostResume()
    // 在真实结果页面恢复显示后设置，不在路由点击或 onArrival 中设置。
    outgoingResult.putExtra(
        GuideResultContract.EXTRA_VISIT,
        GuideResultContract.SEARCH_RESULTS
    )
    setResult(Activity.RESULT_OK, outgoingResult)
    // 此处不 finish，不弹窗。用户可以继续打开更多页面。
}
```

页面可显示加载态、空结果、错误态；本需求以进入结果页为准，不要求搜索请求成功。若容器先显示其他业务，须在实际结果视图显示时设置，不能按 Activity 类名提前推断。

工具页换成 `TOOLS`，扫描页换成 `SCAN`，资讯详情页换成 `AI_DETAIL`。重复恢复时覆盖同一个标记即可，不增加计数。书签 / 历史容器只准备一份 `TOOLS`。

有原业务结果时：保持原 resultCode 和返回 Intent，在它的组装处追加该标记；以后每次更新结果也保留该字段。扫码的 `RESULT_CANCELED` 不应被强行改成 `RESULT_OK`。系统返回、工具栏返回和返回手势都使用同一份已准备的结果，不需要各实现一套弹窗触发。

关键页继续打开的普通页面保持原代码。它们返回关键页只会使关键页恢复显示，原入口还没收到结果，因此不会提前弹。

## 4. 搜索激活页只负责转交

激活页有自己的普通 ViewModel：两个 Boolean，`waitingForResults` 表示正在等本次结果页返回，`hasVisitedSearchResults` 表示这次激活页停留中至少收到过一次有效结果。

```kotlin
class SearchActivationState : ViewModel() {
    var waitingForResults = false
    var hasVisitedSearchResults = false
}

private val state: SearchActivationState by viewModels()

private fun search(keyword: String) {
    if (state.waitingForResults) return
    state.waitingForResults = true
    ARouter.getInstance().build("/search/results")
        .withString("keyword", keyword) // 原有业务搜索词，与引导规则无关。
        .navigation(this, GuideResultContract.ACTIVATION_SEARCH)
    // 路由失败 / 中断时，按现有错误回调把 waitingForResults 清为 false。
}

override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    // 原有业务结果仍由原代码处理；下面只读引导附加字段。
    if (requestCode != GuideResultContract.ACTIVATION_SEARCH) return
    if (!state.waitingForResults) return
    state.waitingForResults = false

    if (data?.getStringExtra(GuideResultContract.EXTRA_VISIT) ==
        GuideResultContract.SEARCH_RESULTS) {
        state.hasVisitedSearchResults = true
        prepareGuideResult()
    }
    // 这里不弹 A：规定展示位置是首页。
}

private fun prepareGuideResult() {
    if (!state.hasVisitedSearchResults) return
    // 示例假设激活页没有原业务结果；有则合并到原结果对象。
    setResult(Activity.RESULT_OK, Intent().putExtra(
        GuideResultContract.EXTRA_VISIT,
        GuideResultContract.SEARCH_RESULTS
    ))
}
```

在激活页 `onCreate` 初始化完毕后也调用一次 `prepareGuideResult()`，让配置重建后保留在普通 ViewModel 中的状态重新写入 Activity 返回结果。没有访问过则不写标记。

搜索按钮、搜索历史、猜你想搜、电影电视榜和综艺榜最终打开结果页时，统一走 `search()` 或等价返回方法。游戏 / 视频保持原路由，不写搜索标记。

一次激活页里重复搜索，多次得到结果只把同一个 Boolean 保持为 true。最后正常返回首页才有一次检查；之前访问过结果、之后又访问游戏，不会清掉本轮已经成立的结果访问。

## 5. 原入口消费一次返回

以下片段适用于首页 / 我的所属 Activity，以及 AI 二级列表 Activity。`isExpectedGuideLocation` 和 `guideManager` 是需要接入当前页面状态与管理器的业务方法。

```kotlin
// 仅在本次回调与紧接着的恢复之间交接，不存入 ViewModel 或 SavedState。
private var pendingReturn: Int? = null

override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    // 原业务结果照常处理。引导独立校验自己的字段。
    if (guideReturn.waitingRequestCode != requestCode) return
    guideReturn.waitingRequestCode = null // 先消费，重复回调不再处理。

    val expected = GuideResultContract.expectedVisit(requestCode) ?: return
    if (data?.getStringExtra(GuideResultContract.EXTRA_VISIT) != expected) return
    pendingReturn = requestCode
    consumeGuideReturnIfResumed()
}

override fun onPostResume() {
    super.onPostResume()
    consumeGuideReturnIfResumed()
}

private fun consumeGuideReturnIfResumed() {
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    val request = pendingReturn ?: return
    pendingReturn = null // 必须先清空，频次未到时不能保留机会。

    if (isFinishing || isDestroyed || !isExpectedGuideLocation(request)) return
    val type = when (request) {
        GuideResultContract.HOME_SEARCH,
        GuideResultContract.HOME_SCAN,
        GuideResultContract.MINE_TOOLS -> GuideType.A
        GuideResultContract.HOME_AI_DETAIL,
        GuideResultContract.SECONDARY_AI_DETAIL -> GuideType.B
        else -> return
    }
    guideManager.tryShow(this, type) // 立即判断一次；拒绝不排队、不定时重试。
}

override fun onStop() {
    pendingReturn = null // 交接尚未完成就离开，直接放弃。
    // 同时关闭此 Activity 持有的引导 Dialog；不要自动恢复它。
    super.onStop()
}
```

`isExpectedGuideLocation` 直接读取已有页面状态，不需要页面 ID：

| 请求 | 当前必须满足的位置 |
|---|---|
| `HOME_SEARCH`、`HOME_SCAN` | 底部导航在首页，首页顶部 tab 不是 AI 快看 |
| `MINE_TOOLS` | 底部导航在我的页 |
| `HOME_AI_DETAIL` | 底部导航在首页，顶部 tab 是 AI 快看 |
| `SECONDARY_AI_DETAIL` | 原 AI 二级列表本身可见 |

tab 切换入口也清空尚未消费的 `pendingReturn`。切换只清理、不调用 `tryShow`。普通 `onResume` 没有新的 pendingReturn 就什么也不做；禁止读取持久化次数后自行找机会补弹。

进程被杀后，普通 ViewModel 的等待字段为空，即使 Android 恢复之前的结果也不会被消费。配置旋转仍可保留“等待页面返回”的关系。已经消费 / 展示过的结果不恢复；如果恰好在交接间旋转而丢失一次机会，可以放弃，不额外引入恢复机制。

## 6. 管理器里的纯频次判断

页面回传负责“本轮行为是否成立”；下面的函数只负责“频次是否允许”，不依赖 Android，不读文件、不弹 UI。三个数据类可放在管理器同一个文件中。

```kotlin
enum class GuideType { A, B }

/** 一份云配给 A/B 使用；第三间隔故意不进入业务模型。 */
data class GuideRules(
    val enable: Boolean,
    val firstPopInterval: Long?,  // 单位天；null 表示缺失或解析无效。
    val secondPopInterval: Long?
)

/** 每种弹窗独立保存一份；时间单位为 Unix 毫秒。 */
data class TypeRecord(
    val showCount: Int = 0,
    val lastShownAtMillis: Long = 0,
    val stopped: Boolean = false
)

/** 唯一共享项为 A/B 最近一次展示时间。 */
data class GuideRecord(
    val a: TypeRecord = TypeRecord(),
    val b: TypeRecord = TypeRecord(),
    val lastAnyShownAtMillis: Long = 0
)

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/**
 * 调用前已确认本轮访问并返回、宿主位置正确。
 * nowMillis 和 zone 显式传入，便于测试时间边界。
 */
fun canShowByFrequency(
    type: GuideType,
    rules: GuideRules,
    record: GuideRecord,
    nowMillis: Long,
    zone: ZoneId
): Boolean {
    val own = if (type == GuideType.A) record.a else record.b
    if (!rules.enable || own.stopped || own.showCount !in 0..2) return false

    val sharedTime = record.lastAnyShownAtMillis
    // 时间被拨回到已记录展示以前，保守拒绝，不重置次数。
    if (nowMillis <= 0 || sharedTime < 0 || nowMillis < sharedTime) return false
    if (sharedTime > 0) {
        val previousDay = Instant.ofEpochMilli(sharedTime).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        if (previousDay == today) return false // A/B 共享自然日额度。
    }

    if (own.showCount == 0) return true // 首次无单类型冷却。
    if (own.lastShownAtMillis <= 0 || nowMillis < own.lastShownAtMillis) return false

    val days = when (own.showCount) {
        1 -> rules.firstPopInterval
        2 -> rules.secondPopInterval
        else -> return false
    } ?: return false
    // 防止错误配置被当成负冷却，以及 Long 乘法溢出。
    if (days < 0 || days > Long.MAX_VALUE / DAY_MILLIS) return false
    return nowMillis - own.lastShownAtMillis >= days * DAY_MILLIS
}
```

管理器每次 `tryShow` 获取当前配置和 `System.currentTimeMillis()`、`ZoneId.systemDefault()`，调用此函数。配置尚未就绪则关闭本轮。数据读取层必须检查记录结构和字段一致性，不能把损坏的非空记录变成默认新用户记录；例如展示次数大于零却没有类型时间或共享时间，应阻止自动引导并记录错误。

读取、展示与写入的职责分开即可：读出当前记录；主线程判断；窗口显示后更新内存记录并按序写磁盘。不要在此函数中启动定时器，也不要返回可持久化的“待弹资格”。

## 7. SP / MMKV 怎样写

下面是 SP 的一次展示写入片段。调用方已在主线程更新内存、锁定当前正在展示的引导；磁盘操作在单一顺序执行的协程中调用。不要给每次写入另起互相并发的协程。

```kotlin
/** IO 线程调用。shownCount 是包含本次展示后的次数。 */
fun saveShown(
    prefs: SharedPreferences,
    type: GuideType,
    shownCount: Int,
    shownAtMillis: Long
): Boolean {
    val prefix = if (type == GuideType.A) "a" else "b"
    return prefs.edit()
        .putInt("${prefix}_show_count", shownCount)
        .putLong("${prefix}_last_shown_at", shownAtMillis)
        .putLong("last_any_shown_at", shownAtMillis)
        .commit() // 校验写入结果；不能在主线程等磁盘。
}

/** IO 线程调用。只改当前类型，不改另一类型或共享日期。 */
fun saveStopped(prefs: SharedPreferences, type: GuideType): Boolean {
    val key = if (type == GuideType.A) "a_stopped" else "b_stopped"
    return prefs.edit().putBoolean(key, true).commit()
}
```

若项目统一使用 MMKV，将同样七个字段作为一条记录 encode / decode，并沿用相同更新次序、错误处理和单测。无需同时维护 SP 和 MMKV 两份状态，也不需要 DataStore。

一个简单的执行约定：显示期间管理器持有 busy 标志；`onShow` 后先更新内存，再执行展示记录写入；写入完成前两个添加按钮暂不可点。添加点击又先改内存停止标志，禁用按钮，写入停止后尝试 deeplink。关闭 Dialog 不应取消已经开始的必要记录写入。管理器使用应用级存活的协程完成记录，但不持有 Activity 以外的长期 UI 引用。

显示写入失败时解除 UI 等待，但本进程关闭后续自动引导；当前 Dialog 的手动添加仍可用。停止写入失败时同样保留内存停止，并记录错误；若宿主仍有效，仍可尝试用户已点击的链接。存储损坏 / 失败不自动清数据。这里没有后台重试弹窗或磁盘预约状态。

## 8. 复用一个 XML 底部弹窗

布局骨架如下，`@string`、截图 drawable 和样式由正式产品资源补齐。示意中标题与截图运行时绑定。

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- A/B 共用内容布局；窗口的底部位置在 Kotlin 中设置。 -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="20dp">

    <!-- 当前组件对应的引导标题。 -->
    <TextView
        android:id="@+id/guideTitle"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="18sp" />

    <!-- 产品提供真实组件截图；运行时按 A/B 设置图片与无障碍描述。 -->
    <ImageView
        android:id="@+id/widgetPreview"
        android:layout_width="match_parent"
        android:layout_height="160dp"
        android:layout_marginTop="16dp"
        android:scaleType="fitCenter" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:orientation="horizontal">

        <!-- 左按钮固定为桌面，不随 A/B 交换位置。 -->
        <Button
            android:id="@+id/addDesktop"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:minHeight="48dp"
            android:text="@string/guide_add_desktop" />

        <!-- 右按钮固定为负一屏。 -->
        <Button
            android:id="@+id/addAssistant"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginStart="12dp"
            android:minHeight="48dp"
            android:text="@string/guide_add_assistant" />
    </LinearLayout>
</LinearLayout>
```

字符串：`guide_add_desktop` 为“添加到桌面”，`guide_add_assistant` 为“添加到负一屏”。标题、截图描述也放 strings / 产品配置，不在布局硬编码具体 A/B 文案。

可使用普通 `Dialog` 配合这个 XML，减少自动恢复带来的额外状态。设置底部 gravity、窗口宽 MATCH_PARENT、高 WRAP_CONTENT、背景遮罩，允许 `setCanceledOnTouchOutside(true)`。应用系统栏 Insets，避免按钮压在导航栏下；大字体 / 小屏幕时内容容器可滚动。

| Kotlin 接入点 | 具体动作 |
|---|---|
| show 前 | 检查 Activity 已恢复、未结束、位置正确、无占用；准备 A/B 内容，安装监听 |
| `setOnShowListener` | 本 Dialog 的 Boolean 防重复；记一次展示，按序保存，完成后启用添加按钮 |
| 左右按钮 | 单次点击保护；停止当前类型并落盘；关闭弹窗，按按钮取相应 deeplink |
| `setOnCancelListener` | 只关闭，不改停止状态，不退次数 |
| `setOnDismissListener` | 清理窗口引用；必要写入完成后释放管理器 busy 标志 |
| 宿主 `onStop` | 关闭所属 Dialog，不保存为待恢复 UI |

管理器展示尝试失败时不计次，并释放 busy。已触发 `onShow` 后的关闭属于已展示。忙碌期间新的返回机会直接结束，不加入队列。

## 9. 设置入口与现有 Promise 封装

设置列表只调用项目设备工具和 deeplink 工具：`isHonorDevice()` 决定 item 可见性，点击调用 `openDeeplink(settingsAddUrl)`。这些名字表示需要对接的现有能力，不是假设仓库已经提供同名方法。不要调用停止函数，不走自动弹窗管理器。

如果已有 `startActivityForResult(...).thenAccept { ... }` 封装，先核对它的完成时机确实是页面返回，以及返回对象含 `resultCode` 和 `data`。把第 5 节的结果消费逻辑放入该回调即可；如回调不在主线程，切回主线程。无论用回调还是 Promise，仍需保留 ARouter 的 Activity/requestCode 启动关系与业务标记。

这次优先按已经确认存在的 ARouter 1.5.2 API 接入；不另写一套 Future 框架。正式编码时应在每个接入点注释“为什么设置 / 消费标记、哪一步清空、为何不改原业务结果”，而不是给每行语法补同义注释。
