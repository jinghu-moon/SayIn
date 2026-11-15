# 40 · 已配对电脑自动发现与重连实施记录

日期：2026-11-14。状态：代码与双端回归已完成；真实局域网端口迁移已验证，尚未实测的边界单独列于第 8 节。协议权威说明为 [01 第 6、7 节](01-voicelink-protocol-v2.md)，本文件记录实现、实验及交付证据。

## 1. 根因与共同基线

原实现把保存的 IPv4/端口同时当作连接入口和唯一定位手段。电脑网络恢复后能够重建监听，手机也会重连旧端点，但电脑获得新地址或选择新端口时，两端都没有发布/发现同一主机新路由的链路。重复连接旧地址不能解决定位问题；重新配对则不必要地改变信任。

本轮把路由与身份分开：mDNS/DNS-SD 只给出候选 IPv4/端口，原固定证书、TLS 名、host/device 身份和 token 继续决定它是不是原电脑。正常恢复不增加确认、多电脑选择或必选网卡。

实施前核对 `AGENTS.md`、[37 规划第 6 节](37-new-features-plan.md)、[29 接收页](29-desktop-receive-ui-refactor.md)、[34 手机说话与连接](34-android-speak-and-connection-refactor.md)、[37 统一说话实施记录](37-android-unified-speak-and-desktop-coordination.md)及调用代码。由主 agent 完成实施与最终集成。

- 原主分支 HEAD：`96a3aaf71a650c119510be5e99283929fab45acb`。
- 工作区现有修改的共同快照：`aee02944c67c3e54a7511aa80672089ec2f747b8`，tree 为 `cfad17fd3db9a79fc9e1e92febaaa6e37d0b8779`。
- 隔离 worktree：`../.zcf/VoiceInput/paired-host-discovery`，分支 `codex/paired-host-discovery`；共享快照使用独立临时 index，未修改主工作区 index，未 reset、stash 或覆盖其他 agent 内容。
- 集成前再次用独立 index 对比主目录，排除本地模型后 tree 与共同快照完全一致。正式交付按既有双端工作与发现增量分组提交；本地临时快照不是推送到主分支的交付提交。
- `assets/models/`、`assets/translation-model/`、本地 ASR、凭据、运行数据、构建产物及 `docs/research/` 不进入提交。研究证据仅在本机保留。

## 2. Windows 发布与监听生命周期

`desktop/crates/transport/src/discovery.rs` 使用 Windows 原生 `DnsServiceRegister`，没有新增第三方发现 crate。服务类型为 `_voicelink._tcp.local`，实例名为 `SayIn-<host_id>`。TXT 只包含 `host_id`、`v=2`、当前绑定 `ipv4`；SRV 使用当前实际端口，不发布证书、token 或配对秘密。

发布复用 `NetworkBinding` 已验证的适配器 GUID、IPv4 与 Windows 网络范围。请求和服务实例都使用同一非零 interface index；索引为零意味着全部接口，不能作为失败后的兜底。生产 TCP 仍精确绑定原选中 IPv4，loopback fixture 默认不发布。

发布对象由实际 listener 的网络任务持有。旧监听停止、网卡/地址/范围失效时先撤销发布，再结束旧网络会话；恢复后由新有效 listener 创建新发布。异步注册最多等待 5 秒，取消与成功竞态由状态机处理，迟到成功也要撤销；FFI 完成回调独立持有引用，不能提前释放原生请求/实例。DNS 缓存可能暂留旧记录，认证和候选期限仍必须执行。

发布不可用时不关闭仍然有效的精确地址监听，已知端点仍可连接；接收页网络详情显示正在发布、已发布或不可用。没有修改防火墙、Windows 公用/专用类别或改为监听全部网卡。

### 真实 LAN 实验发现的问题

首轮把 SRV 主机写为自定义 `vl-<UUID>.local`，真实 Android NSD 能发现实例却解析不出 IPv4。Windows 本机实验表明，注册服务并不为这个自定义主机创建可用 A 记录，不能把服务注册完成当作解析成功。

