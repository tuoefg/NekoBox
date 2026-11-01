# Proposal

## Why

B-box 的 Flutter UI 已取代旧 Fragment 树成为主界面，但与 Throne 既有能力之间仍有结构性缺口：
协议编辑仍依赖手写 JSON（29 个 `*SettingsActivity` 的 typed 表单未迁移）、组设置长尾字段与订阅
变更报告不可达、批量测速面板缺失、设置三列（存储键 / 桥键 / UI 控件）未闭合、JSON 编辑器的
schema 补全与校验仍走原生 `ui/json/`。同时旧 UI 与新 UI 双轨并存，入口指向不一致。本变更按
`docs/bbox-full-parity-plan.md` 的批次任务书把迁移收口到「Throne 既有功能全绿」，并在终点退役
旧 UI（D4 决策）。批次 0.5（审查遗留清尾）与批次 A（审计建档）已在本变更窗口先行完成。

## What Changes

- **批次 B 设置收口**：按 `tools/diagnostics/settings_audit.py` 差集补齐 `SettingsSchema` 键与
  `throne.dart` 控件（含 NTP/MTU/ACL 长尾），每键 Apply 语义（None/Reload/AppRestart）与旧 UI 一致。
- **批次 C 订阅组管理**：桥新增 `groupSettings.get/set` 覆盖 ProxyGroup 长尾字段（subOptions、
  autoClearUnavailable、testSortBy/trafficSortBy/testItemsToShow、frontProxyId/landingProxyId、
  routeId/importSubRoute、sortMethod/sortDescending/displayOrder、skipAutoUpdate）；组设置表单带
  校验；订阅变更报告弹层等价 `SubscriptionReportDialog`。
- **批次 D 协议 typed 表单**：两小批覆盖 29 个协议 Activity 的字段语义（首批 7 大协议，二批长尾），
  保存走 `profiles.*` 既有通道，校验等价 `ValidationForm`。
- **批次 E1/E2 JSON 引擎与编辑器**：`ui/json/`（JsonTree/SchemaStore/SchemaValidator/
  SchemaCompletion/JsonFormatter/ProfileJson）移植为 Dart，编辑器接补全/校验/格式化；sing-box
  schema 资产同步进 `flutter/assets/`。
- **批次 F 批量测速面板**：包装（不重写）Kotlin `TestSessionModel` 为 `groups.testSession.*` 通道 +
  事件流；Dart 面板复用 `ui/line_chart.dart`。
- **批次 G WARP 管理**：桥接 `WarpRegistration.register`/注销/状态查询，完成后 `Capabilities.kt`
  `"warp" to true`。
- **批次 H 路由收口**：规则条件补 SSID/BSSID、网络类型、计费网络三类，规则列表拖拽排序。
- **批次 I 诊断补全**：logcat 通道（`LogcatFragment` tail-reader 模式）与 Flutter 日志页 logcat 页签。
- **批次 J TV**：`SWITCH_NEXT/SWITCH_PREVIOUS` 广播通道 + 逐屏 D-pad/focus 验证。
- **批次 K i18n（zh + en）**：`l10n.dart` `_zh` map 全量覆盖 UI 字符串，CI 缺键校验（见 i18n delta）。
- **批次 L 旧 UI 退役**：L0 入口改指（通知/Phoenix/扫码 → BboxFlutterActivity；QS 磁贴/小部件/
  快捷方式保留）；L1 删除 Fragment 树与 29 协议 Activity（逐组 `git grep` + CI）；L2 收尾发 3.0.0。
- **诊断工具**：`tools/diagnostics/settings_audit.py`（三列差集，批次 B 的验收门禁）与
  `tools/diagnostics/scan_aar_apis.py`（AAR 链 coroutines/stdlib API 引用扫描，已验证钉版无
  NoSuchMethodError 风险——mobile_scanner 35 处引用全部被 kotlin-stdlib 2.0.21 / coroutines 1.6.4
  覆盖，force 保留）。

## Capabilities

### New Capabilities
（无。）

### Modified Capabilities

- `i18n`：新增 Flutter 字符串层要求——`flutter/lib/l10n.dart` 的 key 集合为 UI 字符串唯一来源，
  `_zh` map 覆盖全部 key，CI 校验缺键（D3 决策：仅 zh + en，不做 22 语言移植）。见 `specs/i18n/spec.md`。
- `outbound-protocols`：明确 typed 表单 MUST 覆盖各协议 `*SettingsActivity` 的字段与校验语义
  （D1 决策：typed Flutter 表单，禁「只靠手写 JSON」）。
- `settings-storage`：三列（SettingsRegistry / SettingsSchema / throne.dart）差集 MUST 由
  `settings_audit.py` 闭合为 0（批次 B 验收）。
- `connectivity-testing`：批量测速 MUST 等价 `TestSessionModel` 的实时进度/直方图/排序/移除/连最快。
- `profile-management`：组设置字段与订阅变更报告 MUST 与旧 `GroupSettingsActivity` 逐字段等价。

## Impact

- **Flutter（Dart）**：新增协议表单（批次 D）、组设置页（C）、测速面板（F）、WARP 页（G）、
  logcat 页签（I）、JSON 引擎与编辑器（E1/E2）、l10n 收口（K）。
- **桥层（Kotlin）**：`groupSettings.*`、`groups.testSession.*`、`warp.*`、`logs.logcat.*` 通道；
  JSON 引擎移出 `ui/json/` 后进入 L 删除清单。
- **Android / 构建链**：schema 资产同步步骤（E1）；CI 加 l10n 缺键校验（K）；`nb4a.properties`
  版本按 §6 里程碑 bump（2.1.0 起，versionCode 必须高于上游同号）。
- **不涉及**：throne 核心（THRONE_CORE_REF 不动）、数据库 schema、小部件/磁贴/快捷方式（L 保留锚点）。
- **工具布局**：诊断脚本在 `tools/diagnostics/`，经 `uv run` 执行（repository-governance 合规）。
- **外部 API / 版本**：coroutines/stdlib API 基线为 Maven Central 的
  `kotlin-stdlib:2.0.21`、`kotlinx-coroutines-core-jvm:1.6.4`、`kotlinx-coroutines-android:1.6.4`
  （`scan_aar_apis.py` 自动取用）；移动扫描插件 `dev.steenbakker.mobile_scanner` 1.0（AAR 链实测）。
