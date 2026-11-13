# 安全配对与手机通信实施记录

日期：2026-11-12。实现阶段记录，不等于安全审计、真机跨网验收或真实目标应用输入验收。

> 后续更新：本文保留当时的 Private/Domain 限制与 53 项测试记录。当前已支持公用网络的本次会话授权，无需修改 Windows 网络分类，见 [11 公用网络授权](11-public-network-session-authorization.md)。

## 本阶段结果

- Windows 桌面接收启动后可由用户选择一个 Windows Private/Domain IPv4 接口，网络服务仅绑定该地址；7788 被占用时使用系统分配的端口。没有 mDNS、IPv6、后台服务或自动防火墙规则。直接使用 Windows Network List Manager 启动前及监听期间约每 2 秒核对策略；3 秒检测超时或所选接口不再允许时关闭服务并暂停输入。周期检测存在窗口，实际网络切换仍需专门验收。
- 用户打开 120 秒配对窗口，桌面展示二维码、8 位配对码、地址和有效期提示。手机扫码和手工输入地址/端口/配对码是二选一。两条路径均建立主机限定的 TLS 1.3 WSS `/v2` 连接，然后由用户在电脑确认设备名。
- 手工代码路径在同端口使用 `VLCP2\n` 帧和固定 1Password CPace Git revision `7e2e42c7d359969f4356292c2bd2cf3ff6eeeecc`，短码不单独当成 TLS 证书密码。上游审计没有审计本项目协议与 UI。
- Android 仅信任配对邀请中的叶证书；仍执行 `vl-<host_id>.local` SAN/hostname 校验，DNS 只映射邀请里的数字 IPv4，TLS 仅允许 1.3。未提供 trust-all HostnameVerifier。
- Android 已实现生命周期管理、最多 64 条单一有界状态邮箱、TLS/WSS、心跳、设备身份/令牌加密存储、Room WAL Outbox、pending 条目/字节配额、最终文字规范化预览、已知回执恢复、相同 ID 查询和显式 unknown/review/uncertain 状态。前后台切换时断开/重连。
- 手机发送前先持久化加密 Outbox。重新连接先查询同 host/device 的待处理 ID，不盲目重发。未知结果需用户检查目标后才能以相同 UUID 重试；终态回执写盘后清除载荷；不自动追加 Enter。
- 双端用户界面具备简体中文与 English。电脑显示已授权 UUID，并提供需明确确认的撤销控件；撤销会暂停输入、取消尚未执行的请求并使 token 失效。未持久保存手机昵称；未实现多设备活动发送者切换 UI、mDNS、通知/后台保活、语音识别、root/IME 接入。

## 验证证据

| 验证 | 结果 |
| --- | --- |
| CPace、错误码、上下文绑定、邀请篡改、QR/bounds 单元测试 | 3 passed |
| loopback CPace + TLS 1.3 + WSS + 本地批准 + fake input adapter 端到端测试 | 3 passed |
| 全 Rust workspace tests | 53 passed；含本地授权撤销明确确认、取消未执行操作和令牌失效；详见 [verification JSON](research/10-secure-pairing-verification-2026-11-12.json) |
| Rust workspace Clippy `-D warnings` | pass |
| Android Debug arm64-v8a 构建、lintDebug、Debug androidTest 构建 | pass；lint 有 16 条建议/平台警告，无错误。含 AGP 升级、arm64-only、平台 Locale、主机专用 TrustManager、同步 SharedPreferences commit 和扫描库覆盖资源 unused 警告；没有屏蔽翻译检查 |
| Android JVM 测试 | 5 passed；Unicode 规范化、界限、IPv4 和 UUID |
| Xiaomi Android 13 仪器化测试 | 6 passed；Room 配额/授权范围、JSON 严格解析，以及真实 JNI/OkHttp/Keystore/Room 通信链路 |
| 双端本地化完整性与 Yue 依赖边界 | pass |
| Windows Network List Manager 运行检查 | pass；本机允许接口为 0；PowerShell 独立核对两个接口均为 Public，因此产品拒绝监听 LAN |
| 实际手机到 Windows 同网配对、跨网/防火墙场景、Windows target HWND 的真实文字落字、时延与续航指标 | 未完成 |

## 当前需要完成

1. 在可信、专用 Windows 网络与 Android 设备上完成真实扫码和 CPace 手输双路径配对；确认 Android 37/API 37 本地网络权限与目标 SDK 行为。
2. 补多设备活动发送者切换 UI 和更易辨认的设备昵称；核对撤销后的活跃连接 fencing、设备授权泄漏/未送达 token 的恢复流程。
3. 对网络 profile 变更、地址租约变化、休眠/唤醒、证书过期、手机应用进程被系统回收开展恢复验证。
4. 在真实的普通权限文本编辑目标中验证 Unicode、焦点竞态、暂停/目标切换和恢复确认。不得用 fake adapter 测试证明真实输入成功。
5. 通过采样时间戳/性能剖析建立 p50/p95/p99 和资源占用基线；当前没有可报告的低延迟或高性能实测数字。

详细协议见 [v2 设计](01-voicelink-protocol-v2.md)。

## 可重复运行

```powershell
.\scripts\gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
.\scripts\verify_android_transport.ps1 -DeviceSerial dc39c31d -UseExistingRootInstall
```

普通 `connectedDebugAndroidTest` 首次因 MIUI `INSTALL_FAILED_USER_RESTRICTED` 取消测试包安装。沿用 [06 已授权安装方式](06-android-device-installation.md) 后，6 项仪器化测试实际通过。未禁用包验证器或修改 USB 安全策略。独立测试服务仅绑定 `127.0.0.1:17888`，通过 `adb reverse` 映射到手机；仅该测试服务自动批准，使用 fake input。配对、发送、socket 重连及重建手机 Repository 后，电脑替身输入计数始终为 1。该结果不证明物理扫码、Wi-Fi、防火墙、Windows GUI 实际批准交互或目标程序可见文字已验收。

测试配对/Outbox 使用独立 SharedPreferences/Room 命名空间，不覆盖用户的真实电脑授权。脚本会清理临时 APK、端口转发和测试邀请文件；当前用户无需进行安装操作。Android 首页已实际查看，截图在本机忽略路径 `android/app/build/verification/pairing-home.png`，未生成桌面窗口截图。
