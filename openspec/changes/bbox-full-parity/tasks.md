# Tasks

> 批次任务书全文见 `docs/bbox-full-parity-plan.md`；本清单是 openspec 侧的执行台账，批次边界与
> 规划一致。0.5 与 A 已在本变更窗口完成；B–L 逐批执行、逐批验证，不得跨批混改。

## 1. 批次 0.5：审查遗留清尾（已完成）

- [x] 1.1 修复审查遗留 1–5：access.dart 包名校验、日志导出接 LogRedactor、apps.list/icon 切 IO 线程、pickDocument 并发拒绝 + invokeOnCancellation、QR 多帧不再逐帧误报
- [x] 1.2 修复审查遗留 6–8：bbox-bridge.md / bbox-flutter.md 失实段按 Capabilities.kt 重写、flclash-manifest.json 新增 ownFiles 分区（35 个自写 Dart 文件）、takeIntent 单次交付 + intentAvailable 接活
- [x] 1.3 `.gitattributes` 钉换行（`* text=auto` + 源码资源 `eol=lf`），`git add --renormalize` 确认零重写
- [x] 1.4 CI 验证：`ci.yml` 全绿（run 41287674190，7m22s）；无真机项，原因为不改运行行为语义（导入拒绝/脱敏/线程为缺陷修复，回归由 185 测试覆盖）

## 2. 批次 A：审计建档（已完成）

- [x] 2.1 差距矩阵：逐份过 12 份主规范产出 `gap-matrix.md`（状态 wired/partial/kotlin-only/gated + 批次映射）
- [x] 2.2 `tools/diagnostics/settings_audit.py`（`uv run`）：SettingsRegistry + Key 常量 vs SettingsSchema vs Flutter 消费三列差集，运行时状态键入 NON_SETTING 册；当前输出 registry 15 / Key 15 / schema 无 UI 13 / UI 无 schema 4（批次 B 的工作清单）
- [x] 2.3 `tools/diagnostics/scan_aar_apis.py`（`uv run`）：AAR 链 coroutines/stdlib API 引用静态扫；结论——mobile_scanner 35 处引用全部被 kotlin-stdlib 2.0.21 / coroutines 1.6.4 覆盖（0 unbacked），force 降级无 NoSuchMethodError 风险，保留 force 并以扫描器作为 AAR 更新时的复验门禁
- [x] 2.4 立提案：本目录 proposal / tasks / gap-matrix / specs/i18n delta 入库
- [x] 2.5 CI 验证：纯文档 + 诊断脚本批次，`ci.yml` 全绿即可；无真机项，原因为零运行时改动

## 3. 批次 B：设置收口

- [x] 3.1 按 settings_audit.py 差集补 `SettingsSchema` 缺失键（registry 15 / Key 15 逐项甄别：真设置补入，运行时状态键移入 NON_SETTING 册并注明）与 `throne.dart` 对应控件（schema 无 UI 13 项），每键 Apply 语义（None/Reload/AppRestart）对齐旧 UI
  - 甄别结论：`inbound_address` 由 allowLan 别名覆盖（reset 同步落行）；`tls_spoof_default_on`、`use_dns_object`、`dns_object` 为真设置补入（旧 UI 的 DNS/custom inbound JSON 编辑器对应物）；`warp_amnezia_packets`/`warp_masque_ifc_addrs` 为注册生成态移入 NON_SETTING；主题/布局/日志字号/WebDAV 四键为 shell 自管（新增 SHELL_OWNED 册）；`appTLSVersion`/`notificationActions`/`show_config_security`/`xray_vless_preference`/`xray_geoip_url`/`xray_geosite_url` 为 restore-only（新增 RESTORE_ONLY 册）；`traffic_graph_*` 由 B-box 仪表盘取代归 SHELL_OWNED。
  - 控件：`SettingJson` 新原语（整串写入，区别于 SettingLines 的行列表）；Inbound 页 Custom inbound、DNS 页 Use the DNS object + DNS object 编辑器（开启时按旧 UI 语义收起结构化分区）、WARP 页 Cloudflare 许可开关、Presets 页 TLS spoof by default；`disablePrivateRangeBypass` 冗余条目删除（allowBypass 反相孪生已覆盖）。
- [x] 3.2 页内分组对齐旧 `SettingsFragment` 树；`uv run tools/diagnostics/settings_audit.py` 退出码 0（全差集闭合）
- [ ] 3.3 本地静态校验：flutter analyze 0 error、flutter test 全绿；kotlin 编译通过（本机无 Flutter/Android SDK，未执行；改动已人工逐行复查 + 括号配平 + py_syntax_check 通过，待 CI 验证）
- [x] 3.4 规范同步：`settings-storage` 相关 delta 合入主规范（三列差集闭合的契约表述）
- [ ] 3.5 CI/真机验证：推送触发 `ci.yml` 全绿；真机抽查 设置页 每屏与旧 UI 逐项等价（重点 NTP/MTU/ACL 长尾与恢复默认三态），回传证据为 workflow 链接 + 设置页对照截图

