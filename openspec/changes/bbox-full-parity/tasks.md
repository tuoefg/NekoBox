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

- [x] 4.1 桥新增 `groupSettings.get/set`，覆盖 ProxyGroup 长尾字段（subOptions、autoClearUnavailable、testSortBy/trafficSortBy/testItemsToShow、frontProxyId/landingProxyId、routeId/importSubRoute、sortMethod/sortDescending/displayOrder、skipAutoUpdate）
  - `BboxProfiles.groupSettings(id)`：悬空前/落地代理按 dialog_edit_group.cpp:42-49 重置并落库；载荷含 candidates（排除 autoselector，`AutoSelectorProfiles.TYPE`）、routeChoices（含本订阅自有路由标记）、options 全字段（tlsVersion/httpVersion 为 null=跟随默认，respectServerInterval 三态 0/1/2）、defaults（RequestIdentity.resolve(null) 全局解析值 + subAutoUpdate/subRespectServerInterval）、serverIntervalHours。
  - `BboxProfiles.saveGroupSettings(values)`：服务端校验用旧 UI 同一套助手（`Validation.url`+`SettingChecks.HTTP/Empty.Required` 的 content:// 豁免、`SubscriptionNameFilter.error`、`SettingChecks.groupUpdateInterval`/`headerValue`，HWID 字段仅在发送时校验）；save 语义对齐 `GroupSettingsActivity.save()`——URL 变更重置 subMetadata/subLastUpdate/info、basic 组无 URL、pickedRoute≠initialRoute 才应用、importSubRoute 空 URL 强制 false；restartNeeded 计算同款，差异：**桥直接 reloadService()**（旧 UI 是回传重启标记由列表页提示，桥侧有 service.reload 通道故即时生效，台账记录该差异）。displayOrder 仅在 get/set 往返中保留，UI 编辑走既有 profiles.reorder。
- [x] 4.2 Dart `GroupSettingsPage` 表单校验等价旧 `FormIssue(Severity.Error)` 流；订阅变更报告弹层等价 `SubscriptionReportDialog`/`SubscribeFlows`
  - 新页 `pages/group_settings.dart`（profiles 页每行菜单「分组设置」入口，远端会话隐藏）：分区 常规/链式/路由配置/订阅/设备标识/更新后/名称过滤/排序与显示；URL 用 content:// 豁免 + http(s)+host 校验、名称过滤正则编译（caseSensitive:false + unicode 对齐 Pattern FLAGS）、间隔空/0 或 ≥30、header 值 <1000 字符单行；错误行标红 + 顶部错误条（FormPage.error）。HWID 字段按发送模式启用，placeholder 为全局解析默认值。
  - 订阅报告：`ThroneBoxBridge` attach 起 `SubscriptionClient.reports` 收集（close 取消），按旧 handle() 四分支拆事件——reload→applyRequired("Reload")（仪表盘出现 Apply 行）、error/status→`messages` snackbar（带分组名前缀）、popup→`subscriptionReports` 流；`main.dart` BoxApp 监听弹 AlertDialog（可选中文本 + Copy + OK，空文本显「无变更」）。
- [x] 4.3 本地静态校验 + 提交（kotlin/analyze/test）——括号配平/重复键/缺键扫描全过（本机无 SDK，编译待 CI）
- [x] 4.4 规范同步：`profile-management` 的组设置与订阅报告 delta 合入主规范（Group settings surface + Subscription change report 两条 Requirement）
- [ ] 4.5 CI/真机验证：`ci.yml` 全绿；真机场景为订阅更新后查看变更报告、组设置逐字段读写与校验拦截，回传 workflow 链接 + 截图

## 5. 批次 D：协议 typed 表单（两小批）

