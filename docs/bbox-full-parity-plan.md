# throne-bbox-ui 全功能迁移执行规划（AI Agent 执行文档）

> 版本：v1.1（2026-11-01 增量审计修订）。基线：`main` @ `478c2ab6`（"Fix the audited defects and finish the route, DNS and restart gaps"），CI 全绿。
> 用途：任何 AI agent 接手执行迁移时，以本文档为唯一任务来源。执行前先 `git fetch` 确认基线之后的提交，若规划前提已变化（见 §6 各批「前置」），先做增量审计再动手。
>
> v1.1 修订记录（基线后提交 `4100c426` 增量审计，按「以仓库实况为准」修订）：
> - §4.1 补两条已完成（`4100c426`）：代理-配置联动（selectProfile 始终落位成员 + ProxiesPage 按 selectedProfileId 过滤）；规则编辑器 Rule set 行的预设规则集多选器（`routes.ruleSets`，routeprofiles srslist，≈2000 条，搜索/勾选/手输保留）。
> - §4.1 钉版说法更正：CI 的 Flutter 步骤实为 `channel: stable`（非钉版本号）；本批改为钉版 `3.47.9`（与本地构建工具链一致），原文「3.47.8」系笔误。
> - §5 批次 H 的「DnsServerSuggestions 补入 DNS 字段」已在 `478c2ab6` 完成（DnsPage 远程/直接 DNS 建议式对话框，历史自动记入）；批次 H 仅剩 SSID/BSSID、网络类型、计费网络三类规则条件。
> - §4.2 的 11 项审查遗留经 `4100c426` 后仍全部开放（该提交未触及任何一处），归属批次不变。

---

## 1. 项目速览

- **仓库**：`https://github.com/yuedaochangmendian/throne-bbox-ui`（fork 自 throneproj/ThroneForAndroid v2.0.4，单分支 `main`，无 PR 流程，直接推送）
- **架构**：ThroneForAndroid（Kotlin，sing-box 内核，包名 `io.nekohasekai.sagernet`）+ 新 Flutter UI（B-box / FlClash 0.9.2 风格，vendored 自 `giturass/B-box`）经 Kotlin 桥层通信：
  - `app/src/main/java/io/throne/bbox/bridge/`（14 文件，状态/动作层）+ `flutter/ThroneBoxBridge.kt`（通道宿主）+ `flutter/BboxFlutterActivity.kt`（启动器，FlutterFragmentActivity）
  - `bboxmodel/`：共享 Kotlin 数据类模块
  - `flutter/`：Flutter 模块（`pubspec` name=`b_box_ui`，`module.androidPackage: io.ericlee.bbox_ui`），以预编译 AAR（`io.ericlee.bbox_ui:flutter_release:1.0`）被宿主消费
  - 通道：`io.ericlee.bfa/box`（MethodChannel）+ `io.ericlee.bfa/events`（EventChannel，diff 推送）
  - 能力开关单一来源：`app/src/main/java/io/throne/bbox/bridge/Capabilities.kt`（false 项 = B-box 独有、Throne 无对应物：terminal/capture/remote/reports/stun/quality/endpoints/taildrop/perAppManaged/rootQuery/dnsObject；**迁移范围 = 让 Throne 既有功能全绿，不实现 B-box 独有项**）
- **规格与治理**：`openspec/`（spec-driven）。当前规范 `openspec/specs/`（app-widgets, auto-selector, backup-restore, connectivity-testing, diagnostics-logging, i18n, outbound-protocols, profile-management, proxy-connection, repository-governance, routing-rules, settings-storage）；历史 `openspec/history/`；变更流程 `openspec/changes/`


## 3. 决策记录（已锁定，不再开放）

