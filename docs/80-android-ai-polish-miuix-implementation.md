# 80 · AI 润色 Miuix 设置重构实施记录

日期：2026-11-21。依据：[79 · Android AI 润色 Miuix 设置重构任务](79-android-ai-polish-miuix-implementation-tasks.md)。本文只记录本轮实际实施、验证和剩余边界，不替代 73/73a 的协议与提示词权威说明。

## 1. 基线

- 分支 `main`；T0 基线提交 `7a8336c`。
- `miuix-ui-android`、`miuix-preference-android`、`miuix-core` 均为 `0.9.4`。
- 基线 JVM 测试 171 项通过。
- K40 截图位于 `docs/research/79-baseline/`，包括设置、发送策略、AI 设置、统计和快捷卡。

## 2. T1 设置首页与服务

- AI 服务卡显示厂商 Logo、配置名、模型和当前配置的连接测试状态。
- 增加测试连接、管理服务、模型价格入口。
- 零配置显示添加服务；自动润色开启前显示上传说明确认。
- 服务编辑把端点、协议、API 版本收进高级区；常用区只保留名称、厂商、Key、模型。
- 保存/测试状态按当前配置绑定，字段变化后旧测试状态失效。
- 写失败显示重新载入已保存服务入口。

## 3. T2 价格与 USD/CNY

- 模型候选显示输入/输出参考价；服务卡显示当前模型价格摘要。
- 价格详情显示输入、输出、缓存读、缓存写、上下文档位、时段、目录版本。
- 新增 `PolishExchangeRateClient`：Coinbase `GET /v2/exchange-rates?currency=USD`，只解析 CNY，校验正值和有限性，60 秒缓存。
- 统计页增加 USD/CNY 分段；CNY 使用同一汇率状态，显示来源与本机获取时间。
- K40 实测 CNY：汇率 `6.6691 @ 00:28`，总费用与明细均换算显示。

## 4. T3 调用统计

- 顶部“今日 / 7 天 / 30 天”分段，默认 30 天。
- 指标卡显示调用数、Token、中位耗时；费用显示估算、上报、未知。
- 明细按日分组；行可展开请求模型、响应模型、缓存读写、推理 Token 和价格来源。
- 筛选面板支持用途、服务、模型，应用/取消/重置。
- 顶栏“清除统计”使用确认对话框；清除后更新页内数据。
- `PolishStatsEntry` 增加 `callId`，用于稳定行键。

## 5. T4 应用写法与文本偏好

- 应用写法页显示电脑当前前台、总开关和去重后的应用规则。
- 规则来源为当前前台、`historyApplications` 的应用名和已保存覆盖规则；修复了误把历史 Map ID 当应用名的问题。
- 规则支持自动、通用、聊天、邮件、代码、文档、搜索；`PolishManager.sceneFor()` 统一页面与请求解析。
- 术语表和补充要求从首页 inline 文本框移到子页；校验规则抽到 `PolishPreferenceRules` 并有 JVM 测试。

## 6. T5 V2 提示词与请求保护

- V2 语音/文本模板、场景模块、固定应用映射、改写模块、独立翻译链路。
- 来源映射：LocalAsr/CloudAsr → voice；Ime/Clipboard/Selection/Mixed → text。
- 严格目标语言校验、规范化、内置语言名称和按语言示例。
- 输入只转义 `& < >`；输出还原并按上下文做长度比、事实保真、目标文字、回显和标签泄漏检查。
- 请求票据冻结来源、翻译风格、提示词版本和 guard revision。
- `PolishClient` 的普通/SSE/解析路径统一传入 guard 上下文。

## 7. T6 说话页与快捷卡

- 翻译模式增加“自动/日常聊天/正式书面/技术文档”风格。
- `QuickActivity` 工具区增加 AI 润色入口，快捷卡可打开同一 `PolishSheet`。
- 完整页和快捷卡共用草稿、会话和润色状态。

## 8. 验证

已执行：

```text
:app:testDebugUnitTest                 177 tests, 0 failures
:app:lintDebug                         BUILD SUCCESSFUL
python scripts/check_i18n.py           passed en, zh-Hans
:app:assembleDebug                     BUILD SUCCESSFUL
K40 安装 seeyuer.sayin                 Success
```

隔离 `imageTest` 定向仪器化：

```text
PolishUsageStoreInstrumentedTest       5 tests
PolishUiInstrumentedTest               3 tests
OK (8 tests)
```

覆盖 501 条全量汇总/分页、清除与 finish 竞态、设置页服务入口、连续新增与重载恢复。

## 9. 未完成边界

- 价格详情页已在 K40 验证；USD 参考价和缓存读显示正常，CNY 已在统计页验证。
- 完整 148 项 `verify_android_transport.ps1 -FullSuite -UseExistingRootInstall` 已执行两轮。第一轮 39 项失败；清理隔离测试包数据并放宽单项超时后，第二轮为 `Tests run: 148, Failures: 2`：`TransportInstrumentedTest` 的 `INVALID_PAIRING` 与 `AssistPanelInstrumentedTest` 的系统返回关闭确认对话框超时。仍不能记为完整通信通过。
- T5 的 U01–U08 真实模型/中转站语料未重跑；当前仅验证模板与 guard 单测。
- 应用写法规则选择、术语/补充要求保存、快捷卡 AI 面板运行均已有截图/入口证据，但尚未加入常规仪器化套件。
- 清除统计确认对话框、筛选应用后的空结果和跨币种切换尚未加入 UI 自动化。
- 全量套件剩余 2 项失败需要先区分 fixture/环境与真实回归；在恢复 148 项通过前，不应声称本任务通信验收完成。\n- 已在测试脚本中增加隔离包数据清理和 120 秒单项超时；这两项属于夹具稳定性修正，不改变产品行为。