- [x] 5.1 首批 7 协议 typed 表单（Shadowsocks/VMess/VLESS/Trojan/TUIC/Hysteria2/WireGuard），字段来源各 `*SettingsActivity.kt` + `SettingsMapper`，保存走 `profiles.*`，校验等价 `ValidationForm`
  - 桥：`profiles.nodeConfig(id)` 返回节点规范 sing-box JSON（`ProfileJson.text(outbound)`，Dart 按路径读写）；`updateNode` 保存前跑 `Outbound.validate(issues, SettingsMapper.buildContext())`，Error 级 issue 以「字段: 消息」报错——**原生编辑器的保存门在桥侧生效**（JSON 编辑路径同样受益）。
  - 新页 `pages/protocol_form.dart`（代理卡片菜单「参数设置」入口）：schema 驱动的通用引擎 + 7 协议字段表（路径 = sing-box JSON 路径）。共享块对齐原生绑定：TLS（enabled/server_name/insecure/alpn/uTLS 指纹菜单联动 enabled/Reality/ECH；trojan/tuic/hysteria mustTls 保存时强制开启）、Transport（type 五选 + 按类型显隐，对齐 TransportBlock.setup）、Multiplex（状态菜单 keep/off/on/custom 派生自 JSON 对象形态 + mode 菜单 + brutal 子字段显隐，对齐 MuxBlock）、QUIC（tuic/hysteria）。专有字段：ss method 18 选项 + plugin/uot；vmess security/alter_id/packet_encoding/global_padding/authenticated_length；vless flow；tuic congestion_control(ccAlgorithms)/udp_relay_mode/udp_over_stream/zero_rtt/heartbeat；hysteria 版本菜单门控 v1(auth)/v2(password/obfs/带宽/ports/hop)；wireguard peer.* + address/private_key/mtu/system。
  - 校验等价：Dart 侧必填（server/port/各协议凭据）+ 端口范围 + 行内标红（FormIssue 流）；保存时 Kotlin 模型校验兜底；headers 按 Name: value 解析为 map。覆盖说明：长尾字段（tls 证书路径/fragment/伪装、xray-vless 流设置、wireguard amnezia）不在表单内，走既有原始 JSON 编辑器，台账如实记录。
- [ ] 5.2 CI/真机验证：`ci.yml` 全绿；真机逐协议 新建→保存→重开 回读一致，回传 workflow 链接 + 截图
- [x] 5.3 二批长尾 22 协议 typed 表单（AnyTLS/Binding/Chain/Custom/Direct/Http/Juicity/Masque/Mieru/Naive/OpenConnect/OpenVpn/Snell/ShadowTLS/Socks/SSH/TrustTunnel 等，清单见规划 §7.2）；E1 落地后长尾可先用其校验兜底
  - 新增 14 协议 schema（dial 块为扁平根键，DialFields 导出核实）：direct（dial 8 字段）、socks、http（headers map）、anytls、shadowtls、snell（version 6 分支的 mode/obfs_* 由核心严格校验）、naive（部分 TLS：server_name/certificate/fragment tri/ech）、juicity（mustTls 全块）、mieru（transport/multiplexing 选项对齐 mieruMultiplexing）、masque、ssh、trusttunnel、openconnect/openvpn（部分 TLS 块）。
  - 缓做两项并记录：**chain**（编辑器是跳板列表 UI，需 profile 选择器列表形态，非字段表单）、**custom**（其本体就是 raw 配置文本，NodeJsonPage/raw 编辑器即其表单）。累计 21 协议 typed 表单 + autoselector 专页 + chain/custom 缓做理由在案。
- [x] 5.4 规范同步：`outbound-protocols` 的 typed 表单覆盖要求 delta 合入主规范（Typed settings forms in the ported UI Requirement）
- [ ] 5.5 CI/真机验证：同上，抽查长尾协议各 2 个

## 6. 批次 E1：JSON 引擎移植

- [x] 6.1 Kotlin → Dart 移植 `ui/json/engine/`（JsonTree/SchemaDocument/SchemaStore/SchemaValidator/SchemaCompletion/JsonFormatter）+ JsonRelaxations/ProfileJson；行为以现有 Kotlin 单测为基准写 Dart 单测；sing-box schema 资产同步进 `flutter/assets/`
  - 产物 `flutter/lib/ui/json/engine/` 六文件逐行对齐 Kotlin 语义：JsonTree（宽容扫描器/注释/重复键取后/深度 96/span 保留；token→double 按 JVM 宽容度补 `1.`/`+.5` 分支）、JsonFormatter（严格数字正则**全串匹配**对齐 matches()、非严格走 formatNumber、quote 转义表同款）、SchemaDocument（JSON Pointer ~0/~1 解转义、deref 深 8、branchProperty/allOf 组合、sameNumber=qFuzzyCompare(a+1,b+1)、formatNumber/scalarText）、SchemaValidator（type/const/enum/pattern/minimum/maximum/items/required/additionalProperties/unevaluatedProperties+allOf/anyOf/oneOf 判别式收窄 4 轮、variantMismatch/VARIANT_PENALTY、unknownKeys 三态、issue 按偏移**稳定排序**）、SchemaCompletion（容错扫描 KEY/COLON/VALUE/AFTER_VALUE 状态机、注释/未闭合串/词捕获、判别式收窄+instanceScalar 类型别名、keySuggestions 模板链/valueSuggestions/choices/x-tag-reference 双源标签、filter 前缀排序）、SchemaStore（schema/sing-box.json 一次加载、UNSUPPORTED 三族变体剥离、多根 oneOf union）。JsonRelaxations/ProfileJson 表进 `ui/json/relaxations.dart`（Outbound 绑定部分留在桥侧）。
  - **schema 资产为 CI 生成物**：本地按 `.github/actions/throne-core` 同款流程生成——throneproj/Throne@36b6bc4（nb4a.properties 钉住）源码 + Go 1.27（go.mod 要求 ≥1.26，本机 1.25 不足；go.dev 重定向 SSL 失败改走阿里云镜像）+ `CGO_ENABLED=0 go run -tags "$TAGS,with_purego" ./cmd/schemagen`（TAGS 取自 script/build_android.sh）→ 209KB `flutter/assets/schema/sing-box.json`（pubspec 注册）。
  - 单测基准：仓库无 Kotlin 引擎单测（全仓仅 4 个无关 test），以 Kotlin 源码语义+桌面 C++ 注释为基准写 28 个 Dart 用例（flutter/test/json_engine_test.dart），**用真实生成的 schema 验证**：解析/spans/重复键/注释/尾逗号/深度、格式化精确输出、指针解转义、qFuzzyCompare、有效 shadowsocks 通过、错误变体命名（variantMismatch）、Throne 键放宽为警告、issue 源序、判别式先行（'type'）、type 值建议含 shadowsocks、前缀过滤、x-tag-reference 文档标签回流（my-direct）。修正的语义认知：schema 的 Outbound 分支只 require type（服务器/凭据由核心运行时查）；`1.50` 是严格数字原样保留；容错扫描中已闭合空串值后无建议（与 Kotlin 一致）；字符串上下文中对象联合无可选项。
