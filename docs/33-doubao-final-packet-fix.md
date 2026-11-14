# 33 · 豆包最终包误判修复与 API Key 排查

日期：2026-11-13。前置实施见 [32 云端流式接入](32-doubao-cloud-streaming-asr.md)。

## 已复现的问题

手机能够显示用户说出的文本，但结束说话后提示「云端响应不完整或格式异常」；设置页「验证服务」也出现相同提示。

文字来自已成功接收的实时识别快照，不能据此认定最终包已处理成功。此次问题确实出在客户端：初版将 **flags=3 的最终包必须使用负序号**当成强制条件，而真实 `bigmodel_async` 服务返回了：

```text
message type = 9
flags = 3
sequence = +5
```

修改前在 Redmi K40 上，使用手机已保存的 APP ID / Access Token，发送测试资产中 3.1 秒已知音频，实际失败为：

```text
Cloud ASR: Protocol (sequence:flags=3,value=5)
Tests run: 1, Failures: 1
```

测试直接在手机进程中加载加密配置，密钥没有导出至电脑或日志；不使用麦克风，不读取用户录音，不进入 LAN 发送流程。

## 根因与修复

二进制头部的两位承担不同职责：bit 0 表示后面存在 sequence，bit 1 表示最终包。应该按 bit 1 判定结束，读取 sequence 后继续解析载荷，不能用序号符号替代最终包标记。

