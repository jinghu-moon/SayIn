# 20 · 对照 tranvoice 的上屏实现与 Windows 权限修复

日期：2026-11-13。

## 本轮结论

上一轮 19 修复的是队首永不过期，未验证真实 Windows 原生输入。本轮用户再次反馈没有落字，记录仅显示过期。对照本地 `refer/tranvoice-master` 并执行原生预检探针，复现了另一个实际缺陷：声达仅凭目标 `TokenElevation` 非零就拒绝，忽略了接收进程自身可能已经处于相同权限。

本机只读探针显示 `receiver_process_elevated=true`，旧预检返回 `elevated_target`；修正后同等权限前台通过预检。随后以独立原生 EDIT 子进程验证真实 `SendInput` 落字成功。此前过期操作的旧 reason 已被覆盖，无法从现有账本反推用户截图那条操作当时的确切失败原因，不能声称已复原该条的全部执行过程。

## tranvoice 怎么上屏

实际 Windows 默认路径由 `src/pc/server.js` 选中 `src/pc/win32-injector.js`：

1. 手机 `text.submit` 到达 `ws-server.js`，直接调用 `injector.typeText`。
2. 正文用 Base64 送入单次 PowerShell，使用 `Set-Clipboard` 写入文字。
3. 等待 80 ms，通过 Windows Forms `SendKeys.SendWait("^v")` 向前台发送 Ctrl+V，再等待 50 ms。
4. PowerShell 正常退出就回报成功；不读取目标输入框确认结果，也没有按双方完整性级别做输入前判断。

这条实际默认路径不调用 Ctrl+A。通用 `injector.js` 中的非 ASCII fallback 则包含 Ctrl+A + Ctrl+V；不能照搬它，否则会覆盖目标输入框的既有文字。默认路径也没有锁定某个应用或把焦点切回接收窗口。

声达当前采用批量 Unicode `SendInput`，保留这条已实测能工作的路径。本轮没有把剪贴板覆盖、PowerShell 进程启动及固定 sleep 引入输入热路径，也没有把“进程正常结束”当成目标软件已经显示文字。协议中的长文剪贴板通道仍为待实施项，终端兼容性应按具体终端验证。

## 权限判断修复

Microsoft `SendInput` 文档明确规定：输入注入受 UIPI 限制，只允许相同或更低完整性级别。管理员标志不等价于双方完整性级别的比较。

修改前：目标为管理员 → 无条件挂起，因此接收进程也为管理员时仍永远不进入输入 API。

修改后：通过 query-only 进程令牌读取双方 `TokenIntegrityLevel` 的 mandatory SID RID，只有 `target <= receiver` 才通过。查询失败、无效 SID、缓冲区越界等仍拒绝。没有修改进程权限、请求 UAC、默认提权或降低跨级别限制；会话、输入桌面、声达自身、按键状态、前后焦点身份、暂停代次和执行标记守卫保留。

新增边界测试覆盖普通→普通、管理员→同级、管理员→普通的允许，以及普通→管理员、低级→普通、管理员→system、medium→medium-plus 的拒绝。

## 过期原因可诊断

上一版到期时把所有 reason 改为 `expired_before_input`，使用户只能看到“过期”，看不到最初因为什么未执行。本轮在 `held → expired` 时保留已知拦截 reason，普通排队过期继续使用 `expired_before_input`。不变更账本结构、配对身份或历史载荷。

电脑和手机根据 `st + reason` 显示“具体拦截原因 + 已过期，不会补打”；未知原因用资源兜底，不显示原始平台错误。权限提示改为目标权限高于声达，避免错误暗示所有管理员窗口都不能输入。已经被旧版覆盖的原因无法事后补回，不伪造历史。

## 真实输入验证

`desktop/crates/receiver/examples/native_input_fixture.rs` 是显式运行的本机验证工具：

- 启动它自己的独立进程和原生多行 EDIT，设定已存在的 `existing|` 前缀与末尾光标，泵送真实 Windows 消息。
- 使用独立临时 DPAPI 账本和真实接收协调/输入线程，经 `NativeInput` 捕获并核对前台。
- 测试 adapter 额外限制只允许该子进程的 HWND/PID，窗口焦点不符时拒绝。未读取或修改用户应用内容、生产正文或配对凭据。
- 发送 `中文 😀 literal {ENTER}`，子进程读取实际 EDIT 内容，必须精确等于 `existing|中文 😀 literal {ENTER}`。由此验证中文、UTF-16 代理对、字面命令、光标插入、前缀保留和无附加回车。
- 同 UUID 再次提交仍返回已输入，历史恰好一条且目标 PID 为该子进程；退出时关闭自有窗口和账本。

真实输入日志：[20-real-native-input.log](research/20-real-native-input.log)。只读预检对比：[修复前](research/20-native-preflight-baseline.log)、[修复后](research/20-native-preflight-fixed.log)。运行命令：

```powershell
cd desktop
cargo run -p voicelink-receiver --example native_input_probe --locked
cargo run -p voicelink-receiver --example native_input_fixture --locked
```

原生 EDIT fixture 的消息泵/光标初始化在调试中曾导致焦点不符及插入位置不符；工具修正后才得到精确文本验证通过。这些过程失败没有当成产品输入通过。

## 回归与交付

新增账本测试验证过期保留权限原因、释放 pending、保留原文且无执行时间。WSS 回归验证过期推送仍带原始挂起原因。Android 实机独立存储通信回归确认收到挂起原因、自动过期并保留原因、重连及 repository 重建后状态保留，假输入只执行一次。

本轮手机通信使用 `scripts/verify_android_transport.ps1 -TransportOnly`，只跑通信仪器化测试，避免上一轮 MIUI 阻挡 UI Activity 导致的等待；默认完整测试入口保留，不把通信模式声称为全部 UI 验证。

完整工作区测试、Clippy、构建、包限定格式检查、双端 i18n/依赖检查和 Android Lint/单元测试/构建记录分别在 `research/20-*.log`，最终部署信息见 [验收记录](research/20-native-input-verification-2026-11-13.json)。真实 EDIT 验证不等同于所有终端、聊天软件或手机经 LAN 到用户应用均已验收，不据此声称延迟目标达标。

更新后：在目标输入区域放好光标，只在手机发送新文字。若有拦截，新记录会保留实际原因，30 秒后仍可查看。已过期旧请求不补打，已有配对和电脑原文历史保留。

## 官方依据

- [SendInput](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput)：UIPI 的 equal-or-lesser integrity level、返回事件数与修饰键影响。
- [Mandatory Integrity Control](https://learn.microsoft.com/en-us/windows/win32/secauthz/mandatory-integrity-control)：完整性级别和进程继承。
- [GetTokenInformation](https://learn.microsoft.com/en-us/windows/win32/api/securitybaseapi/nf-securitybaseapi-gettokeninformation)：query-only 令牌信息与缓冲区边界。
