# 78 · Android AI 润色第三批与真机验收记录

对应 [75 · 后续任务](75-android-ai-polish-follow-up-tasks.md) 第三批。日期：2026-11-20。开发阶段，未发布。

## 已执行

| 项目 | 命令 / 方式 | 结果 |
| --- | --- | --- |
| 完整 JVM | `scripts/gradle.ps1 :app:testDebugUnitTest` | 162 项通过，0 失败 |
| i18n | `python scripts/check_i18n.py` | 通过 |
| Lint | `scripts/gradle.ps1 :app:lintDebug` | BUILD SUCCESSFUL |
| Debug 构建 | `scripts/gradle.ps1 :app:assembleDebug` | BUILD SUCCESSFUL |
| 安装 | root `pm install -r --user 0` | Success，Redmi K40，包名 `seeyuer.sayin` |
| 真机连接 | 设置页“测试连接” | `连接成功 · 306 ms` |
| 真机普通润色 | DeepSeek 固定短样句 | 成功；输入 450 / 输出 58 Token，375 ms，估算 `0.000102 USD` |\n| 真机 SSE 润色 | 开启流式预览后 DeepSeek 固定短样句 | 成功；输入 450 / 输出 22 Token，436 ms，估算 `0.000043 USD` |
| 模型候选 | 设置页“选择模型” | 本地快照候选显示；DeepSeek V4 Pro / V4.1 Flash |
| 用量页 | 真机 | 今日/7 天/30 天筛选、调用数、Token、中位耗时、未知费用、用途均显示 |
| 周期任务 | `dumpsys jobscheduler` | 已注册 WorkManager `SystemJobService` |

## 未执行 / 未通过

- **其他厂商真实账号**：只有 DeepSeek 凭据。千问、OpenAI、Claude 的真实请求、实际模型、缓存用量、取消和费用来源均未验证；不能标记为通过。
- **真实中转站**：未提供实际中转站账号；仅用 MockWebServer 验证 `GET /v1/models`、鉴权、去重和 401。
- **SSE 真实服务**：已用 DeepSeek 真机复测金额；其他厂商 SSE 未验证。
- **目录刷新成功路径**：设备当前网络无法解析 `basellm.github.io`，手动刷新返回 DNS 错误；`200`/`304`/升级切换仍待真实网络验证。随包快照可离线工作。
- **ASR 固定语料**：本轮未重跑本地/豆包 ASR、输入法、剪贴板、选区；未验证真实录音自动润色、`partial` 不发请求、取消/断网保留原文。已有 JVM 自动触发矩阵，不等于真机 ASR 验收。
- **目标应用落字**：未执行真实目标应用的输入结果观察；此前文档中的 LAN/loopback 证据不能替代本轮应用落字。
- **仪器化 UI**：未运行 AI 仪器化用例；MIUI 对 `adb install` 的限制仍在，日常包使用 root 安装。测试包安装与结果未记录。
- **费用完整性**：上报金额、非 USD 币种、区域/套餐/时段、缓存写入费率、90 天清理和并发清空竞态仍待补。
- **目录刷新与 WorkManager 实际执行**：只确认任务注册，未取得系统实际联网执行结果。

## 结论

第一批的服务配置、快照、模型选择，和第二批的用量 Room 持久化、DeepSeek 普通响应用量/估算费用，已在 K40 上验证。第三批的真机 ASR/语料、其他厂商和小额真实费用验收尚未完成，不能把当前状态描述为完整验收通过。
