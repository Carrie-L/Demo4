# Browser Widget Guide

> **最新需求：V2（2026-10-11），目前处于方案阶段，业务代码尚未实施。** 请先读 [完整实现方案 V2](docs/IMPLEMENTATION_PLAN_V2.md)：每轮新行为并原栈返回、A/B 各自最多三次、共享每日一次、点击添加停止对应类型。
>
> 下文“已实现规则”、运行步骤及旧测试均描述 V1，不能作为 V2 的行为或验收依据。本次仅提交设计文档和版本提示。

Kotlin + XML 小组件添加引导。`widget-guide` 是可迁移功能模块；`app` 是真实导航验证宿主，使用 ARouter。

## 设计与代码阅读

先读 [完整系统设计文档](docs/SYSTEM_DESIGN.md)，其中第 4 节逐项解释 NavigationEvent、PageRef、EntryPoint 和 ARouter 参数，第 7/8 节解释展示协调、计数与失败恢复。自写源码、测试、XML 和关键构建配置已补充中文注释。

## 构建

- JDK 17，Android SDK 35；Gradle Wrapper 8.11.1，AGP 8.9.2，Kotlin 2.1.20。
- Android Studio 打开根目录，配置本机 SDK 后运行 `app`。
- Windows：`gradlew.bat :app:assembleDebug :widget-guide:testDebugUnitTest :app:lintDebug :widget-guide:lintDebug`
- macOS / Linux：`./gradlew :app:assembleDebug :widget-guide:testDebugUnitTest :app:lintDebug :widget-guide:lintDebug`
- Debug applicationId 为 `com.carrie.demo4.debug`，与 Release 数据隔离。
- ARouter 1.5.2 的旧类型引用通过 Jetifier 转换，同时排除重复 support 依赖，由 AndroidX 提供对应实现。

## V1 已实现规则（非最新需求）

| 行为 | 合法路径 | 展示位置 |
|---|---|---|
| 首页搜索 | 首页搜索框 → 搜索激活 → 搜索结果；或首页搜索按钮 → 搜索结果 | 首页任意频道 |
| 我的工具 | 我的 → 书签 / 历史 / 下载 | 我的 |
| AI 快看 | 首页 AI 快看频道 → 资讯详情 | 首页 AI 快看频道 |

- 行为完成后持久保存。经过任意其他页面、重启、暂时被频控拦截，都不清除；展示后继续保留。
- 搜索结果出现前经过其他业务页面会使未完成链路失效；不能仅沿用 `fromHome=true`。
- A 的两个行为独立满足、共用 A 的展示历史；A/B 独立冷却，AB 本地自然日合计最多一次、总计最多三次。
- 第一次无冷却，展示一次后等待 `firstPopInterval × 24h`，展示两次后等待 `secondPopInterval × 24h`。
- 首页 AI 频道 B 优先；B 不满足时可评估 A。A 在首页频道的范围集中在 `GuidePolicy`。
- 云配默认关闭。关闭期间记录尚未满足的行为；累计三次后停止行为跟踪和到期检查。
- **按最终需求，不识别已添加状态。** 点击添加、打开 deeplink 都不会写“添加成功”或终止引导。
- 设置入口直接 deeplink，不受自动引导规则限制，不计数。
- 弹窗内容第一次绘制时才计数；队列/准备展示不计数，同一展示 ID 去重。

## 存储与失败策略

使用 SharedPreferences，整个状态是一个版本化 JSON 值。`commit()` 在 IO dispatcher 上执行并检查结果；单实例 repository 的 mutex 串行读改写。没有使用 DataStore。

`StringStateStore` 是 MMKV 替换边界。见 [迁移说明](docs/INTEGRATION.md)。禁止多个独立 repository 同时读改写同一个键，禁止直接由其他进程修改该文件。

展示前持久保存独立 reservation，展示后原子替换为展示记录。reservation 不计入三次：如果进程在这两个动作之间结束，下次启动暂停自动引导，防止无法确认的展示再次打扰用户。手动设置入口仍然可用。这个保守策略可能少弹一次或提前停止；不会伪造“已经展示”记录。明确拒绝/关闭前未绘制时会释放 reservation。

读取损坏、未知 schema、写入失败均不能当成空状态。已绘制但写入失败时停止新弹窗，后续 refresh 使用同一个 ID 重试。窗口和磁盘无法实现一个原子事务。

## 如何验证

1. 默认推荐频道。打开“开发验证面板”，开启 `enablePop`，间隔填 1 / 3，点击应用。
2. 搜索框 → 搜索激活 → 搜索结果 → 其他页面（可多次）→ 返回主页面：A 出现。
3. 当天切 AI 快看 → 资讯详情 → 返回：首页 AI 频道上的 B 被每日限制拦截，但行为仍保留。
4. 开发面板将测试时钟前进 24 小时后返回 AI 频道：B 可以展示；不需要再点资讯。
5. 用“清空引导状态”重新验证非法路径、我的页工具、独立冷却等。完整用例见 [验收清单](docs/ACCEPTANCE.md)。

开发面板只在 Debug 开放；配置和虚拟时间只在当前进程有效，行为/次数保存在 SP。修改测试时钟不会修改系统时间。虚拟时间展示后如需重新冷启动验证，应清空测试状态，避免持久记录相对真实时间处在未来。

## 正式迁移需要替换的资源/适配

- 两个现成小组件不在此仓库，本工程不重新实现它们。
- 当前图片是 XML 示意图，替换为真实组件截图、产品文案和三个正式 deeplink。
- 验证落地页仅显示收到的组件和添加目标，不伪造桌面/负一屏添加成功。
- 替换云配 source、主页面/频道可见状态、ARouter 页面事件接入和已有统一弹窗管理器适配。
- 不要把验证宿主的调试面板和测试时钟迁移进正式产品。

## 结构

| 路径 | 内容 |
|---|---|
| `widget-guide/.../core` | 纯 Kotlin 行为识别、状态与频控 |
| `widget-guide/.../storage` | SP 单值持久化、严格 schema 校验、MMKV 接口 |
| `widget-guide/.../runtime` | 事件串行协调、展示前复核、云配接口 |
| `widget-guide/.../ui` | XML DialogFragment 和宿主接口 |
| `app` | 首页/频道/我的/搜索/资讯/工具/设置验证页面与 ARouter |
| `widget-guide/src/test` | 规则、链路、调度竞态与存储测试 |

所有版本固定；不存在真实账号、生产接口地址、凭据或签名密钥。