修正为系统实际 `<computer DNS hostname>.local`；Android 从解析结果中仅选择与 TXT `ipv4` 一致、位于当前 LAN IPv4 前缀内的地址。发现 DNS 名与固定证书的 `tls_name` 完全独立，不能为了 DNS 解析而接受新证书或关闭 SAN 校验。修正后真实 LAN NSD、连接、认证及端口持久更新均通过。

## 3. Android 发现、授权与调度

`HostDiscovery.kt` 封装系统 NSD；`LanNetwork.kt` 观察 Wi-Fi/以太网及链路地址；`VoiceLinkRepository` 的单一 mailbox 继续拥有连接 generation、候选、认证和持久保存。

| 阶段 | 当前上限与处理 |
| --- | --- |
| 首选端点 | 每回合先连接保存的 IPv4/端口，期限 12 秒 |
| 服务浏览 | 先等待 2 秒收集结果，本回合最多处理 8 个名称匹配实例 |
| 解析 | 单个 2 秒；整个发现回合 12 秒，包括浏览与解析，结束停止浏览 |
| 候选 | 最多 4 个去重 IPv4/端口，串行尝试；每个候选完整连接/认证最多 6 秒 |
| 回合间重试 | 统一现有 1/2/5/10 秒退避，上限 10 秒；不是总恢复时长上限 |
| 权限变动 | 有连接需求期间每秒检查一次；撤权取消连接、发现和网络观察，恢复后允许重试 |

名称及 TXT host_id/version 是廉价过滤器，均不可信。仅接受当前 LAN IPv4 前缀内、与 TXT ipv4 一致的解析 IPv4；排除 IPv6、loopback、任意地址、组播和链路本地地址。API 33+ 浏览绑定选中的 LAN Network，连接使用同一 Network 的 socket factory；旧系统浏览使用系统能力，仍对解析结果做当前前缀过滤。不扫描全网段，不进入公网中转。

targetSdk=37，Manifest 声明并按需请求 `ACCESS_LOCAL_NETWORK`；仓库层也检查许可，不能只依赖 UI 按钮。无许可时不建立连接或开始发现；发送 hello/token 前再次检查。本记录初版没有 API 37 系统授权界面证据；2026-11-15 已补验拒绝/允许、撤销后重启及有界 NSD，见 [49](49-android-platform-support-and-validation.md) 和第 8 节。

可见 Activity 或用户已开始录音才产生连接需求。退出需求、切网、取消或存储阻断会取消任务、停止观察、清候选并递增 generation。新发现/连接只能接受同代结果；非合作的迟到回调也不能替换连接或保存地址。

API 34+ 使用 `registerServiceInfoCallback` 并在 finally 注销；旧系统短时持有 MulticastLock，结束释放。支持 T 扩展 7 的旧解析可以请求停止，停止回调与普通完成回调只释放一次占位。更老系统没有可用的停止解析 API，只保留一个 OS 在途解析占位，等待其回调期间不叠加；业务已取消，迟到结果不使用。这一系统限制不能描述成 OS 操作立即撤销。

## 4. 候选认证与接收安全语义

候选只通过 `PairedHost.withAddress` 创建临时路由，沿用原完整 DER 固定证书、原 `tls_name`、host UUID、device UUID 和 token。TLS 1.3、固定证书及默认 SAN 校验通过并触发 WebSocket Open 后，才发送原 hello 凭据。错误证书测试实际得到 TLS 握手异常，未发生 Open，因此没有机会发送 token。

收到 welcome 后还需验证原 host/device、会话 ID 及完整状态；全部成功后才加密持久保存同一主机的新 IPv4/端口。不接受新证书、不自动配对，不因相同显示名/伪造 host_id 而信任候选。撤销设备不能通过 hello；新路由不能持久替换原保存端点。

重连继续先 query 原 Outbox 请求。它不创建 submit、不自动 release 旧 held、不解除主动暂停、存储阻断或 uncertain，不清理草稿、历史或原身份。连接代次进入 ReceptionScope，旧连接的自动发送票据在同地址快速恢复时也无效。

