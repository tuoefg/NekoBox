# throne-flclash-ui 未来发展规划（AI Agent 执行文档）

> 版本：v1.0（2026-11-03 定稿）。基线：`main` @ `364cda9`（**v3.0.11**，VERSION_CODE 124），CI 全绿，`releases/latest` = v3.0.11。
> 用途：全功能迁移（bbox-full-parity）已于 2026-11-01 完成、旧 UI 退役并归档（`openspec/history/bbox-full-parity.md`），本文档是**后迁移时代的接续规划**，任何 agent 接手时以本文为任务来源。执行前先 `git fetch`（代理断连用 `git -c http.proxy= fetch`）确认基线之后的提交；若前提已变，先做增量审计再动手。
> 姊妹文档：仓库内 `docs/bbox-full-parity-plan.md`（已完成的迁移规划，其 §2 硬性规则全部沿用）。
> 执行状态（2026-11-03）：W1/v3.0.12 ✅、v3.0.13 ✅、批次 M 的 M1/M2/M5 ✅、批次 N 的 N1–N4 ✅；本文档随仓库分发，批次 O 及里程碑表以 §4 为准。

---

## 1. 现状盘点（基线事实，执行时勿再重复审计）

### 1.1 已完成、勿重做

- **迁移收官**：批次 0.5→A→B→C→D×2→E1→E2→F→G→H→I→J→K→L0/L1/L2 全部落地（46 commits）；旧 Fragment 树与 29 个协议 Activity 已删；Flutter 是唯一界面（3.0.0 起）；openspec 提案已归档
- **v3.0.x 打磨线**（3.0.1–3.0.11）：协议 typed 表单 + 测速面板细化、国旗徽章（矢量 SVG）、logcat 通道（tail-reader、清屏不阻塞主线程）、日志导出反馈、设置行值展示、probe 会话生命周期修复（v3.0.11）
- **质量现状**：Flutter 221 测试全绿 + Kotlin 单测（备份/解析器/设置契约/发布说明）+ analyze 存量约 167 条 info（CI 不阻塞）；`tools/diagnostics/` 已有 `settings_audit.py`、`scan_aar_apis.py`、i18n 三件套
- **自更新链路已加固**：`UpdateChecker` 读 `throne-update.json`（name/size/sha256），`UpdateVerifier` 落盘后校验 sha256——不必重做，只需保持测试
- **能力开关现状**（`Capabilities.kt`）：true = routes/autoSelector/warp/connections/logs/groups/memory/proxyGroups/clashModes/perApp/backup/lanProxy；**false = 12 项 B-box 独有能力**（terminal/capture/remote/reports/stun/quality/endpoints/taildrop/perAppManaged/rootQuery/dnsObject）

### 1.2 已知的债与坑（含 2026-11-03 实录）

| # | 内容 | 证据 | 归属 |
|---|---|---|---|
| 1 | 依赖链 pin：coroutines force 1.6.4、stdlib 2.0.21 排除重编、全局 `-Xskip-metadata-version-check`（`buildSrc/Helpers.kt:80`）——Flutter AAR 调新版 API 会有运行时 NoSuchMethodError 风险，`scan_aar_apis.py` 结论需复查收窄 | `app/build.gradle.kts` | M |
| 2 | compileSdk 36 被 androidx.camera 1.6.1 绑架（targetSdk 仍 35） | `app/build.gradle.kts:55` | M |
| 3 | analyze 存量 167 条 info（多为 test 的 curly_braces / unnecessary_import） | `flutter analyze` | M |
| 4 | README 仍是上游 Throne 原文（捐赠链接指 throneproj、文档指 throneproj.github.io、命名混乱：仓库名 flclash / 应用名 Flrone / 包名 com.throneflclash） | `README.md` | M |
| 5 | docs 失实风险：`bbox-bridge.md`/`bbox-flutter.md` 在 0.5 修过一轮，3.0.x 又漂移了十余轮 | docs/ | M |
| 6 | 核心侧日志噪音：TUN lazy 握手下本地 app 提前 RST → `connection: report handshake success: go: connection reset by peer`（ERROR 级，reF1nd/sing-tun `stack_go_conn.go:51` errGoReset；核心 pin 上游不可修） | 本会话已定性 | N |
| 7 | 并行会话互踩：2026-11-03 两个 agent 会话同时改本仓库，一方 commit 落进另一方新建的分支 | AGENTS.md 已录教训 | 治理 |
| 8 | 验证盲区：所有发布只经 CI（单测/构建/lint），**真机从未系统走查**（TV 焦点、VPN 升级安装、备份恢复跨版本） | 无真机测试记录 | N |

