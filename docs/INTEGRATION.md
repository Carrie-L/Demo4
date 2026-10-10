# 正式项目接入

> **V1 历史实现说明。** 本文解释旧代码；最新需求和后续实现以 [IMPLEMENTATION_PLAN_V2.md](IMPLEMENTATION_PLAN_V2.md) 为准。V2 尚未编码；请勿继续采用旧版长期保留行为、到期补弹、AB 总计三次等规则。

完整架构、字段字典和设计取舍见 [系统设计文档](SYSTEM_DESIGN.md)。

## 生命周期和线程

进程内创建一个 `PersistentGuideRepository` 和一个 `WidgetGuideController`。Controller 由 Application 级容器持有，不要跟随某个 Fragment 重建。所有公开入口与 Host 回调从主线程调用；repository 内部会切换 IO。不要把 Activity 存入应用单例之外的长期静态字段；宿主销毁调用 `detach(host)`。

主页面提供 `HostSnapshot(hostId, version, surface, canPresent)`：

- `HOME_OTHER`：底部首页、非 AI 频道且业务页面真正可见。
- `HOME_AI`：底部首页、AI 快看频道且真正可见。
- `MINE`：我的页真正可见。
- `NONE`：所有其他业务页面。
- 前后台、页面遮盖、Tab/频道切换、宿主状态改变时更新快照并 `refresh()`。
- `version` 只在可见位置/有效性变化时递增，不能在 getter 中递增。
- 不能将本引导弹窗自身占用作为 `canPresent=false`，否则会立刻取消自身。
- 不要从 RecyclerView bind、布局重绘或普通 UI 更新上报页面进入。
- 系统时间/时区发生变化时调用 `refresh()`，重算冷却与下一次零点；返回前台时也重新计算。

## ARouter / 行为路径

`visible(PageRef)` 表示当前实际业务页面。`entered(NavigationEvent)` 表示目标页面已进入，包含唯一导航 ID、真实来源页面实例、目标页面实例及指定入口。目标页初次进入先上报 `entered`，再上报 `visible`。同一次导航不要每次 `onResume` 都生成新 ID。

示例：

```kotlin
// 首页已显示
controller.visible(homePage)

// 从搜索框发起路由时生成 navigationId、targetPageId，随 Intent 传到目标页。
// 搜索激活页实际进入时上报：
controller.entered(NavigationEvent(navigationId, homePage, activePage, EntryPoint.HOME_SEARCH_BOX))
controller.visible(activePage)

// 从该搜索激活页直接提交到结果页：
controller.entered(NavigationEvent(nextNavigationId, activePage, resultPage, EntryPoint.SEARCH_SUBMIT))
```

需要覆盖相关流程里的其他业务页面；如果漏报中途页面，无法证明搜索路径未绕行。ARouter 路由回调不等于目标页已可见；目标页面的实际进入负责完成行为。不要从不可信外部 deeplink 原样接受 `HOME_SEARCH_BUTTON` 等来源字段，入口和来源应由内部路由适配器生成并校验。

转场失败不要上报目标页 entered。返回已存在页面只上报 visible；返回搜索激活页前曾经过其他页面，旧链路已经失效。配置变化保留同一 PageRef ID；进程重启不恢复未完成链路。

## 统一弹窗管理器

`GuideHost.tryShow()` 是**同步接纳边界**，不是第二个排队入口：

1. 现有管理器忙时返回 false，不产生窗口、不回调 shown。
2. 管理器允许本功能参与展示时调用 controller.refresh()。
3. controller 再判断并写入 reservation，之后重新检查最新快照和配置。
4. tryShow 内再次校验 FragmentManager、宿主 lifecycle 及管理器名额；接纳后立即执行，不能延迟执行此前捕获的 show 闭包。
5. 第一次实际绘制回调 onShown，随后关闭回调 onClosed（恰好一次）。绘制后立刻被取消也要先回调 onShown。
6. cancel 必须真正取消已接纳但尚未显示的请求，并最终回调 onClosed；不能仅移除一个队列标记。

如现有管理器必须先排队，把“取得展示权”适配在 tryShow 之前；排队本身不占用本模块的展示次数。迁移时替换 `FragmentGuideHost`，核心规则不用改。

本仓库 DialogFragment 恢复时若没有活跃回调所有者，提前禁止创建窗口并移除旧 fragment。Activity 暂停会取消正在展示的引导；旋转后不会重复计数，也不会恢复旧弹窗闪现。

## SP / MMKV

SP 是默认可运行实现。仅一个 JSON key，禁止分别写次数/日期/行为。`commit(false)` 可能已更新 SP 的内存副本，所以 repository 即使发现内容相同也会重写，不能把读回相同值当作已落盘。

若正式项目已有 MMKV，实现 `StringStateStore` 即可：read 对应读取整个 JSON；write 对应 encode 单个 key，并按项目所用 MMKV 版本选择同步策略。必须将写入结果/同步异常传回，不要无条件返回 true。不要为了接这个功能引入第二个 MMKV 版本。本仓库没有增加 MMKV 依赖，也没有假设所有版本都有相同的持久化保证。

SP/MMKV 均不提供这里所需的跨进程读改写事务。本功能由主进程唯一实例管理；如果项目中的 provider/service 在其他进程上报事件，应先转发到主进程。

频控文件应排除自动备份：用户要求清除数据、卸载重装后重新计数。验证 app 已关闭备份；正式项目保留自己的备份策略，并单独排除此 SP 文件。

## 云配

接入 `GuideConfigSource.config: StateFlow<GuideConfig>`。`GuideConfigParser` 接受完整 JSON 中的 `browserWidgetGuideRules`。缺失、类型错误、负数、溢出均返回默认关闭。关闭配置允许没有间隔字段。

请求失败可以保留之前已验证的缓存；配置更新不能清除 GuideState。正式云配接口不在仓库内，因此验证工程只提供 debug source，Release 默认关闭。

## 添加页和内容

`GuideContentProvider` 注入 A/B 文案、截图、桌面/负一屏 deeplink；`WidgetGuideNavigator` 使用项目现有路由器打开地址，返回是否成功发起跳转。设置页直接使用同一 navigator。

不查询安装状态，不写 everAdded，不根据点击结果停止引导。生产截图、URL 和厂商负一屏添加实现需要由现有项目提供。

## 恢复和可观测性

Controller.status 用于开发状态面板；reportError 接入项目日志/监控。日志不包含搜索词、书签、资讯正文等用户内容，仅行为枚举、拒绝原因和展示 ID。

发生未知 schema、损坏数据、不可恢复的 reservation 时暂停自动引导，禁止静默清空历史。Debug 面板可明确重置测试数据；正式版本没有自动清空和重新计数入口。业务若未来选择不同崩溃恢复策略，须单独修改并验收，不可把 reservation 直接算作实际展示。
