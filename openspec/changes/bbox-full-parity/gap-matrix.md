# Gap Matrix — 12 份主规范 vs B-box UI 现状

> 批次 A 产出。状态以 `main` @ 批次 0.5（`a93a2a9a`）为时点；「批次」列指向 `docs/bbox-full-parity-plan.md`
> 的任务书编号。状态词：**wired**（新 UI 全等价）／**partial**（部分等价，缺口列明）／**kotlin-only**
>（能力在 Kotlin 侧保留、不经 Flutter）／**gated**（`Capabilities.kt` 为 false，B-box 独有，不在迁移范围）。

## app-widgets
| 需求 | 状态 | 批次 | 缺口 |
| --- | --- | --- | --- |
| Status widget | kotlin-only | L0 | 小部件保留 Kotlin；L0 仅改点击入口指向 BboxFlutterActivity |
| Toggle widget | kotlin-only | L0 | 同上（`Quick*Shortcut`/QS 磁贴为 L 依赖锚点，不可删） |
| Widget tap targets | partial | L0 | 通知/Phoenix/扫码仍落 MainActivity（审查遗留 10） |

## auto-selector
| 需求 | 状态 | 批次 | 缺口 |
| --- | --- | --- | --- |
| Automatic node selection | wired | — | AutoSelectorRuntime + `profiles.autoSelector*` 通道 |
| Candidate scope | wired | — | gids/组成员选择已入 auto_selector.dart |
| Manual pinning | wired | — | 选择/取消自动已入 UI |
| Selector state visibility | partial | I | AutoSelectorStatusActivity 旧页删除前状态推送要即时（候选缺口） |

## backup-restore
| 需求 | 状态 | 批次 | 缺口 |
| --- | --- | --- | --- |
| Backup container format | wired | — | BboxBackup `.thrbackup` |
| Partial backup selection | wired | — | scope=all/profiles |
| Restore validation | wired | — | strategy=Compatible + warnings 事件 |
| Cross-platform restore | wired | — | 桌面备份互认 |
| Auto-backup scheduling | partial | B | 自动备份调度在旧 UI 设置树，SettingsSchema 差集见 settings 审计清单 |

## connectivity-testing
| 需求 | 状态 | 批次 | 缺口 |
| --- | --- | --- | --- |
| Single profile URL test | wired | — | `groups.test` / 测速卡片 |
| Batch testing | partial | **F** | `TestSessionModel` 未包装：实时进度/直方图/最快连选为旧 `TestPanelController` 独有 |
| TCP ping testing | wired | — | 模式在 TestingPage 可配 |
| Result-driven sorting | wired | — | proxySort=Latency/Name |
| Test configuration | wired | — | TestingPage（testUrl/并发/超时） |

## diagnostics-logging
| 需求 | 状态 | 批次 | 缺口 |
| --- | --- | --- | --- |
| In-app log viewer | wired | — | logs.dart（CoreLog 缓冲 + 过滤） |
| Log export and sharing | wired | — | document.save；批次 0.5 已接 LogRedactor |
| Sensitive data redaction | wired | — | `redact: true` 走 LogRedactor（0.5 修复） |
| Crash and error reporting path | gated | — | reports=B-box 独有；Throne 侧 CrashHandler 保留 |

## i18n
| 需求 | 状态 | 批次 | 缺口 |
| --- | --- | --- | --- |
| 基准资源作为翻译唯一来源 | wired | — | Android 资源基线不因 UI 端口而变 |
| 语言目录条目完整覆盖基准 | wired | — | 既有 i18n 批次已闭合 |
| 翻译保持格式契约 | wired | — | i18n_coverage.py 门禁 |
| 覆盖率诊断脚本可静态校验 | wired | — | `uv run tools/diagnostics/i18n_coverage.py` |
| **（delta）Flutter 字符串 zh/en 覆盖** | partial | **K** | l10n.dart `_zh` map 需全量覆盖 UI key + CI 缺键校验；见 `specs/i18n/spec.md` delta |

## outbound-protocols
| 需求 | 状态 | 批次 | 缺口 |
| --- | --- | --- | --- |
| Supported protocol set | wired | — | 核心协议全集经 `profiles.*` 通道可达 |
| Type-specific settings contract | partial | **D** | 29 个 `*SettingsActivity` 的 typed 表单未入 Flutter（现靠手写 JSON/ProfileForm） |
| Config serialization | wired | — | SettingsMapper / profiles.save |
| Chained outbounds | wired | — | Chain 节点类型已可建 |
| Custom and raw configs | partial | **E1/E2** | checkConfig/manifest 已收口；schema 补全/校验编辑器（`ui/json/`）未移植 |

## profile-management
| 需求 | 状态 | 批次 | 缺口 |
| --- | --- | --- | --- |
| Profile persistence | wired | — | Room 经 bridge |
| Group model | partial | **C** | ProxyGroup 长尾字段（subOptions/排序/前落地代理/routeId 等）未暴露 |
| Subscription update | partial | **C** | 更新报告弹层（SubscriptionReportDialog）未入 UI |
| Import and share | wired | — | 扫码/剪贴板/文件/分享（0.5 修复多帧与包名校验） |
| Ordering and pinning | wired | — | ProfileOrderPage |
| Default group | wired | — | ensureDefault |