## 5. 双端界面收尾

本轮将此前按 demo 实现的双端界面共同验收并纳入交付：手机完整说话页与快捷卡共享会话/草稿/发送规则，桌面接收页提供真实恢复状态，历史支持对应应用图标和宽屏多列。详细交互与此前基线见 [37 统一说话实施记录](37-android-unified-speak-and-desktop-coordination.md)。

发现状态复用现有连接面板和网络详情：正在查找原电脑、权限拒绝、未发现和候选认证失败使用中英文资源；失败仍保留手动地址维护入口，不引导删除配对或反复确认电脑。正常恢复不新增选择步骤。

原生 `verify_desktop_shell` 在隔离目录完成中英/明暗、720×540 至 1600×1000、四页及菜单/关闭交互，产出 113 张 PNG；已抽查接收页与英文暗色三列历史。Android 真机布局覆盖快捷录音态、320dp/150% 字号、横屏键盘；额外原生 QuickActivity 检查通过，输入法弹出后编辑行及 48dp 发送目标可见。截图使用模拟识别/隔离测试草稿，不代表真实麦克风录音。

## 6. 修改前后回归结果

全部构建、输入 fixture 与数据都与日常电脑身份、账本、端口隔离。手机沿用现有授权覆盖安装，不卸载、不清日常数据、不导出凭据；未调用付费云测试或开启真实麦克风。没有退出日常桌面进程或覆盖它正在运行的 exe。

| 检查 | 修改前 | 最终结果与证据 |
| --- | --- | --- |
| Android JVM | 42 项通过 | 45 项，0 失败/错误/跳过；JUnit XML 及 `discovery-android.log` |
| Android 构建/Lint | Debug、AndroidTest、Lint 通过 | 同项通过；0 error、77 warning、3 hint，未屏蔽错误；Lint XML |
| 常规设备套件 | 37 项通过 | 37 项通过；恰好两次 fake 输入（injected、uncertain），查询/重连/重建无重放；`discovery-device-full.log` |
| 独立发现 recover | 无 | 1 项通过；错误证书、多候选、原身份不变、旧 held 查询、旧 scope 拒绝、模拟撤权与迟到回调取消 |
| 独立发现 revoked | 无 | 1 项通过；撤销身份不能认证/保存新路由 |
| 独立发现真实 LAN | 无 | 1 项通过；Redmi Wi-Fi → Windows 以太网，真实系统 NSD 找到新端口并认证，无 ADB reverse |
| Rust 完整工作区 | 108 项通过 | 111 项通过，无失败/忽略；新增 IPv4+端口迁移集成、发布取消竞态测试 |
| Rust 静态检查/构建 | 既有阶段基线 | workspace/all-targets Clippy `-D warnings`、workspace build、包限定 fmt 通过 |
| 资源/依赖 | 既有检查可运行 | i18n、Yue 依赖图与源码摘要、`git diff --check` 通过 |
| 原生 UI | 37 阶段截图与设备测试 | 桌面 113 张渲染图；QuickActivity IME 原生窗口检查通过 |

真实 LAN 环境：Redmi K40（API 33、T 扩展 3）`192.168.71.72/24`，Windows 所选以太网 `192.168.71.6` / interface 4，另有 VPN 与链路本地接口。测试没有切换网卡、改网络类别或自动开放防火墙。每个发现 fixture 恰好一次 fake 输入，迁移后 paused 保持为 true，未增加执行次数。

基线首次设备检查曾因 worktree 尚未生成非 hashed fixture exe 失败；另一次把构建与安装并行导致复制中的 APK 被改写而安装失败。补建 fixture，并将构建与安装严格串行后重新执行成功。真实 LAN 首轮解析失败及修正见第 2 节；最终结果不把这些失败算作通过。

本机原始证据保留于 worktree 的 `baseline-*.log`、`discovery-*.log`、`android/discovery-*.log`、`android/discovery-*-result.json`，以及 `docs/research/40-*`。整理后的摘要与日志同时复制到主工作区 `docs/research/40-verification/`，属于忽略目录，不能当作 GitHub 必然可下载的附件。