| # | 决策 | 结论 | 影响 |
|---|---|---|---|
| D1 | 协议表单策略 | **typed Flutter 表单** | 批次 D 按协议逐个建 typed 表单，禁「只靠手写 JSON」 |
| D2 | JSON 编辑器 | **移植到 Flutter** | 批次 E 拆 E1（引擎移植）+ E2（编辑器 UI），不采用原生深链长期方案 |
| D3 | i18n 范围 | **仅 zh + en** | 批次 K 缩小；openspec i18n spec 需加 delta（随批次 A 提交） |
| D4 | 旧 UI 退役 | **彻底删除** | 批次 L 扩容，L0 改指前置 + 依赖排序的删除清单 |
| D5 | 版本节奏 | **独立版本线，里程碑一发** | 2.1.0 起按 §5 里程碑发 prerelease；versionCode 必须高于上游 Throne 同号版本以保覆盖安装（执行时核对 `nb4a.properties`） |

## 4. 当前状态基线（截至 `478c2ab6`）

### 4.1 已完成（勿重复做）

- CI 构建 Flutter AAR（`.github/actions/android-build` 含 Flutter 步骤，钉版 Flutter 3.47.9），`ci.yml` 全绿
- 路由 `routes.get` 的 `Sequence` 已 `.toList()`；`connections.close/closeAll`、memory 探针已 `Dispatchers.IO`
- `restartPending` 契约已实现（AppRestart 徽章由 `ready` 跨 attach 携带；reset 三态语义）
- 路由档 DNS 字段 presets + 输入历史；「Restore default rules」；routeprofiles 官方包按国家下载（`d6cd66f1`）
- 每个 Throne 设置页（inbound/TUN/DNS/routing/WARP/core/testing/presets/subscriptions/updates）Restore defaults（`d6cd66f1`）
- 配置文档语义锁定为组清单（name/url/autoUpdate/updateIntervalMinutes/nodes），`checkConfig` 拒绝 dns/route 键（`5ee5dbe5`）
- 代理-配置联动：配置卡片选择真正落位（`selectProfile` 选成员 + 热切换），代理界面按 `selectedProfileId` 只显示所选配置的节点（`4100c426`）
- 规则编辑器 Rule set 行的预设规则集多选器（`routes.ruleSets` 通道，routeprofiles srslist 目录，搜索/勾选/手输保留）（`4100c426`）

### 4.2 审查遗留项（本会话 code review 确认、HEAD 复核仍开放；行号为 `478c2ab6` 时点）

| 项 | 内容 | 位置 | 归属批次 |
|---|---|---|---|
| 1 | 分应用剪贴板导入无包名校验（URL 被存成假应用；旧 UI 有 `PackageNameInput.rejectInvalid` 语义） | `flutter/lib/pages/access.dart:112-116` | 0.5 |
| 2 | B-box 日志导出绕过 `LogRedactor`（旧路径对 core.log 脱敏 `moe/matsuri/nb4a/utils/LogExport.kt:49`，新导出无脱敏，core.log 含凭据） | `flutter/lib/pages/logs.dart`、`io/throne/bbox/bridge/BboxLogs.kt`、`ThroneBoxBridge` saveDocument | 0.5 |
| 3 | `apps.list`/`apps.icon` 在主线程做包管理扫描 + PNG 压缩 | `ThroneBoxBridge.kt:502-508`、`bridge/BboxApps.kt:51-87` | 0.5 |
| 4 | `pickDocument`/`pickSaveTarget` 单槽 handler，并发选择挂死首个 Dart future；无 `invokeOnCancellation` | `BboxFlutterActivity.kt:32-72` | 0.5 |
| 5 | QR 多帧：`decoded:0` 仍带 partial text，Dart 回退解析每帧误报错 | `flutter/lib/pages/profiles.dart:413-419` | 0.5 |
| 6 | docs 失实 ×4：`docs/bbox-flutter.md:123-128`「Not wired yet」（8 项已实现）、`docs/bbox-bridge.md:97-104`（3 条全假）、`:58` memory 行、`:59` LAN 行 | docs/ | 0.5 |
| 7 | flclash-manifest.json 来源缺口：35 个自写 Dart 文件不在任何分区 | `flutter/flclash-manifest.json` | 0.5 |
| 8 | `takeIntent`「只交付一次」契约未强制（`activity.intent` 不清空；`intentAvailable` 事件无人发） | `ThroneBoxBridge.kt` takeIntent、`flutter/lib/box.dart` | 0.5（接活或删契约） |
| 9 | 依赖 force 降级（coroutines 1.6.4 / stdlib 2.0.21）+ 全局 `-Xskip-metadata-version-check`：AAR 若调新版 API 运行时 NoSuchMethodError，未验证 | `app/build.gradle.kts` resolutionStrategy、`buildSrc/Helpers.kt` | A（静态扫 AAR API 引用后收窄或解除） |
| 10 | 旧 UI 入口改指（通知/Phoenix/扫码落 MainActivity） | `SagerNet.kt` configureIntent、`utils/PlatformNotifications.kt:38`、`ktx/Utils.kt:189`、`ui/ScannerActivity.kt:162` | **L0**（非 0.5） |
| 11 | shell-intent extras 死代码（无 producer、`onNewIntent` 不处理） | `MainActivity.kt` EXTRA_OPEN_* | **E2 后激活或删**（D2=移植后倾向删除） |

