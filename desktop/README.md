# SayIn Windows

Rust 原生桌面应用，使用相邻目录 `Yue-GPUI-Component` 的 Yue 控件与 Tabler 图标。已接入持久接收核心、Windows 输入适配器、安全配对与 TLS 1.3/WSS 手机通信。

## 运行

需要 Windows x64、Visual Studio C++ 工具和 Windows SDK。项目通过 `rust-toolchain.toml` 固定 Rust 1.99.1；不修改系统默认工具链。

本项目仍使用本地 Yue path 依赖及维护补丁。首次克隆时，将 <https://github.com/jinghu-moon/GPUI-Yue-Component> 克隆到与 SayIn 平级的 `Yue-GPUI-Component` 目录，并检出 `desktop/yue-source.json` 记录的 revision。目录名参与相对路径解析；源码摘要检查必须通过，再执行构建。

在 VoiceInput 根目录执行：

```powershell
.\scripts\desktop.ps1
```

默认配置位于 Windows 当前用户的 `%APPDATA%\seeyuer\VoiceLink\config\preferences.json`，以 `directories::ProjectDirs` 的实际解析结果为准。可指定独立测试目录：

```powershell
.\scripts\desktop.ps1 run --locked -- --data-dir D:\Temp\VoiceLink-Test
```

该参数必须使用绝对目录，不影响正常配置。语言/外观立即切换，后台串行原子保存；损坏文件保留并提示重试，不静默覆盖。只有明确的 English/简体中文选择会覆盖系统，跟随系统读取 Windows UI 语言优先级；不支持的语言最终回退 English。

接收账本位于同一数据目录的 `receiver/`：SQLite WAL/FULL、DPAPI 保护的 HMAC 密钥、载荷及全文历史。正常重启在网络许可有效时自动待命，显式暂停和 uncertain 不自动越过。不要单独删除数据库或密钥来“修复”接收故障，这会破坏去重身份。

先选择「配对手机…」，再选择与手机处于同一局域网的 IPv4 接口。专用/域网络默认可用；公用网络需勾选「本次会话允许所选公用网络」，然后点击「开始配对」。无需更改 Windows 网络类型。电脑显示二维码和 8 位代码，手机扫码或输入电脑地址、端口、代码二选一；两种方式都需在电脑批准。网络策略直接使用 Windows Network List Manager；后台约每 2 秒重新核对，网卡、IP、Windows 网络 GUID 或类别集合变化，以及查询失败/超时，会关闭监听并暂停输入。公用网络授权仅本次会话有效，切换接口、刷新列表或退出也会清除。不自动更改防火墙；如系统询问，允许 SayIn 使用所选可信网络对应的类型（公用网络需该类型下的入站许可）。无需关闭防火墙。

配对批准后自动待命，手机发送文字时捕获电脑当前前台输入目标，不需要手工锁定窗口或桌面逐条确认。不抢焦点、不自动追加 Enter。授权设备可在电脑撤销；没有 mDNS、IPv6 或自动选择其他网络。

支持托盘常驻，可设置关闭主窗口后的行为；从托盘打开可恢复隐藏或最小化的主窗口。历史保留每条原文、来源设备、执行时目标软件和结果，并支持复制原文。退出时等待已开始操作的结果和设置持久化。

## 验证

```powershell
python scripts/check_desktop_dependencies.py
.\scripts\desktop.ps1 fmt -p voicelink-desktop -p voicelink-receiver -p voicelink-pairing -p voicelink-transport -- --check
.\scripts\desktop.ps1 clippy --workspace --all-targets --locked -- -D warnings
.\scripts\desktop.ps1 test --workspace --all-targets --locked
.\scripts\desktop.ps1 build --workspace --locked
```

只格式化上述四个项目包，不要 `cargo fmt --all`：Cargo 会遍历外部 path 工作区并格式化引用库。本项目不修改该外部库。

`Cargo.lock` 提交管理；Kit 的精确约束与三个补丁不可移除。`yue-source.json` 记录源文件摘要；更新引用库后先审查、构建、测试，再显式运行 `python scripts/check_desktop_dependencies.py --record-source`。

主题使用 neutral 控件，品牌角色固定 `#1f75db`；不启用默认 Azure 或彩色状态变体。Yue 当前没有完整自定义 accent API，因此不冒充已经覆盖所有组件的 accent token。

基础测试覆盖 FTL、语言协商、设置原子保存/损坏/失败，以及生产界面的指针、方向键、切换焦点、快速变更合并、保存失败重试和最小窗口几何。GPUI 采用 headless 窗口执行，不等同于 D3D 实机截图验收。

接收测试另覆盖真实 SQLite/DPAPI、子进程不清理退出、事务失败、去重/会话隔离、暂停竞态、队首阻塞、配额、历史计数与查询计划、满队列退出，以及界面启动/失败重试/取消锁定/关闭期间初始化。包含原生接收线程的 GPUI 集成测试按框架官方能力启用 `allow_parking`，与纯确定性视图测试分开理解。

最新实施与验收边界见 [16 桌面重构](../docs/16-desktop-ui-refactor-implementation.md)、[17 托盘窗口恢复](../docs/17-tray-main-window-restore-fix.md)、[20 输入权限修复](../docs/20-native-input-integrity-and-tranvoice-comparison.md) 和 [21 SayIn 品牌](../docs/21-sayin-brand-name-and-icons.md)。用户已确认实际落字可用；独立测试使用假输入的结果仅证明该测试链路，不等同于普通应用落字或延迟指标。设备日志、验收截图及 `docs/research/` 为本地验证材料，未纳入源码仓库。
