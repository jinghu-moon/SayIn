# 77 · Android AI 润色第二批实施记录

对应 [75 · 后续任务](75-android-ai-polish-follow-up-tasks.md) 第二批。日期：2026-11-20。开发阶段，未发布。

## 已完成

### 用量归一化

- 新增 `PolishUsage`：普通输入、缓存读取、缓存写入、总输出、推理明细。
- Chat Completions：读取 `prompt_tokens`、`completion_tokens`、`prompt_tokens_details.cached_tokens`、`completion_tokens_details.reasoning_tokens`；兼容 DeepSeek `prompt_cache_hit_tokens`/`prompt_cache_miss_tokens`，缓存命中只计一次。
- Anthropic Messages：`input_tokens + cache_creation_input_tokens + cache_read_input_tokens` 归一为总输入，保留缓存分项。
- 普通响应与 SSE 共用同一归一化；截断/拒绝响应保留已取得用量。
- 非法负数、缓存超过总输入、推理超过总输出时归一化为空，不写入猜测值。

### Room 调用记录

- 新增独立 `polish-usage.db`，按调用 ID 保存：起止时间、耗时、配置 ID/版本、服务、协议、用途、请求模型、HTTP 状态、终态、用量分项、费用来源/金额/币种/目录版本/规则。
- 网络请求前写入 `pending`；完成时 `UPDATE ... WHERE outcome='pending'`，重复终态幂等。
- 启动时将遗留 `pending` 恢复为 `unknown`，保留已取得用量。
- 按 90 天清理；清空后迟到 `UPDATE` 找不到行，不会复活。
- 用途区分润色、翻译、连接测试；测试连接也单独记录。
- 统计页改为读取 Room；保留最多 500 条用于展示。

### 费用估算

- 从随包 basellm 目录按精确模型 ID 取 USD/百万 Token 费率。
- 普通输入、缓存读取、输出分别用 `BigDecimal` 计算，结果保存为微 USD，避免汇总前丢失精度。
- 缓存写入在当前快照没有已核验费率时标记未知；测试连接不估算费用；未知模型/缺费率保持未知。
- 记录目录版本和规则标识，便于历史追溯。

### 用量页

- 今日、最近 7 天、最近 30 天筛选。
- 显示调用数、已知 Token 总量、耗时中位数、未知费用调用数。
- 明细显示用途、服务、模型、输入/输出 Token、耗时、结果、费用来源。
- 估算费用以 6 位小数显示；没有金额显示“未知”。

## 验证

| 项目 | 结果 |
| --- | --- |
| JVM | 44 项通过，0 失败（含新增 usage、pricing 测试） |
| i18n | `python scripts/check_i18n.py` 通过 |
| Lint | `:app:lintDebug` BUILD SUCCESSFUL |
| 构建/安装 | `:app:assembleDebug` 成功；root 覆盖安装成功 |
| 真机统计 | 测试连接后显示“1 次调用 · 83 Token · 中位耗时 252 ms”、用途“连接测试”、未知费用 1 |
| 数据库 | 设备上 Room 记录可读，筛选/汇总/明细正常 |

## 未完成 / 待补

- 真实润色的估算金额尚未用真机复测；目前只验证测试连接的未知费用路径和单元价格计算。
- 上报金额、非 USD 币种、价格时段/套餐/区域、缓存写入费率尚未接入。
- “已知未发送”和“可能计费的未知调用”尚未在 UI 分开呈现；当前统一显示未知费用。
- 取消请求时尚未立即把 pending 标为取消/未知，需等下次启动恢复；未完成取消竞态测试。
- 90 天清理、清空与迟到完成、并发写入尚未做设备/仪器化竞态验证。
- SSE 用量只在代码路径共用归一化，仍缺独立的 SSE 用量断言用例。
- 用量页尚未按配置/模型/用途做分组聚合，仅逐条展示并提供时间筛选。

## 命令

```powershell
python scripts/check_i18n.py
.\scripts\gradle.ps1 :app:testDebugUnitTest --tests "seeyuer.sayin.polish.*"
.\scripts\gradle.ps1 :app:lintDebug
.\scripts\gradle.ps1 :app:assembleDebug
```
