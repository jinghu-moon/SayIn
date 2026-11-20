# 78 · Android AI 润色第三批与真机验收记录

对应 [75 · 后续任务](75-android-ai-polish-follow-up-tasks.md) 第三批。日期：2026-11-20。开发阶段，未发布。

## 已执行

| 项目 | 命令 / 方式 | 结果 |
| --- | --- | --- |
| 完整 JVM | `scripts/gradle.ps1 :app:testDebugUnitTest` | 168 项通过，0 失败 |
| i18n | `python scripts/check_i18n.py` | 通过 |
| Lint | `scripts/gradle.ps1 :app:lintDebug` | BUILD SUCCESSFUL |
| Debug 构建 | `scripts/gradle.ps1 :app:assembleDebug` | BUILD SUCCESSFUL |
| 安装 | root `pm install -r --user 0` | Success，Redmi K40，包名 `seeyuer.sayin` |
| 真机连接 | 设置页“测试连接” | `连接成功 · 306 ms` |
| 真机普通润色 | DeepSeek 固定短样句 | 成功，输入 450 / 输出 58 Token，375 ms，估算 `0.000102 USD` |
| 真机 SSE 润色 | 开启流式预览后 DeepSeek 固定短样句 | 成功，输入 450 / 输出 22 Token，436 ms，估算 `0.000043 USD` |
| 模型候选 | 设置页“选择模型” | 本地快照候选显示；DeepSeek V4 Pro / V4.1 Flash |
| 用量页 | 真机 | 今日/7 天/30 天筛选、调用数、Token、中位耗时、未知费用、用途均显示 |
| 周期任务 | `dumpsys jobscheduler` + `cmd jobscheduler run -f seeyuer.sayin 13` | 已注册并实际执行，Worker result RETRY（DNS 失败） |
| X-ASR 仪器化 | `am instrument -w -e class seeyuer.sayin.asr.XAsrInstrumentedTest ...` | OK（2 项），15.816 s，使用真实 JNI 和 CPU 完成混合中英及静音文件推理 |
| 多配置存储仪器化 | `am instrument -w -e class seeyuer.sayin.polish.PolishConfigStoreInstrumentedTest ...` | OK（1 项），两份配置的 Key、端点和模型独立，切换后仍各自保留 |
| 多配置 UI | K40 手工 | 新增 Second、切回 DeepSeek，重启后原配置仍启用。切到 Second 并删除后，原 Key 和模型仍保留 |
| 用量库仪器化 | `am instrument -w -e class seeyuer.sayin.polish.PolishUsageStoreInstrumentedTest ...` | OK（4 项），覆盖待定转未知、终态幂等、清空后不复活、90 天清理、4 路并发及清空和完成竞态 |
| 目录缓存仪器化 | `am instrument -w -e class seeyuer.sayin.polish.PolishCatalogStoreInstrumentedTest ...` | OK（1 项），覆盖有效缓存、升级淘汰旧缓存和损坏回退 |
| 真实目录刷新 | VPN 直连 | 成功，真机显示“目录检查于 11-20 21:01” |
| 真实中转站 | `PolishRelayLiveTest`，VPN 直连 | OK，`api.shuaiapi.com` 的 `gpt-6.2-sol` 模型列表、连接和润色均成功 |

## 未执行 / 未通过

- **其他厂商真实账号**：只有 DeepSeek 凭据。千问、OpenAI、Claude 的真实请求、实际模型、缓存用量、取消和费用来源均未验证；不能标记为通过。
- **中转站计费**：已有一站模型列表、连接测试和润色成功。该站实际账单与 App 的费用口径还未比对。
- **SSE 真实服务**：已用 DeepSeek 真机复测金额；其他厂商 SSE 未验证。
- **目录刷新网络条件**：设备在无 VPN 时曾遇到 DNS 失败，VPN 直连下手动刷新已通过。周期 Worker 在真实网络下成功应用新目录仍待验证。
- **ASR 固定语料**：文件级 X-ASR 混合中英语料已通过 2 项仪器化测试；真实麦克风录音、豆包云 ASR、输入法/剪贴板/选区、`partial` 不发请求、取消/断网保留原文仍未执行。
- **目标应用落字**：未执行真实目标应用的输入结果观察；此前文档中的 LAN/loopback 证据不能替代本轮应用落字。
- **费用完整性**：厂商上报金额、非 USD 币种、账号套餐和中转站账单对照仍待补。90 天清理和并发清空已有设备测试结果。

## 结论