已排除（勿报）：路由档被订阅更新覆盖（上游既有行为，#105）；`bbox-flutter.md`「189 Dart files」（按模块范围准确）。

## 5. 批次任务书

> 每批执行节奏：实现 → push → `gh run list` 盯 CI 全绿 → 失败迭代 → 里程碑前并行 code review → spec 同步。批次内禁止混入其他批任务。

### 批次 0.5 — 审查遗留清尾（小改一批）
- **前置**：无。
- **任务**：§4.2 表 1–8 项全修（第 6 项 docs 重写以 `Capabilities.kt` 为准）；⑨ 加 `.gitattributes`（`* text=auto` + 源码/资源 `eol=lf`，先确认不引起整仓库重写 diff，必要时 `git add --renormalize` 单独提交）。
- **验收**：审查清单闭合；CI 绿。

### 批次 A — 审计建档
- **前置**：0.5。
- **任务**：① 按 §7 参考数据逐份过 12 份主规范，产出差距矩阵；② 写 `tools/diagnostics/settings_audit.py`（uv run）：`SettingsRegistry.kt` 全键 vs `io/throne/bbox/bridge/SettingsSchema.kt` vs `flutter/lib/pages/throne.dart` 消费，三列差集；③ 静态扫 Flutter AAR 链对 coroutines/stdlib 的 API 引用（验证遗留项 9，产出收窄/解除 force 的结论）；④ 差距矩阵 + i18n spec delta（覆盖要求改 zh/en）立为 openspec 提案 `openspec/changes/bbox-full-parity/`（proposal + tasks + spec deltas）。
- **验收**：提案入库；settings 审计输出 0/差集清单。

### 批次 B — 设置收口
- **前置**：A。
- **任务**：审计脚本差集 → 补 `SettingsSchema` 缺失键与 `throne.dart` 对应控件（重点：NTP/MTU/ACL 类长尾、每键 Apply 语义 Reload/AppRestart 与旧 UI 一致）；页内分组对齐旧 `SettingsFragment` 树。
- **验收**：脚本 0 差集；每屏与旧 UI 等价。

### 批次 C — 订阅组管理
- **前置**：A。
- **任务**：桥新增 `groupSettings.get/set`，覆盖 `ProxyGroup` 桥未暴露字段（见 §7.1，重点 `subOptions`(SubscriptionOptions)、`autoClearUnavailable`、`testSortBy/trafficSortBy/testItemsToShow`、`frontProxyId/landingProxyId`、`routeId/importSubRoute`、`sortMethod/sortDescending/displayOrder`、`skipAutoUpdate`）；Dart `GroupSettingsPage` 带表单校验（等价旧 `FormIssue(Severity.Error)` 流）；订阅变更报告弹层（等价 `SubscriptionReportDialog`/`SubscribeFlows` 报告）。
- **验收**：旧 `GroupSettingsActivity` 每字段在新 UI 可读写且校验等价。