- [x] 6.2 验收：Dart 引擎对同一输入与 Kotlin 输出一致（校验错误/补全/格式化）；CI 全绿
  - `flutter analyze` 0 error/0 warning（顺带修复批次 C/D 遗留的 6 处真编译错误：List+Section 语法、ListItem.invalid 参数、Kotlin ifEmpty、l10n 与双引号存量键的 4 处重复、被脚本截断的 jsonDecode 行、case 逗号模式）；28/28 测试过。
- [ ] 6.3 CI/真机验证：`ci.yml` 全绿（含 Dart 单测）；无真机项，原因为纯引擎层
  - ci.yml 已加 `flutter-tests` job（subosito/flutter-action@v2 + flutter pub get + flutter test），随下次推送首跑。

## 7. 批次 E2：编辑器 UI

- [x] 7.1 `components/code_editor.dart` 升级：补全浮层、错误标注、语法配色（JsonColors 进 palette 体系）、格式化、manifest 模板入口；接 `profiles.save`/`checkConfig` 组清单键白名单
  - `JsonCodeController`（TextEditingController.buildTextSpan）：引擎 span 着色（key=primary/string=tertiary/number=secondary/literal 斜体 secondary，替换 profile_editor 的正则分词器）；issue 波浪下划线按 span 相交叠加（error=colors.error、warning=0xfff08c00 桌面同款）；解析错误标注自身 span。
  - CodeEditor：可选 `completion`（150ms 防抖，容错扫描逐键上下文建议，底部停靠面板 label+detail+计数，tap 应用 insert/before/after + 选中区间）与 `issues`；`JsonIssuesStrip`（行:列 + 消息 + 严重级图标，tap 跳转 caret）。
  - profile_editor（组清单）：本地 JsonFormatter 格式化（config.format 桥调用退役）、config.check 白名单保留、Template 动作（空文档播种 {name,url,nodes:[]}）、解析错误入 strip。
- [x] 7.2 验收：Flutter 内完成 schema 校验 + 补全 + 格式化全流程；`ui/json/` 进入 L 可删清单
  - 新页 `pages/node_json_page.dart`（proxy_card「Edit」入口，替换原 _NodeEditor 对话框）：profiles.nodeConfig（桥新增 xray/endpoint 旗标）→ ProfileJsonTables.schemaRoots/relaxations（Xray 形态无 schema 优雅降级为纯语法）→ SchemaStore.get 单例；校验 300ms 防抖 + 补全面板 + 本地格式化 + nodeUpdate 保存（桥侧模型校验兜底）。存量 code_editor_test 迁移 JsonCodeController 过（4 用例）。
- [ ] 7.3 CI/真机验证：`ci.yml` 全绿；真机打开 raw 编辑器验证 补全/错误标注/格式化，回传截图

## 8. 批次 F：批量测速面板

