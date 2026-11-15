# 61 · Root 采集模块 Android 17 适配与新手机验证

日期：2026-11-15 至 2026-11-16。基线：`d7dbc00`（0.2）。阶段：**0.3.1 已通过 KernelSU 正式升级/重启核对、正式 ELF 14 项回归和相同 ELF 的真实锁屏/解锁测试；App 读取与保活未接入，管理器 Action/启用后开机恢复/卸载仍待验收**。

用户已连接新手机，要求传送模块，由用户安装、agent 测试。新设备实际为 Xiaomi `25102RKBEC` / `myron`、Android 17/API 37、arm64-v8a、HyperOS `OS4.0.6.0.XPMCNXM`，Root 身份为 `u:r:ksu:s0`。没有复用旧手机 Android 13 的成绩描述新机通过。工作区其它 Android/桌面修改保持原样，无 stash、reset 或并行 agent。

## 1. 差异与实现

旧包安装器和运行时明确只接受 API 33。新增 API 37 profile，与 API 33 共用严格文字解析、过滤、历史、加密、进程控制；不另造一套守护，也不只放开版本号。用户要求的模块仍为 C++20，诊断/写入 fixture 的 Java 不进入模块 ZIP。

| 位置 | API 33 | 新手机 API 37 | 处理 |
| --- | --- | --- | --- |
| `IClipboard` 读取/监听参数 | package、user | package、attributionTag、user、deviceId | 37 写入空 attributionTag、默认 deviceId 0 |
| 剪贴板读取/描述/注册/移除事务 | 4/5/7/8 | 4/5/7/8 | 共用 |
| `IActivityManager.getCurrentUserId` | 126 | 142 | profile 明确选择 |
| `ITrustManager.isDeviceLocked` | 10 | 10 | 共用，仍校验用户和 display |
| ClipData Item 对象字段 | 4 个 | 5 个，多出 IntentSender | 都要求不存在，不反序列化复杂对象 |
| 非空 BaseBundle | AOSP 原格式 | 本机 HyperOS 额外写入 `mHasIntent` Boolean | 明确 ROM 格式；非零拒绝，不猜测对齐 |

使用测试工具 `PlatformProbe.java` 直接反射当前手机框架中的接口签名和事务常量，并序列化固定测试 `ClipData`。两个生成样例保存为 `root-helper/tests/fixtures/*.hex`，正文只有生成的 `SAYIN_TEST_platform_中文🙂`，时间戳 12345；不从系统剪贴板生成这些样例。

初次解析生成样例时 `classification_layout` 失败。进一步从该手机拉取 `/system/framework/framework.jar`，核对其 BaseBundle 字节码：ROM 在非空 bundle 数据后写出额外 Boolean，且该标记不计入 bundle 长度。这是实际 ROM 差异，不是简单的 Android API 差异。实现仅对 API 37、manufacturer `Xiaomi`、OS 标识 `OS4.0` 选择该方言；未知小米 OS 标识拒绝启动，未知数据布局仍拒绝采集。其它 API 37 ROM 不因存在 AOSP profile 就被宣称已真机验收。

诊断、框架字节码摘录和源码对照位于被忽略的本机 `docs/research/native-clipboard/api37/`。主分支 AOSP 资料仅作结构对照，不作为本机固定版本的替代证据。

## 2. 测试侧根因与修正

新 ROM 中隔离 fixture 的 `WRITE_CLIPBOARD` 有 UID foreground 约束；后台调用 `setPrimaryClip` 没抛异常，却被拒绝，没有实际写入。不能据此判定模块丢失样例，也不能修改 AppOps 来让测试通过。

写入器改为真正的前台 Activity：仪器化准备好后，由 ADB Root 发起该**测试应用**的 Activity，等待真实焦点，随后写入并关闭。仅仪器化自行启动 Activity 会被该 ROM 的后台启动规则拦截；不改自启动、后台启动白名单或系统权限。fixture 只在测试期间短暂占用手机前台。

普通 ADB 安装 fixture 被 ROM 返回 `INSTALL_FAILED_USER_RESTRICTED`，通过已有 Root 安装专用测试包成功；不关闭系统安装检查、不替换日常 SayIn。fixture APK 不包含于模块 ZIP。

## 3. 升级前后与实际结果

升级前重新执行 `pwsh -File scripts/build_root_helper.ps1`：0.2 主机 **1666 断言**、工程 **8/8**、原生构建通过。升级后当前主机 **2247 断言**、工程 **8/8**、arm64 原生告警即错误构建通过。原 API 33 样例/边界仍测，新增 37 真机样例、截断、变异、版本隔离与 ROM 敏感标记测试。