---

## 2. 战略定位（先选路线，再排任务）

本仓库本质是 **throneproj/Throne 上游 + 全面重写的 Flutter UI** 的下游发行版。三条路线：

- **A. 跟随上游，UI 领先**（推荐，本规划默认）：核心钉 `THRONE_CORE_REF` 定期跟进，开发力量全部投在 UI/桥层/体验；上游的协议与配置面自动继承
- **B. 特化独立产品**：走向 Flrone 品牌（README/docs 命名统一、独立文档站），功能上补 B-box 独有能力形成差异
- **C. 反哺上游**：把 Flutter UI 贡献回 throneproj（工作量大、不可控，不作为目标；仅顺手提小修）

**执行立场：A 为主、B 为辅**——W2 先把身份与文档理顺（半个 B），W4 按 B-box 能力逐项评估补齐（B 的差异化），核心层永远保持「pin + 跟随」（A）。

---

## 3. 工作流（W1–W7）

> 每批节奏沿用旧规划 §2：一次一个最小自洽批次 → 完整实现 → push → CI 全绿 → 里程碑前双 agent 并行 code review（浅扫 + 专项）→ 修 ≥80 分项 → spec 同步（openspec MUST 条款）→ 发版。**批次内禁止混入其他批任务。**

### W1 · ~~v3.0.12 在途收尾~~ ✅ 已完成（2026-11-03，commit `41636a9`）

- BboxLogcat.kt 重写为可注入进程的 LogcatReader（Mutex 保护、带超时的 clear，JVM 可单测，新增 BboxLogcatTest 477 行）；logs.dart 导出 tri-state + 防重入 + 空列表禁用，logs_test 补三态/防重入用例；探针会话重构为 BboxProbeSession（close 取消在飞请求 + 释放资源），`respond` 补 CancellationException 语义（scope 已关/会话已关返回 "cancelled"）；routed_ip_probe 补 `_closed` 语义（close 等待延迟 open，防残留 session）；顺带 shape_badge 焦点环改为前景绘制（不透明子组件不再遮盖）
- Flutter 226 测试全绿（+5）、analyze 167 存量不变；CI run 41485297569 成功，v3.0.12 已发（prerelease=false，latest 指向正确）
- 遗留观察：`probeGet` 从 computeIfAbsent 自愈改为「缺 session = CancellationException」——依赖 Dart 侧 get 自开 session（`routed_ip_probe.get` 已保证）；若未来新增调用方必须沿用该约定（M5 通道审计脚本应加此校验）

### W2 · 批次 M：技术债与身份理顺（v3.1.0）

- **M1 依赖链收窄**：跑 `scan_aar_apis.py` 复核 Flutter AAR（3.47.9 工具链产物）对 coroutines/stdlib 的实际 API 引用；能解除 force/排除就解除，不能解除则在 docs 里写明风险边界与升级路径；`-Xskip-metadata-version-check` 目标删除
- **M2 camera 与 compileSdk**：评估 camera 1.6.1 降级到兼容 compileSdk 35 的版本（扫码是唯一用途），或接受 36 并记录原因
- **M3 analyze 清零**：一次性修掉 167 条 info（curly_braces/unnecessary_import 全部是机械修复），此后 CI 加 `--fatal-infos` 防回潮
- **M4 README/docs 重写**：README 讲清「这是什么（Throne 核心 + Flrone UI）／与上游关系／自更新与签名／安装升级」；去掉上游捐赠链接（保留上游致敬段）；`bbox-bridge.md`/`bbox-flutter.md` 对照 `Capabilities.kt` + 通道现状重写（通道方法以双端 grep 为准）；**本规划文档随此批入库 `docs/roadmap.md`**
- **M5 通道契约自动化**：新 `tools/diagnostics/channel_audit.py`——正则扫 Dart `box.call('…')/events.add({'type':…})` 与 Kotlin `when(call.method)/emit("…")`，双端差集为空才算过；进 CI（ci.yml 加一步）
- 验收：M5 脚本 0 差集；CI 带 --fatal-infos 全绿；README/docs 与实况一致

### W3 · 批次 N：稳定性与可观测（v3.2.0）