- [x] 8.1 桥包装 Kotlin `TestSessionModel`（勿重写）：`groups.testSession.start/stop/poll` + 事件流（rows/ranked/removed、LatencyHistogram bins、Live 速度样本）
  - 新增 `BboxTestSession.kt`：对 `TestSessionClient` 的薄包装（模型零重写）——startGroup/startProfiles/startCurrent、stop、dismiss、sort（对齐 TestPanelController.sort：测速会话按 metric 记 testSortBy 后 BY_TEST_RESULT 降序，URL 按 BY_LATENCY）、remove（对齐 removeUnavailable：`batchDeleteProfiles(stopRunning=false)` + markRemoved，返回删除数）。`wire()` 把 `TestUiState` 全量整形给 Dart：rows（phase/latency/error/速度文本/country/ip/ok/failed/aborted）、histogram（EDGES+bins+failed+connectOnly）、ranking 前 10、speed live（stage/字节/延迟/服务器/样本曲线）、testingProgress、removed、失败原因枚举。
  - `ThroneBoxBridge`：start/stop/dismiss/sort/remove dispatch + `groups.testSession.visible` 打开 400ms 轮询 feed（等值去重，close 取消）——客户端模型 100ms 自发布，桥按面板可见性中继。
- [x] 8.2 Dart 面板（复用 `ui/line_chart.dart`）：实时进度/最快服务器/排序/移除不可用/一键连最快
  - 新页 `pages/test_panel.dart`（代理页菜单「批量测速」入口）：类型（延迟/IP 查询/测速）+ 范围（分组）选择、开始/停止；进度区（done/total、ok/failed/aborted、preparing/stopping、失败原因文案、线性进度条）；URL 会话显示延迟分布直方图（三色带 + 失败/仅连接计数）；测速会话实时卡（阶段/上下行速率/ping/服务器 + LineChart 样本曲线，ValueKey(profileId) 隔离换档）；排行区前 10（第一行「连接」按钮，全部行点击即 profiles.select 连接）；完成后菜单：排序 / 移除不可用（confirm 后批量删除 + snackbar）/ 收起。数据走 BoxData.testSession 订阅（可见性驱动的组测试会话 feed）。
  - 范围选择仅分组（旧面板的选中集/当前连接入口桥层已备 startProfiles/testCurrent，Dart 侧后续按需暴露）。
- [x] 8.3 规范同步：`connectivity-testing` 的批量测速等价要求 delta 合入主规范（Batch test panel Requirement）
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

- [x] 13.1 以 Flutter 全部 UI 字符串为 key 清单（en=原文），`l10n.dart` `_zh` map 全覆盖；术语与旧 `values-zh/strings.xml` 对齐
  - 直接字面量形式 `tr('...')` 的键全量入册（本轮补 21 键：Advanced/Authenticate/Not valid JSON/Template/TLS/Dial/路由规则编辑三键/Custom inbound 等 throne.dart 批次 B 遗漏 + routes.dart 规则排序/端点/终端数键）；动态透传（map 键回退、三元表达式）走运行时英文回退，不可静态枚举，测试注释在案；表单标签经 _Field/_Section 字面量扫描全覆盖（protocol_form 42 标签已补）。
- [x] 13.2 CI 加缺键校验步骤（入 android-build 或独立 step）
  - `flutter/test/i18n_coverage_test.dart`：扫描 lib/**.dart 的直接 tr 字面量 + _Field/_Section 标签，断言 _zh 全覆盖——flutter-tests job 的 `flutter test` 即跑，无需独立 step。
- [ ] 13.3 CI/真机验证：校验步骤 + `ci.yml` 全绿；真机切换中文抽查全部页面无英文回退，回传截图

## 14. 批次 L：旧 UI 退役（D4）

- [ ] 14.1 L0 改指：VPN 通知点击/ProcessPhoenix/configureIntent（SagerNet.kt、PlatformNotifications.kt、ktx/Utils.kt）→ BboxFlutterActivity；QS 磁贴、小部件、快捷方式保留（不可删）
- [ ] 14.2 CI/真机验证：`ci.yml` 全绿；真机验证通知点击/扫码落新 UI，回传截图
- [ ] 14.3 L1 删除（每删一组跑一次 `assembleOssDebug` CI）：Fragment 树全量、29 协议 Activity（前提 D）、`ui/json/` + JsonEditorActivity（前提 E）、AppListActivity/AppManagerActivity/WebDAVSettingsActivity/ScannerActivity/GroupSettingsActivity/AutoSelectorStatusActivity/FirstStartPrompts/WifiPermissionFlow/BackupFragment 等；每项 `git grep` 核对引用后再删
- [ ] 14.4 L2 收尾：MainActivity 保留为无 UI 壳；strings.xml 清理（Fragment 专属删、app 级留）；openspec 归档 + `openspec/history/bbox-ui-migration.md` 补录
- [ ] 14.5 CI/真机验证：`assembleOssRelease` 产物功能完好（通知/磁贴/小部件/快捷方式/备份导入全通），`ci.yml` 全绿；发 3.0.0（nb4a.properties bump + CHANGELOG + release.yml dispatch），回传 release 链接