```powershell
pwsh -File scripts/build_root_helper.ps1
python root-helper/tests/build_device_fixture.py
python root-helper/tests/verify_device.py --serial f3ba305a --allow-synthetic-clipboard-writes
```

API 37 最终 **14/14 真机用例通过**：

1. Binder 服务和 AES-GCM 往返/篡改自检。
2. 连续 5 条中文/emoji 样例全部进入历史。
3. 敏感、超大、URI、HTML、富文本均跳过。
4. 含普通非敏感 Bundle 元数据的文字正常采集，避免把“所有非空 extras 都丢弃”误算敏感过滤成功。
5. 重复实例拒绝。
6. 运行中清空屏障、后续采集及停用后持久恢复。
7. 静态密文无测试正文。
8. 普通 shell 读取历史被拒。
9. 重启采集保留历史、不导入当前剪贴板，临时 stop 保留 opt-in。
10. 仅杀死已核对路径的隔离父进程后，shell 子进程停止。
11. 模拟 module disable 标记使双进程停止。
12. 篡改历史拒绝读取及启用。
13. 缺失密钥拒绝重新创建。
14. 关闭后清理实现已知的历史/密钥。

测试采用随机私有目录、专用测试前缀和限时进程，不修改日常配对/草稿/Outbox，不开启 `/data/adb/sayin-helper` 日常采集。会覆盖系统当前剪贴板为生成样例，不备份或输出原内容。测试原始证据为本机 `docs/research/native-clipboard/evidence/device-api37-f3ba305a.json`。

最初交付时只连接新手机，API 33 仅做主机回归；用户重启后旧手机也已连接，随后对 0.3 和修复版 0.3.1 都补跑了 API 33 的 14 项真机回归，均通过。0.2 的 API 33 真机证据保留在 [60](60-root-helper-native-clipboard-collection.md)。本次不改产品 App/桌面，因此未重跑两端完整测试，不能拿本模块结果替代产品回归。

## 4. 交付与待验收

0.3 包已传至新手机：

```text
/sdcard/Download/sayin-helper-0.3.0-dev-arm64.zip
大小 276004 字节
ZIP SHA-256 a806dffa84e84acea50f707a7e678c207e529c322f72cdc3f92766adb62334f6
ELF SHA-256 37e9c853b1da4ad7395228135d521bf365edd1226046895c96b4c76d0df56561
```

手机与电脑 SHA-256 一致。用户自行通过 KernelSU 管理器安装、重启，agent 不自动安装 Root 模块。首次默认关闭，不预先点击 Action 开启日常采集。

用户安装并重启后的实际结果在以下续记。管理器界面 Action 实际切换、启用状态下开机恢复和卸载仍未验收，不把命令行或模拟标记替代为这些操作通过。

边界仍与 60 一致：不保证快速覆盖零漏采、系统时间回拨可能漏采、密钥不是硬件 Keystore、不能防 Root、普通 App 面板读取与应用保活尚未接入；其它 ROM、长期功耗、磁盘满/断电尚未验收。

## 5. KernelSU 正式安装后的核对

用户反馈“已重启”后，新机 `sys.boot_completed=1`，正式模块版本为 `0.3.0-dev` / code 3，无 disable/remove/update 标记。正式 ELF、service/action/uninstall 脚本权限为 0755。首次 `/data/adb/sayin-helper` 尚不存在，采集/保活默认 false、App 签名列表为空，未发现采集进程。

逐文件校验 SHA256SUMS：除管理器已移除的安装专用 `customize.sh` 外，**13 个文件全部匹配**；正式 ELF 摘要与交付包一致。不能据此宣称开机 service 脚本已有执行日志，只能确认此次安装/重启切换、完整性与默认关闭状态正确。

给 `verify_device.py` 增加 `--installed`：运行正式路径的 ELF，但继续使用随机数据目录、测试前缀和限时进程；不删除/覆盖正式 ELF、不读写日常历史。此模式的普通 shell 会被 `/data/adb` 目录权限直接拒绝，错误为 `inaccessible or not found`，而非 ELF 内的 `root_required`。断言分别验证实际失败码/路径与访问拒绝；临时 ELF 模式仍验证进程自身拒绝非 Root。

正式 ELF 的 **14/14 通过**，记录位于 `device-api37-f3ba305a-installed.json`；完成后日常采集仍关闭。

## 6. 锁屏语义缺口与 0.3.1