### 批次 D — 协议 typed 表单（两小批）
- **前置**：A；E1 完成后长尾协议可先用其校验兜底。
- **任务**：首批 7 协议 Shadowsocks/VMess(StandardV2Ray)/VLESS(Xray)/Trojan/TUIC/Hysteria(2)/WireGuard；二批长尾 AnyTLS/Binding/Chain/Custom/Direct/Http/Juicity/Masque/Mieru/Naive/OpenConnect/OpenVpn/Snell/ShadowTLS/Socks/SSH/TrustTunnel（清单见 §7.2）。字段来源 = 各 `*SettingsActivity.kt` + `SettingsMapper`；保存走 `profiles.*` 既有通道；表单校验等价 `ValidationForm`。
- **验收**：每个协议新建/编辑全程不依赖手写 JSON。

### 批次 E1 — JSON 引擎移植（D2 决策）
- **前置**：A。
- **任务**：Kotlin → Dart 移植 `ui/json/`：`engine/JsonTree`（增量解析/树模型）、`engine/SchemaDocument`+`SchemaStore`（schema 加载）、`engine/SchemaValidator`、`engine/SchemaCompletion`、`engine/JsonFormatter`、`JsonRelaxations`、`ProfileJson`。sing-box schema JSON 资产进 `flutter/assets/`（CI 的 throne-core 步骤已产出 schema，加同步步骤）。引擎行为以现有 Kotlin 单测/行为为基准写 Dart 单测。
- **验收**：Dart 引擎对同一输入与 Kotlin 引擎输出一致（校验错误、补全项、格式化结果）。

### 批次 E2 — 编辑器 UI
- **前置**：E1。
- **任务**：`flutter/lib/components/code_editor.dart` 升级——补全浮层、错误标注、语法配色（`JsonColors` 映射进现有 palette/theme 体系）、格式化、manifest 模板入口；接 `profiles.save`/`checkConfig` 既有语义（组清单键白名单）。
- **验收**：Flutter 内完成 schema 校验 + 补全 + 格式化全流程；`ui/json/` 进入批次 L 可删清单。

### 批次 F — 批量测速面板
- **前置**：A。
- **任务**：桥**包装** Kotlin `ui/test/TestSessionModel`（勿重写）：新增 `groups.testSession.*`（start/stop/poll）+ 事件流携带 rows/ranked/removed、`LatencyHistogram` bins 数组、Live 速度样本；Dart 面板（复用 `ui/line_chart.dart` 画直方图）：实时进度/最快服务器/排序/移除不可用/一键连最快。
- **验收**：旧 `TestPanelController` 交互逐项等价。

### 批次 G — WARP 管理
- **前置**：A。
- **任务**：桥接 `bg/WarpRegistration.register(tunnelType, proxy, apiHosts)`（+ 注销/状态查询，读 `WarpRegistration.kt` 全 API）；Dart WARP 页（注册表单 + 流程）；路由层 `OutboundIds.WARP_BYPASS`（"warp-bypass"）已在 `BboxRoutes` 映射，无需改；完成后 `Capabilities.kt` `"warp" to true`。
- **验收**：注册端到端可用 + WARP 模式可路由。

### 批次 H — 路由收口（剩余小头）
- **前置**：A。
- **任务**：routes.dart 支持 SSID/BSSID、网络类型、计费网络三类规则条件（旧 `RouteFragment` 条件集）；规则列表拖拽排序。
  （「DnsServerSuggestions 补入 DNS 字段」已于 `478c2ab6` 完成，见 v1.1 修订记录。）
- **验收**：旧 RouteFragment 条件全支持。

### 批次 I — 诊断补全
- **前置**：A。
- **任务**：logcat 通道——复用 `LogcatFragment` 的 tail-reader 模式（IO 线程 `reader.poll()` + commit 推进读位置，取消安全），桥新增 `logs.logcat.visible/clear` 事件流；Flutter 日志页加 logcat 页签；LiveGraphs 与 Flutter `line_chart` 对齐确认；首启引导/`WifiPermissionFlow` 移植评估（如 Flutter 侧已覆盖则记录关闭）。
- **验收**：设备日志在新 UI 可看可清。