## 4. 批次 C：订阅组管理

- [ ] 4.1 桥新增 `groupSettings.get/set`，覆盖 ProxyGroup 长尾字段（subOptions、autoClearUnavailable、testSortBy/trafficSortBy/testItemsToShow、frontProxyId/landingProxyId、routeId/importSubRoute、sortMethod/sortDescending/displayOrder、skipAutoUpdate）
- [ ] 4.2 Dart `GroupSettingsPage` 表单校验等价旧 `FormIssue(Severity.Error)` 流；订阅变更报告弹层等价 `SubscriptionReportDialog`/`SubscribeFlows`
- [ ] 4.3 本地静态校验 + 提交（kotlin/analyze/test）
- [ ] 4.4 规范同步：`profile-management` 的组设置与订阅报告 delta 合入主规范
- [ ] 4.5 CI/真机验证：`ci.yml` 全绿；真机场景为订阅更新后查看变更报告、组设置逐字段读写与校验拦截，回传 workflow 链接 + 截图

## 5. 批次 D：协议 typed 表单（两小批）

- [ ] 5.1 首批 7 协议 typed 表单（Shadowsocks/VMess/VLESS/Trojan/TUIC/Hysteria2/WireGuard），字段来源各 `*SettingsActivity.kt` + `SettingsMapper`，保存走 `profiles.*`，校验等价 `ValidationForm`
- [ ] 5.2 CI/真机验证：`ci.yml` 全绿；真机逐协议 新建→保存→重开 回读一致，回传 workflow 链接 + 截图
- [ ] 5.3 二批长尾 22 协议 typed 表单（AnyTLS/Binding/Chain/Custom/Direct/Http/Juicity/Masque/Mieru/Naive/OpenConnect/OpenVpn/Snell/ShadowTLS/Socks/SSH/TrustTunnel 等，清单见规划 §7.2）；E1 落地后长尾可先用其校验兜底
- [ ] 5.4 规范同步：`outbound-protocols` 的 typed 表单覆盖要求 delta 合入主规范
- [ ] 5.5 CI/真机验证：同上，抽查长尾协议各 2 个

## 6. 批次 E1：JSON 引擎移植

- [ ] 6.1 Kotlin → Dart 移植 `ui/json/engine/`（JsonTree/SchemaDocument/SchemaStore/SchemaValidator/SchemaCompletion/JsonFormatter）+ JsonRelaxations/ProfileJson；行为以现有 Kotlin 单测为基准写 Dart 单测；sing-box schema 资产同步进 `flutter/assets/`
- [ ] 6.2 验收：Dart 引擎对同一输入与 Kotlin 输出一致（校验错误/补全/格式化）；CI 全绿
- [ ] 6.3 CI/真机验证：`ci.yml` 全绿（含 Dart 单测）；无真机项，原因为纯引擎层

## 7. 批次 E2：编辑器 UI

- [ ] 7.1 `components/code_editor.dart` 升级：补全浮层、错误标注、语法配色（JsonColors 进 palette 体系）、格式化、manifest 模板入口；接 `profiles.save`/`checkConfig` 组清单键白名单
- [ ] 7.2 验收：Flutter 内完成 schema 校验 + 补全 + 格式化全流程；`ui/json/` 进入 L 可删清单
- [ ] 7.3 CI/真机验证：`ci.yml` 全绿；真机打开 raw 编辑器验证 补全/错误标注/格式化，回传截图

## 8. 批次 F：批量测速面板

- [ ] 8.1 桥包装 Kotlin `TestSessionModel`（勿重写）：`groups.testSession.start/stop/poll` + 事件流（rows/ranked/removed、LatencyHistogram bins、Live 速度样本）
- [ ] 8.2 Dart 面板（复用 `ui/line_chart.dart`）：实时进度/最快服务器/排序/移除不可用/一键连最快
- [ ] 8.3 规范同步：`connectivity-testing` 的批量测速等价要求 delta 合入主规范
- [ ] 8.4 CI/真机验证：`ci.yml` 全绿；真机跑一次 20+ 节点批量测速与旧面板逐项对照，回传录屏/截图

## 9. 批次 G：WARP 管理

