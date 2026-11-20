# 76 · Android AI 润色第一批实施记录

对应 [75 · Android AI 润色后续任务](75-android-ai-polish-follow-up-tasks.md) 第一批。日期：2026-11-20。开发阶段，未发布。

## 已完成

### 1. 多份服务配置

- `PolishConfig` 增加稳定 `id`、显示名 `name` 和单调递增的 `revision`；`fingerprint` 纳入 `id`，切换配置不会误复用旧自动润色授权。
- 加密存储升级为 `version=2` 的 `configurations + activeId`，仍使用 AndroidKeyStore AES-GCM 与 `AtomicFile`；单配置旧文件首次读取时生成稳定 ID 并立即写回。
- 同一设置页通过“已保存的服务”下拉切换配置；“添加服务”“删除当前服务”与保存、测试共享同一编辑表单。
- 每次润色票据冻结 `configId`、`configRevision` 和 `configFingerprint`；应用结果前同时核对。配置切换、编辑当前配置或删除时清空旧结果并撤销自动润色绑定。
- 模型/Key 仍在同一绑定内复用；改地址、协议或鉴权方式会清空 Key。

### 2. basellm 快照与周期刷新

- 随包资源：`android/app/src/main/assets/basellm/all.json`、`LICENSE`、`manifest.json`。
  - 来源：`https://basellm.github.io/llm-metadata/api/all.json`
  - 上游：`https://github.com/basellm/llm-metadata`，Apache-2.0
  - 快照 SHA-256：`d26a9a5c8a8b9b05dd9ea414d55851cc973d1db92e0902caaefbce430830ff01`
  - 大小：721281 字节；取数时间：`2026-11-20T11:23:20Z`
- `scripts/check_basellm_snapshot.py` 可重复核验格式版本、来源、许可、SHA-256、字节数和文本模型候选。
- 官方预设映射：DeepSeek→`deepseek`，千问→`alibaba-cn`/`alibaba`，OpenAI→`openai`，Claude→`anthropic`。目录只提供文本模型候选，不改变协议、鉴权和请求参数。
- WorkManager 唯一周期任务 `sayin-polish-catalog`，每 24 小时、联网约束；没有官方配置时取消。手动刷新与后台共用同一 `Mutex` 和刷新实现。
- 刷新只向固定公开 HTTPS 地址发 GET，不使用服务 Key、地址或草稿；携带缓存 `ETag`，`304` 只更新检查时间；`200` 有界读取（2 MB）、严格 UTF-8、结构校验后原子替换；失败继续用当前快照/缓存。
- 缓存记录随包版本；应用升级后随包 SHA 变化时旧缓存不再参与选择。

### 3. 模型搜索、手填与中转站列表

- 设置页“选择模型”打开可搜索候选面板，覆盖 DeepSeek、千问、OpenAI、Claude；当前模型始终保留，目录刷新不会重置模型或 Key。
- 自定义服务可手动“获取中转站模型”，通过同一已确认端点和鉴权执行 `GET .../models`；独立于付费连接测试，401/404/空列表/超时均可继续手填。
- 切换配置或服务后递增列表请求代次，旧服务的迟到响应被丢弃。
- 请求参数按已验证的精确模型 ID 收窄：OpenAI 只有 `gpt-6-luna` 使用 `max_completion_tokens` 和 `reasoning_effort=none`；其他手填模型退回 `max_tokens` 且不发送 reasoning。Anthropic 只有 `claude-haiku-5-5` 发送 `thinking/output_config`。

## 验证

| 项目 | 命令 / 方式 | 结果 |
| --- | --- | --- |
| JVM | `scripts/gradle.ps1 :app:testDebugUnitTest --tests "seeyuer.sayin.polish.*" --tests "seeyuer.sayin.ui.settings.PolishEditorStateTest"` | 38 项通过，0 失败 |
| 快照 | `python scripts/check_basellm_snapshot.py` | OK，SHA/字节/许可/4 个厂商候选通过 |
| i18n | `python scripts/check_i18n.py` | 通过 |
| Lint | `scripts/gradle.ps1 :app:lintDebug` | BUILD SUCCESSFUL |
| 构建 | `scripts/gradle.ps1 :app:assembleDebug` | BUILD SUCCESSFUL |
| 安装 | root `pm install -r --user 0` | Success；`versionName=0.1.0`，`lastUpdateTime=2026-11-20 19:37:51` |
| 真机 UI | Redmi K40 | 已看到“已保存的服务”“添加服务”“选择模型”“刷新模型目录”；模型候选显示 DeepSeek V4 Pro / V4.1 Flash |
| 真机连接 | 设置页“测试连接” | `连接成功 · 306 ms`，实际 DeepSeek 配置 |
| 周期任务 | `dumpsys jobscheduler` | 已注册 `SystemJobService`（`JOB #u0a543/2`） |

## 未通过 / 待补

- 真机手动刷新目录当次返回 `Unable to resolve host "basellm.github.io"`。这是当前设备网络无法解析该域名，不是目录校验失败；随包快照仍可离线选模型。成功 `200`、`304` 和升级切换尚未在真实网络下记录。
- “保存 DeepSeek + 中转站、重启切换、增删互不影响”的完整 UI 验收尚未执行；当前已迁移并保留原 DeepSeek 配置。
- 真实 DeepSeek 手动润色本轮只验证连接，未重新跑固定样句；74 号文档已有的润色结果仍有效。
- WorkManager 在系统调度下的实际执行结果尚未拿到；当前只确认任务已注册。
- 中转站模型列表尚未连接真实中转站验证；JVM MockWebServer 已覆盖 GET 路径、鉴权、去重和 401。

## 命令

```powershell
python scripts/check_basellm_snapshot.py
python scripts/check_i18n.py
.\scripts\gradle.ps1 :app:testDebugUnitTest --tests "seeyuer.sayin.polish.*" --tests "seeyuer.sayin.ui.settings.PolishEditorStateTest"
.\scripts\gradle.ps1 :app:lintDebug
.\scripts\gradle.ps1 :app:assembleDebug
```