### 批次 J — TV
- **前置**：B–H 完成。
- **任务**：prev/next 服务器（桥发 `Action.SWITCH_NEXT/SWITCH_PREVIOUS` 广播，同 `TvControls.switchServer`）；逐屏 D-pad/focus 验证（vendored `flutter/lib/ui/focus_*.dart` 基础）；TV 横幅已迁无需动。
- **验收**：TV 模拟器/真机全流程可操作。

### 批次 K — i18n（zh + en）
- **前置**：B–I 文案冻结。
- **任务**：以 Flutter 全部 UI 字符串为 key 清单（en=原文）；补齐 `flutter/lib/l10n.dart` `_zh` map 覆盖率；术语与旧 `values-zh-rCN/strings.xml` 对齐（同一概念不出现两套译名）；CI 加缺键校验（可入 android-build 或独立 step）。
- **验收**：zh map 覆盖全部 UI 字符串 + CI 校验绿。

### 批次 L — 旧 UI 彻底退役（D4 决策）
- **前置**：J、K 完成；差距矩阵全绿。
- **任务**：
  - **L0 改指（必须先做）**：VPN 通知点击/ProcessPhoenix/`configureIntent`（`SagerNet.kt`、`PlatformNotifications.kt`、`ktx/Utils.kt`）→ `BboxFlutterActivity`；QS 磁贴、小部件、快捷方式保留（Kotlin 侧独立，依赖 `ProfileSelectActivity`/`SwitchActivity`/`Quick*Shortcut`——**不可删**）。
  - **L1 删除（每删一组跑一次 `assembleOssDebug` CI）**：Fragment 树全量（Dashboard/Groups/Route/Settings/Tools/Logcat/LiveGraphs/About…）；29 个协议 `*SettingsActivity`（前提 D 完成）；`ui/json/` + `JsonEditorActivity`（前提 E 完成）；`AppListActivity`/`AppManagerActivity`/`WebDAVSettingsActivity`/`ScannerActivity`/`GroupSettingsActivity`/`AutoSelectorStatusActivity`/`FirstStartPrompts`/`WifiPermissionFlow`/`BackupFragment` 等；`git grep` 逐个核对引用后再删。
  - **L2 收尾**：保留 `MainActivity` 为无 UI 壳（通知/备份导入入口）；strings.xml 清理（Fragment 专属条目删，app 级留）；openspec 归档 + `openspec/history/bbox-ui-migration.md` 补录；发 **3.0.0**。
- **验收**：旧 Fragment 树与协议 Activity 全删、CI 绿、`assembleOssRelease` 产物功能完好。

## 6. 里程碑与发版

| 版本 | 内容 | 发布动作 |
|---|---|---|
| 2.1.0 | 批次 0.5 + A | `nb4a.properties` bump（versionCode > 上游同号）→ CHANGELOG 节 → `release.yml` dispatch → 默认 prerelease |
| 2.2.0 | B + C | 同上 |
| 2.3.0 | D（两小批可分 2.3.0/2.3.1） | 同上 |
| 2.4.0 | E1 + E2 | 同上 |
| 2.5.0 | F + G + H | 同上 |
| 2.6.0 | I + J + K | 同上 |
| 3.0.0 | L（旧 UI 退役） | 同上 |

发布前固定动作：并行 code review（双 agent）→ 修 ≥80 分项 → tag 查重 → dispatch → 核对 prerelease 标志/assets/latest。

## 7. 关键参考数据（执行时直接引用）

### 7.1 ProxyGroup 实体字段（`database/ProxyGroup.kt`，批次 C 范围）
`id, archive, skipAutoUpdate, name, url, info, subLastUpdate, frontProxyId, landingProxyId, columnWidthJson, scrollLastProfile, autoClearUnavailable, testSortBy, trafficSortBy, testItemsToShow, typeSortBy, subOptions(SubscriptionOptions JSON), subMetadata(SubUserInfo JSON), sortMethod, sortDescending, displayOrder, routeId, importSubRoute`；派生 `isSubscription`（url 非空）。桥 `groups.*` 现仅 list/select/test。