## 7. 重跑命令

仓库根目录执行；使用本项目固定 Temurin 25，不改全局 Java。先完成构建，再覆盖安装，不能同时写入正在传输的 APK。

```powershell
.\scripts\gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
# 以下 Cargo 命令在 desktop/ 执行
cargo test --workspace --all-targets --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo build --workspace --locked
cargo build -p voicelink-transport --examples --locked
cargo fmt -p voicelink-desktop -p voicelink-receiver -p voicelink-pairing -p voicelink-transport -- --check
# 回到仓库根目录，设备解锁且保留既有 root 安装授权
.\scripts\verify_android_transport.ps1 -DeviceSerial dc39c31d -UseExistingRootInstall -SkipBuild
.\scripts\verify_android_discovery.ps1
.\scripts\verify_android_discovery.ps1 -Revoked -SkipInstall
.\scripts\verify_android_discovery.ps1 -Lan -SkipInstall
python scripts/verify_android_quick_window.py --device dc39c31d --use-existing-root --output docs/research/40-mobile-window
python scripts/check_i18n.py
python scripts/check_desktop_dependencies.py
git diff --check
```

独立脚本分配随机 host/device、数据目录及端口，只在测试例程自动批准配对并使用 fake 输入；结束删除临时配对码，不打印或提交密钥。LAN 模式没有反向隧道，不能用 loopback 结果替代该模式。

## 8. 剩余限制与未验证范围

- 真实 LAN 已验证同一电脑端口变化；**实际 DHCP/手动变更电脑 IPv4** 尚未执行。IPv4 与端口同时变化目前由 `127.0.0.1 → 127.0.0.2` 的真实 TCP/TLS/hello/query 集成测试覆盖，不能称为真实 LAN 换 IP 验收。
- 未实际切换多个物理网卡，未构造访客隔离/多播被阻断网络。已有前缀过滤、接口绑定、取消和空结果/超时路径，不能替代这些环境验收。
- API 33 真机真实 LAN NSD 已通过。2026-11-15 另在 API 37 模拟器实际完成系统本地网络授权拒绝/允许、撤销后进程重启且身份保留、有界 NSD/取消，见 [49](49-android-platform-support-and-validation.md)。未验收 API 37 OEM 真机或进程存活时的撤销时序；模拟器配对走 ADB reverse，不能替代物理 LAN。
- 旧 Android 无停止解析 API 时，OS 单个解析可能继续等待回调；业务任务已失效且不叠加，不承诺 OS 立即结束。
- 不做 IPv6、跨 VLAN、全网段扫描、公网中转或空闲后台常驻发现。多播无法到达时保留手动地址更新；DNS 字段可被伪造/阻断而造成不可用，但不能越过原固定证书身份认证。
- 没有重新实测真实语音到普通目标应用落字、云识别质量/费用、长期 OEM 后台行为或功耗/延迟分位数；不能把本轮 fake 输入、截图及单次恢复时长当作这些指标的达标证明。

## 9. 官方依据

- [Android NSD 指南](https://developer.android.com/develop/connectivity/wifi/use-nsd)、[NsdManager API](https://developer.android.com/reference/android/net/nsd/NsdManager)。
- [Android 本地网络权限](https://developer.android.com/privacy-and-security/local-network-permission)。
- [DnsServiceRegister](https://learn.microsoft.com/en-us/windows/win32/api/windns/nf-windns-dnsserviceregister)、[注册请求及接口范围](https://learn.microsoft.com/en-us/windows/win32/api/windns/ns-windns-dns_service_register_request)。
- [DnsServiceDeRegister](https://learn.microsoft.com/en-us/windows/win32/api/windns/nf-windns-dnsservicederegister)、[完成回调](https://learn.microsoft.com/en-us/windows/win32/api/windns/nc-windns-dns_service_register_complete)、[构造实例](https://learn.microsoft.com/en-us/windows/win32/api/windns/nf-windns-dnsserviceconstructinstance)。