[官方流式 API 文档](https://www.volcengine.com/docs/6561/1354869)的 flags 字段说明与后面的 message-flow 示例存在不一致：示例最终包使用 flags=3、sequence=+3。真实服务也返回正序号最终包。修复遵循明确的最终包标志和实际服务行为，而非坚持已经证伪的负序号约束。

- 允许正、负序号的最终包；非最终标志即使携带负序号也不视作完成。
- 继续严格检查版本、类型、长度、压缩、UTF-8 和 JSON，以及「用户明确结束、发送末包、收到最终包」顺序。
- partial 后直接断开、提前最终包、末包后超时仍失败；没有通过把 partial 当最终稿或忽略错误来消除提示。
- 设置页的连接、协议和超时错误使用验证服务专用文案，不再提及不存在的草稿操作。
- 结构诊断仅包含程序生成的帧结构信息，不记录服务响应正文、转写内容或凭据。

## API Key 是另一条独立问题

用户确认密钥来源：

`https://console.volcengine.com/speech/new/setting/apikeys?projectName=default`

这是正确的语音服务新版控制台入口。[官方 API Key 使用说明](https://docs.volcengine.com/docs/doubao-voice/api-key-usage?lang=zh)明确：只需 `X-Api-Key`，不需要同时填写 APP ID。本项目的请求头与之相符；界面名称改为 **API Key**，避免旧文档中 APP Key 的叫法引起混淆。

[官方控制台指引](https://docs.volcengine.com/docs/doubao-voice/console-guidance)另明确说明：

- API Key 归属于项目；不同项目的资源、权限与模型开通相互隔离。
- 调用前需在目标项目开通对应模型；默认项目与其他项目的开通状态不能互相推断。
- 旧版以应用隔离服务与凭据。因此旧 APP ID 调用成功，不能证明新版 API Key 所属项目已开通同一模型。

随后在手机内复现 API Key 请求，服务端实际返回 HTTP 403。对响应只在手机内按固定词表脱敏分类，确认为 **MissingServiceGrant（请求资源未授权）**，不是结束包解析错误。用户在同一项目开通对应小时版模型后，同一把 API Key 的资源访问、音频转写及静音验证均恢复通过，确认该次失败由模型资源授权缺失引起。没有要求重新生成密钥或修改认证头。

握手错误细分为：HTTP 401 → 凭据认证，403 → 访问拒绝（提示核对凭据及所属项目 / 应用开通），429 → 服务限制。403 不再直接断言密钥无效。没有增加项目选择器、套餐猜测、认证方式自动回退，也不同时发送两组认证头。

先用 APP ID / Access Token 完成 2 项真实云服务复验。随后由用户在手机内保存 API Key，完成失败复现、开通模型和成功复验。当前手机保留 API Key 配置，**同一批 3 项真实服务测试全部通过**；凭据未导出至电脑。开通后第一批请求中，最早的请求仍返回 403，而后续资源访问和静音探测已成功；单独复验语音后成功，再完整复跑 3 项均成功，符合授权生效存在短暂传播窗口的现象，未增加自动重试音频的逻辑。

## 验证记录

修改前：38 项 JVM 测试通过，但真实服务测试失败，说明原有模拟服务未覆盖实际正序号最终包。

修改后：

- **39 项 JVM 测试通过**。新增正 / 负 sequence × 最终 / 非最终 flags 的组合；正常 WebSocket 集成用例改用真实服务形态的 flags=3、sequence=+5。缺失 sequence / 长度的坏帧仍拒绝。
- 原有 partial 修订、尾包、取消、提前结束、缺最终结果、超时、Gzip 上限和本地流式用例保留通过。握手测试实际覆盖 401 / 403 / 429、不开麦、不自动重试。
- Debug、仪器化测试 APK、Release（含 R8 / 资源裁剪）、lint、双语资源与 diff 检查通过。
- 真机真实云服务 **2 项通过**：3.1 秒测试音频实时识别并成功取得最终文本；设置页实际复用的 1 秒生成静音探测成功。第一次修复后实测 `OK (2 tests)`，5.115 秒；最终安装产物复测仍为 2 项通过，4.857 秒。没有以模拟服务器结果代替真实服务结果。

命令：

```powershell
.\scripts\gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
python scripts/check_i18n.py
git diff --check

adb -s dc39c31d shell am instrument -w -r -e liveCloud true `
  -e class seeyuer.sayin.asr.DoubaoLiveInstrumentedTest `
  seeyuer.sayin.test/androidx.test.runner.AndroidJUnitRunner
```

`DoubaoLiveInstrumentedTest` 必须显式传 `liveCloud=true` 才访问真实服务，否则假设跳过。没有密钥时也跳过。类中共有 3 项 opt-in 测试：资源访问、3.1 秒语音转写、1 秒静音验证；后两项会产生几秒服务用量。普通通信回归脚本通过 `notClass` 排除该类，保持原有 23 项常规设备测试，避免自动消耗用户云服务额度。仪器化源码现共有 26 个测试，不能将编译通过或排除 opt-in 测试描述为 26 项全部执行。

实际设备仍为 Redmi K40 / Android 13。未运行真机 UI 自动化或整套 LAN 输入回归，未测量日常录音、热词准确率及端到端输入延迟。新的实际服务证据不改变这些边界。

日志与官方资料摘录位于忽略提交的 `docs/research/33-*`。本次覆盖安装保留云端配置、配对及持久历史，不导出凭据。

## 用户复验

1. 使用现有 APP ID / Access Token，点击「验证服务」，应显示验证完成。
2. 说话页选择云端，开始说话并结束，检查文字和尾字；应正常回到可编辑 / 可发送状态，不再误报格式异常。
3. API Key 所属项目现已由用户开通对应模型，真实验证完成。可直接使用当前 API Key 配置，不需切回旧版方式。以后新增项目时仍需单独开通模型；密钥不需要也不应发送至聊天。

## 修复版产物

Debug APK：663,644,214 字节，SHA-256 `92d75a69bb100bdd7f61e9c6f6a179bba980770031ea7b5dc5ab02be004e0098`。覆盖安装到 Redmi K40 后，完成上述 2 项真实服务复测并重新打开主界面。

### API Key 最终复验

`docs/research/33-api-key-live-final.log`：**OK (3 tests)**，5.059 秒。包含 HTTP Upgrade 成功、真实测试语音的 partial 与最终文本、设置页相同静音探测；日志不记录转写内容、密钥或服务端原始响应。主界面已重新打开供用户手工实录验收。