- [x] 9.1 桥接 `WarpRegistration.register(tunnelType, proxy, apiHosts)` + 注销 + 状态查询（读 `WarpRegistration.kt` 全 API）；Dart WARP 注册表单与流程
  - 落地形式与旧 UI 的差异甄别：旧 UI 注册流 = `WarpGenerate.confirmTerms`（ToS 一次确认持久化 `warp_tos_accepted`）→ `WarpClient.register(tunnelType)`（经 `CoreServiceClient` BIND_AUTO_CREATE，服务未运行也可注册）→ amnezia 模式追加 `generateCamouflage()` → `WarpSettingsScreen.fill()` 按模式写字段 → `reloadIfEnabled()`。注销与状态查询上游不存在（身份只存密钥材料，`isGenerated()` 即状态），不做发明。本批新增 `BboxWarp.register(tunnelType)`（IO 线程 register + amnezia packets + fill + reload 标志），`ThroneBoxBridge` 加 `warp.register` dispatch（respond 模式，注册后 emit settings 事件供 UI 回读），Dart WarpPage Registration 分区加 `_WarpRegisterRow`（ToS 确认框走房屋 `confirm` 助手，tunnelType 由 warpMode 推导：masque→masque 其余→wireguard，返回 `reload` 为真且运行中时 `service.reload`，`packetsError` 非空时提示，异常走 `box.report`）。
- [x] 9.2 完成后 `Capabilities.kt` `"warp" to true`；路由层 `OutboundIds.WARP_BYPASS` 已映射无需改
  - `Capabilities.kt` `"warp" to true` 已改（注释同步：warp.register 填字段、字段仍可编辑）；`OutboundIds.WARP_BYPASS` 批次 B/H 已映射，无需改动。
- [ ] 9.3 CI/真机验证：`ci.yml` 全绿；真机完成一次注册并以 warp-bypass 规则路由，回传截图

## 10. 批次 H：路由收口

- [x] 10.1 routes.dart 补 SSID/BSSID、网络类型、计费网络三类规则条件（旧 RouteFragment 条件集）；规则列表拖拽排序
  - 增量审计结论：三类条件在基线后提交中已进入 RuleEditorPage（`network_type`/`wifi_ssid`/`wifi_bssid` CSV 行 + `network_is_expensive` 开关，桥接经 RouteRule 反射字段透传），gap matrix 该行系 0.5 时点旧数据；本批补两处收口：`network_type` 由自由文本升级为旧 RouteRuleActivity 的多选对话框（wifi/cellular/ethernet/other），规则列表新增 `RuleOrderPage` 拖拽排序（复用 ProfileOrderPage 的 ReorderableListView 模式，经 `routes.saveRules` 保存，`ruleName`/`ruleSummary` 提为顶层函数两页复用，摘要补 network_type/wifi_ssid/wifi_bssid/metered 计数）。
- [ ] 10.2 CI/真机验证：`ci.yml` 全绿；真机建含三类条件的规则并验证命中，回传截图

## 11. 批次 I：诊断补全

- [x] 11.1 logcat 通道：复用 `LogcatFragment` tail-reader 模式（IO 线程 `reader.poll()` + commit 推进，取消安全），`logs.logcat.visible/clear` 事件流；Flutter 日志页加 logcat 页签
  - 新增 `BboxLogcat.kt`（bridge 包，与 `BboxLogs` 同构）：tail-reader 对 logcat 的等价物是 `logcat -v threadtime -T 500` 流进程（旧 UI 的 LogTail 是 RandomAccessFile poll/commit，管道无可 commit 的偏移，改为逐行读取 + 500ms 批量 flush，进程死亡自动重开，取消即 destroy 进程解除阻塞）；threadtime 优先级字母 V/D/I/W/E/F 映射到日志页已有的级别词表；`logcat -c` 即清空（buffer 清 + pendingReset）；条目容量 1500 同 BboxLogs，firstId 保留语义一致。
  - `ThroneBoxBridge` 加 `logs.logcat.visible`/`logs.logcat.clear` dispatch，事件走 `logcat` 通道；close() 补 `BboxLogcat.stop()`。
  - Dart：`BoxData`/`BoxAspect` 各加 `logcat`，`box.logcat` 缓冲与 `logs` 同款 reset/firstId 合并；日志页加 SegmentedControl（核心日志 / Logcat）页签，数据订阅随页签切换（BoxDataSubscription didUpdateWidget 已支持），暂停/搜索/级别过滤/导出（Logcat.log，同 redact）/清空全复用，logcat 页签 Default 级别 = 全部（无配置级别可跟随）。