## proxy-connection
| 需求 | 状态 | 批次 | 缺口 |
| --- | --- | --- | --- |
| Connection state machine | wired | — | serviceState/transitioning |
| Foreground service notification | wired | — | PlatformNotifications |
| UI process binding | wired | — | ready/events 通道 |
| External control actions | wired | — | 磁贴/快捷方式/通知按钮 |
| VPN and proxy modes | wired | — | serviceMode + TUN 设置页 |

## repository-governance
| 需求 | 状态 | 批次 | 缺口 |
| --- | --- | --- | --- |
| OpenSpec 是当前规范的唯一入口 | wired | — | 本提案即流程产物 |
| 仓库目录职责明确 | wired | — | 0.5 补 `.gitattributes` |
| Python 工具使用 uv 文件工作流 | wired | — | settings_audit.py / scan_aar_apis.py 遵循 |
| 本地开发不依赖 Go 或 Android 编译环境 | wired | — | 构建在 CI |
| 核心由 CI 按 THRONE_CORE_REF 构建并缓存 | wired | — | throne-core action |
| 外部仓库由用户提供明确路径 | wired | — | — |
| 应用版本来源唯一 | wired | — | nb4a.properties |
| 发布与预览工作流分离 | wired | — | release.yml / preview.yml |

## routing-rules
| 需求 | 状态 | 批次 | 缺口 |
| --- | --- | --- | --- |
| Routing rule definition | wired | — | RuleEditorPage（custom/simple/endpoint_preferred_by） |
| Rule ordering | wired | — | 拖拽排序已入 `RuleOrderPage`（批次 H）；SSID/BSSID/网络类型/计费网络四条件在 RuleEditorPage 全量可达，network_type 为多选对话框 |
| Routing profiles | wired | — | routes.list/get/save/delete + 只读 raw + 恢复默认规则 |
| Rule set references | wired | — | `routes.ruleSets` 预设规则集选择器（`4100c426`） |

## settings-storage
| 需求 | 状态 | 批次 | 缺口 |
| --- | --- | --- | --- |
| Typed settings access | wired | — | ThroneSettings.set/get |
| Settings registry contract | partial | **B** | 三列差集未闭合（见 `uv run tools/diagnostics/settings_audit.py`：registry 15 / Key 15 / schema 无 UI 13 / UI 无 schema 4） |
| Per-profile overrides | partial | **C** | 组级 routeId/importSubRoute 覆盖未入 UI |
| Settings categories | wired | — | 10 个 Throne 设置页 |
| Restart-required hints | wired | — | 三态按需重启 + ready 跨 attach 徽标 |

## 汇总
| 批次 | 承载缺口 |
| --- | --- |
| B | settings 三列差集（settings-storage/backup 自动备份长尾） |
| C | 组设置与订阅报告（profile-management / settings-storage 覆盖）；已完成：`groupSettings.get/set` 桥（旧编辑器同款校验与 save 语义，restart 即时 reload）+ `GroupSettingsPage` 全字段表单 + 订阅报告事件流（弹层/snackbar/Apply 提示） |
| D | 协议 typed 表单 ×29（outbound-protocols）；首批 7 协议已完成：`nodeConfig` 桥（ProfileJson 规范 JSON）+ `updateNode` 模型校验保存门 + `ProtocolFormPage` schema 驱动表单（TLS/Transport/Mux/QUIC 共享块 + 各协议专有字段），长尾 22 协议待 5.3 |
| E1/E2 | JSON 引擎与编辑器（outbound-protocols custom/raw）；E1 已完成：六文件引擎 Dart 移植 + 真实 schema（schemagen 209KB 入 flutter/assets）+ 28 单测（0 error 0 warning）；E2 待做 |
| F | 批量测速面板（connectivity-testing）；已完成：`BboxTestSession` 薄包装（start/stop/dismiss/sort/remove + TestUiState 全量 wire）+ 400ms 可见性 feed + `TestPanelPage`（进度/直方图/排行/速度曲线/排序/移除/一键连最快） |
| G | WARP 注册（已完成：`warp.register` 桥 + ToS 一次确认 + WarpPage 生成行，capabilities warp=true；注销/状态查询上游不存在，`isGenerated()` 即状态） |
| H | 路由规则条件收口 + 拖拽排序（routing-rules） |
| I | logcat 页签 + 状态即时推送（diagnostics-logging / auto-selector）；已完成：BboxLogcat 流进程（-T 500 + threadtime 级别映射 + logcat -c 清空）+ 日志页 核心日志/Logcat 页签；首启引导与 WifiPermissionFlow 已入 BboxFlutterActivity；LiveGraphs 确认由 B-box 仪表盘曲线承载、随批次 L 退役 |
| J | TV 逐屏可用（无规范条款，验收为真机）；prev/next 已完成：`profiles.switchRelative` 桥（运行中广播热切换 / 停止态 neighbour 移动选择）+ tv 能力门控的 profiles 工具栏按钮 |
| K | Flutter zh/en 覆盖 + CI 缺键校验（i18n delta） |
| L | 旧 UI 退役 + 入口改指（app-widgets tap targets） |