第一批的服务配置、快照、模型选择，和第二批的用量 Room 持久化、DeepSeek 普通响应用量/估算费用，已在 K40 上验证。第三批的文件级 X-ASR 语料已通过，但真实麦克风、输入法/剪贴板/选区、其他厂商和目标应用落字验收尚未完成，不能把当前状态描述为完整验收通过。

## 75 号任务复审修复

| 项目 | 修改前 | 修改后及验证 |
| --- | --- | --- |
| 服务 | 连续新增会复用同一预设 ID，切换和保存分别写配置 | 新增独立 ID，写入成功后更新当前配置。K40 仪器化 UI 连续新增两份同厂商配置，重新读取时名称和 Key 各自保留。 |
| 用量 | Claude 流式只解析末段，用量页先取 500 条再筛选 | 合并首包模型与用量和后续输出计数，错误仍保留已接收用量。Room 按日期、用途、服务、模型汇总并对明细分页，K40 的 501 条用例通过。 |
| 费用 | 取响应时的目录，按厂商查价 | 调用开始时冻结目录，按已确认端点分开匹配区域。缺缓存费率、不适用的中转站和无法判定节假日的 DeepSeek 高峰规则显示未知。当前价格为 USD 参考估值。 |
| 目录 | 周期刷新落盘后页面仍读旧值，旧缓存的 ETag 仍可能发出 | 周期和手动刷新共享可观察状态。缓存有效才发送 ETag，校验通过后原子更新。K40 专项覆盖升级淘汰旧缓存、重新获取、304 和新目录发布。 |
| 设置页 | 切服务、编辑凭据、行为和全部用量同页 | 首页切换已保存的服务并展示摘要。独立管理和用量页承载配置及明细，模型面板提供搜索、手填和刷新。K40 三项 AI 界面测试通过。 |
| 说话页工具栏 | 完整页固定单行，320dp 下扣除卡片留白后只剩 252dp，六个 48dp 按钮会被压缩 | 窄屏缩减卡片留白，空间仍不足时按 48dp 按钮尺寸换行。现有无障碍回归同步纳入 AI 按钮。 |

本轮 `scripts/gradle.ps1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 成功，JVM 171 项通过。`scripts/check_i18n.py`、`scripts/check_basellm_snapshot.py` 和 `git diff --check` 均通过。K40 上通过 8 项 AI 目录、配置和用量数据库测试及 3 项 AI 界面测试。`adb -s dc39c31d install -r android/app/build/outputs/apk/debug/app-debug.apk` 返回 Success，日常包 `seeyuer.sayin` 已覆盖安装。

补充验证记录

- 第一轮 `scripts/verify_android_transport.ps1 -DeviceSerial dc39c31d -FullSuite` 运行 148 项，三个用例出现四条失败记录。辅助面板的短语清空取消和窄屏大字号用例在组合运行超时，当前版本逐个重跑各通过一次。说话页工具按钮宽度断言在修改前基线 `3c34e99` 的同台 K40 上复现。日志分别为 `desktop/target/image-android-64faaf20-04f9-493e-ab0f-2f4001070d3e.log` 和 `desktop/target/ai-polish-ui-baseline-3c34e99.log`。
- 第一轮通信 fixture 记录两次预期的模拟输入，原生输入次数为零。独立图片套件通过 4 项，记录一次预期的模拟输入，日志为 `desktop/target/image-android-457d6e97-cf47-43cf-a4bf-82fbfbe26f24.log`。
- 日常包的周期任务已注册。本轮强制系统触发任务 34 后，WorkManager 仍等待下一次执行。缓存和最后检查时间未变化，日常包周期联网刷新仍待验证。
- 工具栏修复后的日常包已再次覆盖安装，`lastUpdateTime=2026-11-20 22:19:23`。通过 `am start -W` 冷启动，返回 `Status: ok`。
- 工具栏修复后执行 `scripts/verify_android_transport.ps1 -DeviceSerial dc39c31d -SkipBuild -FullSuite`，文字及界面套件报告 `OK (148 tests)`，独立图片套件报告 `OK (4 tests)`。两个辅助面板用例和说话页六个工具按钮的宽度断言均通过。付费中转站用例仍按显式开关跳过。两套 fixture 分别记录 2 次和 1 次模拟输入，无原生输入。日志为 `desktop/target/image-android-66a4e5ba-a4a0-424e-8cf8-a577bbaf3f21.log` 和 `desktop/target/image-android-98aef163-03d4-4d90-af29-809217435bda.log`。