### 7.2 协议设置 Activity 清单（批次 D 范围，`ui/profile/`，29 个）
AnyTLS / AutoSelector / Binding / Chain / Custom / Direct / Http / Hysteria / Juicity / Masque / Mieru / Naive / OpenConnect / OpenVpn / ProfileSettings(通用) / SSH / ShadowTLS / Shadowsocks / Snell / Socks / StandardV2Ray / TrustTunnel / Tuic / WireGuard / XrayVless + 辅助（PreferenceBlocks / ProfileConfigExport / ProfileSettingsRouter / ProfileTextImport / ValidationForm）。

### 7.3 JSON 引擎清单（批次 E1 移植范围，`ui/json/`）
`JsonCodeEditor.kt, JsonColors.kt, JsonEditorActivity.kt, JsonLanguage.kt, JsonRelaxations.kt, ProfileJson.kt, engine/{JsonFormatter, JsonTree, SchemaCompletion, SchemaDocument, SchemaStore, SchemaValidator}.kt`。schema 资产经 `SchemaStore.ASSET`（app assets，CI throne-core 步骤产出）。

### 7.4 测速会话（批次 F 复用，`ui/test/`）
`TestSessionModel`：kind/testCurrent/startedAt/position(map)/rows(LinkedHashMap)/LatencyHistogram bins/countryCounts/ips/working(RankedResult)/names/removed/Live 速度样本/batched/setIds；配套 `TestPanelController`、`LatencyHistogramView`（自绘直方图）、`TestUiState`。

### 7.5 其他
- WARP：`bg/WarpRegistration.kt` `register(tunnelType, proxy, apiHosts): String`；路由层 `OutboundIds.WARP_BYPASS` → "warp-bypass"（`BboxRoutes.kt` 已映射）。
- logcat：`LogcatFragment` tail-reader 模式——IO 线程 `reader.poll()` 批读 + `commit` 推进位置，取消丢弃当批。
- TV：`TvControls.switchServer(step)` 发 `Action.SWITCH_NEXT/SWITCH_PREVIOUS` 广播。
- 小部件/磁贴/通知：`appwidget/{StatusWidgetProvider,ToggleWidgetProvider,WidgetActionReceiver,Widgets}`、QS tile、`QuickEnable/DisableShortcut`、`ProfileSelectActivity`、`SwitchActivity`——全部保留，是批次 L 的依赖锚点。
- Flutter l10n 现状：`flutter/lib/l10n.dart` 仅 `const _zh` 手写 map（en=原文兜底）；旧 UI 22 语言（values-ar/be/de/es/fa/fr/in/it…）仅作 zh 术语比对源，不做移植。
- vendored 边界：`flutter/flclash-manifest.json`（99 files + adaptedFiles，哈希可验）之外的自写 Dart = 迁移主战场；`flutter/lib/ui/*` vendored 不改（除非缺陷）。

## 8. 风险与已知坑

1. **批次 E 是最大单批**：E1 引擎移植先做，E2 UI 后做；E1 产出可提前为 D 长尾协议兜底校验。
2. **删除类事故模式**：本仓库历史多次「按行号删错份/checkout 丢挂载/漏引用」——L1 每组删除必须 `git grep` 核对 + CI 验证。
3. **flutter analyze/test 首次暴露存量问题**：属预期，迭代修复即可，不算回归。
4. **CI 时长**：Flutter AAR 构建约 +15 min；并行调度其他工作。
5. **pubspec.lock 镜像污染**（规则 7）、**换行符**（规则 6）——每次提交前自检。
6. **上游 Throne 仍在演进**：若需要同步上游，`git fetch upstream`（需配置 remote）后在批次边界进行，禁止批次中途合上游。

## 9. 进度跟踪约定

- 每批完成：在 openspec 提案 `bbox-full-parity` 的 tasks 清单勾选 + 提交说明引用批次号（如 `批次 C:`）。
- 审查遗留项（§4.2）随批次 0.5 闭合后，在新一轮 code review 中不应再出现同项。
- 本文档若与仓库实况冲突（基线后新提交），以仓库实况为准并先做增量审计修订本文档，再继续执行。
