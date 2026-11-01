# Spec Delta

## Purpose

在 Android 资源国际化契约之上，定义 B-box Flutter UI 字符串层（`flutter/lib/l10n.dart`）的
zh/en 契约：UI 字符串 key 集合为唯一来源，中文映射全量覆盖，CI 静态校验缺键。（D3 决策：
仅 zh + en；旧 UI 22 语言不移植。）

## ADDED Requirements

### Requirement: Flutter 字符串 key 为 UI 文案唯一来源
Flutter UI 的全部用户可见字符串 MUST 以英文原文作为 key（`context.tr('<key>')`），MUST NOT
在控件构造处硬编码非 key 文案；插值 MUST 使用 `{name}` 占位与 `.replaceAll` 拼装，MUST NOT
以字符串拼接直接构造用户可见句子。

#### Scenario: 新增界面无硬编码文案
- **WHEN** 新增或修改任一 `flutter/lib/pages|components` 下的界面代码并检索非 `tr(...)` 的用户可见字符串
- **THEN** 不存在以字面量直接呈现的用户可见文案（装饰性符号与调试日志除外）

#### Scenario: 插值走占位符
- **WHEN** 某条 UI 文案需要变量（如条数、名称）
- **THEN** key 形如 `'Restore {count} profiles?'`，调用处以 `.replaceAll('{count}', ...)` 拼装

### Requirement: 中文映射全量覆盖
`flutter/lib/l10n.dart` 的 `_zh` 映射 MUST 覆盖全部 UI 字符串 key：每个 `context.tr` 的 key
MUST 在 `_zh` 中存在对应译文或被显式认定为「英文即终文」并登记豁免；术语 MUST 与旧
`values-zh/strings.xml` 同一概念保持同一译名（同一概念不得出现两套译名）。

#### Scenario: 中文环境无英文回退
- **WHEN** 系统语言为中文并逐屏走查全部 Flutter 页面
- **THEN** 所有用户可见文案为中文（显式豁免项除外），无英文回退

#### Scenario: 术语与旧 UI 一致
- **WHEN** 比对 `_zh` 中某概念（如「路由档」「恢复默认」）与旧 `values-zh/strings.xml` 对应条目
- **THEN** 同一概念使用同一译名

### Requirement: CI 缺键校验
仓库 CI MUST 包含 Flutter 字符串缺键校验：收集 `flutter/lib` 全部 `tr('<key>')` 的 key 集合，
与 `_zh` 映射键集合求差，任何缺键 MUST 使校验失败；校验 MUST 只读，不修改文件。

#### Scenario: 缺键被拦截
- **GIVEN** 新增了一个 `tr('New string')` 而 `_zh` 未登记
- **WHEN** CI 运行缺键校验步骤
- **THEN** 校验报告该缺键并以失败结束

#### Scenario: 全量覆盖通过
- **GIVEN** `_zh` 已覆盖全部 key（或显式豁免）
- **WHEN** CI 运行缺键校验步骤
- **THEN** 校验通过