用户实际锁屏时，系统 Keyguard 已 showing、屏幕 off，但 TrustManager 的 deviceLocked 为 false。旧门禁只判断“是否需要身份验证”，因此没有暂停。首次交互测试在等待暂停时失败，**锁屏通过结论不成立**，没有把它记成成功。

[官方 KeyguardManager 文档](https://developer.android.com/reference/android/app/KeyguardManager)明确区分：`isDeviceLocked()` 表示用户需要身份验证才能重新访问；滑动可解锁或处于受信任状态时可返回 false。`isKeyguardLocked()` 表示锁屏界面显示，不要求身份验证。这是实现选择的语义错误，不能归因于 ROM 返回异常。

修复仍为纯 C++：保留 CE/主用户/认证锁定检查，增加 `IWindowManager.isKeyguardLocked` 和 `IPowerManager.isInteractive`；只有“锁屏未显示、设备可交互”才允许采集。每秒与读取前后均使用完整门禁，未知值/布局/服务访问失败拒绝采集。通过两台手机的框架反射确认 keyguard 事务均为 30，interactive 在 API 33 为 16、API 37 为 23。新增对“认证未锁但锁屏显示/屏幕关闭”、非主用户、CE 不可用、未知状态的测试。

0.3.1 主机 **2264 断言**、工程 **8/8**、原生构建通过；API 33 和 API 37 临时 ELF 的 **14/14 各自通过**，同一修复 ELF 摘要为：

```text
480bd86fbb29474a156f7bcf2501449c3883a990ecddbcdd0832a8c894492979
```

新增 `verify_lock.py`：用户协助锁/解锁，真实 adb shell 写入生成文字，不因应用在锁屏失去焦点而伪造“已写入”。仅测试前缀可进入私有测试目录。门禁状态必须稳定 2 秒，避免把父进程初始 false 当作已检测到锁屏。支持正式 ELF 或临时修复 ELF，后者不修改已安装模块。

本轮临时修复版交互验证 **4/4 通过**：解锁基线 3 条可采集；清空后锁屏写入 3 条，历史仍为空；解锁无补入，随后 3 条新内容全部采集且历史仅含新前缀；结束后日常采集仍关闭。证据为 `lock-api37-f3ba305a-temporary.json`，ELF 摘要与上文 0.3.1 一致。首次 0.3 失败证据另存为 `lock-api37-f3ba305a.json`，不覆盖失败历史。

0.3.1 已传至新手机 `/sdcard/Download/sayin-helper-0.3.1-dev-arm64.zip`，276819 字节，手机/电脑 SHA-256 一致：

```text
544cfdef2fbc509e74c1940aa621aaec881d7cae4f51712e9254a59b8e92ca7a
```

交付时用户需通过管理器更新并重启，当时正式目录仍为 0.3。用户随后完成升级，实际核对结果见下一节。

## 7. 0.3.1 正式升级后的最终核对

用户再次反馈“已重启”后，新机正式目录为 `version=0.3.1-dev` / `versionCode=4`，`sys.boot_completed=1`，没有 disable/remove/update 标记。13 个安装文件摘要匹配；正式 ELF SHA-256 为 `480bd86fbb29474a156f7bcf2501449c3883a990ecddbcdd0832a8c894492979`，与此前锁屏/解锁 4 项验证的临时 ELF **逐字节身份一致**。不额外让用户重复锁/解锁；本次没有声称再次在正式路径进行那 4 项人工协助流程。

再次实际执行：

```powershell
python root-helper/tests/verify_device.py --serial f3ba305a --installed --allow-synthetic-clipboard-writes
```

正式 0.3.1 的 **14/14 全部通过**。`device-api37-f3ba305a-installed.json` 保存最新正式 ELF 结果，`installed-files-api37-v031.json` 保存升级文件/默认状态核对。日常 enabled 文件不存在；在这个前提下显式执行正式 `service.sh` 正常返回，保持关闭，没有初始化日常历史。

本阶段代码提交为 `a9a03b1`（Android 17 profile、门禁修复、验证工具和记录），已推送 GitHub；本节为用户升级后的验收补记。没有将其他 Android/桌面 agent 的工作纳入本阶段提交。

当前完成：本机 KernelSU 管理器安装升级和重启后切换、默认关闭、正式路径隔离采集/过滤/持久化/停止故障回归、双机新 ELF 回归、真实锁屏及恢复。未完成：管理器 Action 实际启用/关闭、开启采集后的跨重启恢复、真实卸载、长期功耗、更多 ROM；App 面板读取及保活仍未实现。默认日常采集继续关闭。