- [x] 11.2 LiveGraphs 与 `line_chart` 对齐确认；首启引导/WifiPermissionFlow 移植评估（已覆盖则记录关闭）
  - LiveGraphs：**确认关闭**——对齐对象是 B-box UI，B-box 本就无独立流量图表屏，其仪表盘背景速率曲线（`line_chart.dart`，upload/download 30 样本）即同款功能；thronefa 的 LiveGraphsFragment（1/2/5/10 分钟窗口 + 代理/直连分线 + 图例开关）是旧 UI 专属，随批次 L 退役；`traffic_graph_*` 维持批次 B 的 SHELL_OWNED 册定论。
  - 首启引导：**已移植**——`FirstStartPrompts` 放宽为 `ComponentActivity`（MainActivity 路径原样保留：ThemedActivity snackbar + openSettingsScreen；其余宿主走 decorView Snackbar 无设置跳转），`BboxFlutterActivity.onCreate(savedInstanceState == null)` 触发（同 MainActivity 位置），13+ 通知权限 → 电池优化豁免各问一次的语义不变。
  - WifiPermissionFlow：**已移植**——该类本就只依赖 ComponentActivity，`BboxFlutterActivity` 持字段；`run()` 改返回 Boolean（并发调用被丢弃时调用方立即拿到 false 而非永久挂起，原有两处调用方不受影响）；触发点对齐 `RouteRuleActivity.finishWith`：保存含 wifi_ssid/wifi_bssid 的规则时先走一遍向导（`wifiPermissions.ensure` 桥方法，非阻塞、拒绝不影响保存），通知 deep-link（EXTRA_WIFI_PERMISSION）在 onCreate 同样生效。
- [ ] 11.3 CI/真机验证：`ci.yml` 全绿；真机查看并清空 logcat，回传截图

## 12. 批次 J：TV

- [x] 12.1 prev/next 服务器：桥发 `SWITCH_NEXT`/`SWITCH_PREVIOUS` 广播（同 `TvControls.switchServer`）
  - `BboxActions` 加 `switchServer(step)`（默认空实现，preview 不受影响）；`BboxCommands.switchServer` 按 TvControls 语义：运行中（canStop）发同款包内广播（BaseService.switchRelative 热切换），停止态 IO 线程 `ProfileOrder.neighbour` 移动选择 + postUpdate + scheduleRefresh。`ThroneBoxBridge` 加 `profiles.switchRelative` dispatch。
  - Dart：`Capabilities` 新增 `"tv" to SagerNet.isTv`（旧 UI 的 isTv 门控等价物），profiles 页工具栏在 tv 能力下加 上一/下一个服务器 两个 iconAction（chevronBack/chevronForward，>1 个配置才显示，远端/选择器模式不显示）；l10n zh 键按 values-zh-rCN 术语（上一个服务器/下一个服务器）。
- [ ] 12.2 逐屏 D-pad/focus 验证（vendored `ui/focus_*.dart` 基础）
- [ ] 12.3 CI/真机验证：`ci.yml` 全绿；TV 模拟器/真机全流程可操作，回传录屏

## 13. 批次 K：i18n（zh + en）

- [ ] 13.1 以 Flutter 全部 UI 字符串为 key 清单（en=原文），`l10n.dart` `_zh` map 全覆盖；术语与旧 `values-zh/strings.xml` 对齐
- [ ] 13.2 CI 加缺键校验步骤（入 android-build 或独立 step）
- [ ] 13.3 CI/真机验证：校验步骤 + `ci.yml` 全绿；真机切换中文抽查全部页面无英文回退，回传截图

## 14. 批次 L：旧 UI 退役（D4）

- [ ] 14.1 L0 改指：VPN 通知点击/ProcessPhoenix/configureIntent（SagerNet.kt、PlatformNotifications.kt、ktx/Utils.kt）→ BboxFlutterActivity；QS 磁贴、小部件、快捷方式保留（不可删）
- [ ] 14.2 CI/真机验证：`ci.yml` 全绿；真机验证通知点击/扫码落新 UI，回传截图
- [ ] 14.3 L1 删除（每删一组跑一次 `assembleOssDebug` CI）：Fragment 树全量、29 协议 Activity（前提 D）、`ui/json/` + JsonEditorActivity（前提 E）、AppListActivity/AppManagerActivity/WebDAVSettingsActivity/ScannerActivity/GroupSettingsActivity/AutoSelectorStatusActivity/FirstStartPrompts/WifiPermissionFlow/BackupFragment 等；每项 `git grep` 核对引用后再删
- [ ] 14.4 L2 收尾：MainActivity 保留为无 UI 壳；strings.xml 清理（Fragment 专属删、app 级留）；openspec 归档 + `openspec/history/bbox-ui-migration.md` 补录
- [ ] 14.5 CI/真机验证：`assembleOssRelease` 产物功能完好（通知/磁贴/小部件/快捷方式/备份导入全通），`ci.yml` 全绿；发 3.0.0（nb4a.properties bump + CHANGELOG + release.yml dispatch），回传 release 链接