- **N1 核心日志分层显示**：Flutter 日志页加「噪音折叠」——对已知良性核心错误（handshake-reset 类）默认折叠为计数徽章（「已折叠 N 条连接重置噪音」），可展开可关闭；**不改核心、不删记录**，只改展示层（openspec diagnostics-logging spec 补 delta）
- **N2 本地崩溃自捕获**：默认关闭的「诊断日志」开关（对应 Capabilities.reports 语义的轻量自建，**不上传**）——Thread.uncaughtExceptionHandler + ANR 观测落盘 app 私有目录，日志页可查看、可随日志导出；修复后保留 7 天滑动窗口
- **N3 真机验证矩阵（制度化）**：写 `docs/device-checklist.md`——arm64 真机：新装首启 / 覆盖升级（3.0.x→new）/ 备份导出→恢复（含 WebDAV）/ 分应用代理 / TV 模拟器 D-pad 全屏走查 / 弱网切换。**每个里程碑发版前跑一轮**，结果记入 CHANGELOG 节尾（「已过设备清单 YYYY-MM-DD」）
- **N4 自更新端到端演练**：本地起静态服务托管伪造 throne-update.json 验证 size/sha256 校验拒绝路径（UpdateVerifier 已有实现，缺的是恶意/损坏包拒绝的自动化验证）
- 验收：N1 折叠生效可还原；N2 崩溃可复现可导出；N3 清单执行一轮并留档；N4 拒绝路径有测试

### W4 · 批次 O：首个 B-box 独有能力（v3.3.0，二选一）

按价值/风险/核心依赖三轴评估后**只做一项**：

| 候选 | 价值 | 依赖 | 建议 |
|---|---|---|---|
| `quality` 网络质量采样页 | 直观展示各节点长期质量 | 核心 clash API 数据已有（`BboxApi`）；主要是采样存储 + 图表 | **首选**——复用 `line_chart.dart` 与测速面板经验，无新安全面 |
| `capture` HTTP 捕获改写 | 高级调试利器 | 依赖 libbox 私有引擎，Throne 核心是否暴露**未证实** | 先做可行性侦察（grep Throne 核心 aar/api），证实前不立项 |
| `dnsObject` 裸 DNS 编辑器 | 高级用户刚需 | 纯 JSON 引擎已就绪（批次 E），加 schema 即可 | 备选——若 quality 侦察失败则上此项 |

**侦察结论（2026-11-03，quality 立项通过，实现留待下一轮）：**

- 核心 API 在：throneproj/Throne 的 libbox 暴露完整的 `NetworkQualityTest` 族（`experimental/libbox/networkquality.go`、`CommandClient.StartNetworkQualityTest(configURL, outboundTag, serial, maxRuntimeSeconds, http3, handler)`），与 B-box 用法逐参一致
- 参考实现齐：B-box 的 `NetworkQualityViewModel.kt`（状态机 + toWire 线格式：phase/idleLatencyMs/downloadCapacity/uploadCapacity/RPM+accuracy/isRunning/configURL/serial/http3/maxRuntime）与 `BoxTools.kt` 的 `quality.start` 分支（validate url → selectOutbound → setSerial → setHttp3 → setMaxRuntime → start）可直接翻译
- vendored 页面现成：`flutter/lib/pages/diagnostics.dart`（quality=true 分支）已消费 `quality` 事件流与 `quality.start/stop/confirm/dismiss`
- **实现路径（下一轮照此执行）**：quality 的取样必须跑在 :bg 进程（box 所在处）——照 `TestSessionClient` 模式：`ICoreService` 加 `startNetworkQuality(config, callback)` AIDL 方法 + bg 侧 libbox 会话 + 回调 marshal 到主进程 → `BboxQuality` 状态机（移植 ViewModel）→ ThroneBoxBridge 四个 when 分支转发并 `emit("quality", wire)` → `Capabilities.kt` `"quality" to true` → diagnostics 页从设置/工具页入口可达 → 通道审计自动覆盖新契约
- 工作量估计：AIDL + bg 实现 + 桥接 + 状态机移植 ≈ 一个完整批次（半天），含测试与评审

流程：openspec 提案（proposal + tasks + spec delta）→ 桥接层 → Flutter 页 → `Capabilities.kt` 置 true → 设备清单过 N3。其余 false 项（terminal/taildrop/endpoints/remote/stun/perAppManaged/rootQuery）**明确不做**（重依赖或低价值），除非用户点名。

### W5 · 上游跟踪（常驻，不占版本号）

- 每月核对 `throneproj/Throne` 新 release/commit：安全修复与核心 bugfix **及时 pin 升级**；升级动作 = 改 `THRONE_CORE_REF` → CI 重建 AAR（缓存失效）→ 全量测试 + N3 设备清单 → 单独发 patch 版
- 关注上游破坏面：sing-box option schema（影响 JSON 引擎资产与 typed 表单字段）、clash API 形状（影响 dashboard/connections）、`THRONE_CORE_SCRIPT_SHA`（build_android.sh 变更使缓存键失效属正常）
- 上游若有 Flutter 化动向，评估 C 路线成本后再动

### W6 · 分发与合规（随 v4.0 评估）

- targetSdk 35→36 窗口（Android 16 要求）随上游/依赖就绪后走单独批次
- F-Droid / Play 上架评估：签名密钥管理（现为 GitHub secrets 的独立密钥）、隐私政策页（VPN 应用清单要求）、数据安全表单——**仅在用户提出上架需求时启动**
- APK 体积画像与预算：Flutter AAR + 多 ABI split 已就绪，补「每版体积记录进 CHANGELOG」的轻量惯例即可

### W7 · 治理与流程（常驻）

- **并行会话纪律**（11-03 事故固化）：同一工作区只允许一个 agent 会话执行写操作；第二会话只读或等待；操作前 `git branch -vv` + 文件 mtime 检查；提交只 add 自己的文件；main 前进用 `git branch -f` 不 checkout
- 删除类改动（退役代码、删分支、删 release）一律 `git grep` 逐引用核对 + CI 验证（仓库已有三次删错事故先例）
- 每里程碑双 agent code review 固化；review 发现 <80 分但 ≥75 的项必须在 CHANGELOG 或 issues 里留痕（不许静默丢弃）
- renovate/dependabot 评估：Kotlin 依赖可自动，Flutter 侧注意 pubspec.lock 必须保持 pub.dev（镜像污染已有三次）

---

## 4. 里程碑与发版

| 版本 | 内容 | 前置 | 发布动作 |
|---|---|---|---|
| **v3.0.12** | ✅ W1 日志轮收尾（2026-11-03 发，commit `41636a9`） | — | 已发，latest 已指向 |
| **v3.1.0** | 批次 M（依赖收窄 + analyze 清零 + README/docs + 通道审计进 CI） | W1 合入 | 同上 + 双 agent review |
| **v3.2.0** | 批次 N（噪音折叠 + 本地崩溃自捕获 + 设备清单首跑 + 自更新演练） | M5 脚本在 CI | 同上 |
| **v3.3.0** | 批次 O（quality 或 dnsObject，二选一） | 可行性侦察结论 | 同上 |
| **v4.0.0** | 核心大版本跟随 + targetSdk 36 + 上游 schema 适配 | 上游 release 节奏 | 单独评审轮 |
| 常驻 | W5 上游 pin 升级（每个安全修复） | 无 | patch 版 |

发布前固定动作：CHANGELOG 节存在性（release_notes.sh 会拦）→ tag 查重（`gh api .../releases/tags/vX.Y.Z`）→ dispatch → 核对 prerelease 标志 / assets / latest。

---

## 5. 风险清单

1. **上游破坏性演进**（最高）：schema/option/clash API 变更会同时打穿 JSON 引擎、typed 表单、dashboard——缓解：M5 审计脚本 + 每次 pin 升级全量回归；schema 资产随 CI throne-core 步骤自动再生成是既有安全网
2. **AAR 依赖链隐形升级**：Flutter 工具链升级（3.47.9→未来）可能引入 coroutines/stdlib 新 API 引用，配合 force pin 出运行时崩溃——缓解：M1 收窄结论 + 升级 Flutter 时必跑 N3 设备清单
3. **删除类事故**：历史三次（删错 Kotlin 分支、checkout 丢挂载、pubspec.lock 污染）——缓解：W7 治理三条
4. **单维护者 + agent 流水线的吞吐瓶颈**：CI 一轮 ~12 分钟，批次滚动依赖结果——缓解：批次间并行做文档/侦察类只读工作
5. **验证盲区**：CI 只能证「构建得出、测试过」，证不了「真机好用」——缓解：N3 制度化，不接受「CI 绿 = 可发版」的捷径

---

## 6. 进度跟踪约定

- 每批完成：openspec 立/勾对应提案（`openspec/changes/<批次号>-<主题>/`），提交说明带批次号前缀（如 `批次 M3:`）
- 本文档随执行修订：批次完成即更新 §4 里程碑表状态；与仓库实况冲突时以实况为准，先修订再执行
- 完成迁移归档后，本规划的历史使命结束时（预计 v4.0 后），按同格式写下一期规划并归档本文至 `openspec/history/`
