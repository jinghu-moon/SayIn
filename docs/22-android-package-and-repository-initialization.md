# 22 · Android 最终包名与源码仓库初始化

日期：2026-11-13。

用户确认 Android 最终包名采用 **`seeyuer.sayin`**，并明确项目处于开发期、尚未正式发布，不考虑兼容性。本次直接修改应用身份，不增加旧包适配或数据迁移逻辑。

## 包名调整

| 范围 | 结果 |
| --- | --- |
| applicationId / namespace | `seeyuer.sayin` |
| 主源码、单元测试、仪器化测试 | 统一迁至 `seeyuer/sayin` 根目录，更新 package/import |
| Android 测试包 | `seeyuer.sayin.test` |
| JNI 短码配对 | Rust 导出调整为 `Java_seeyuer_sayin_pairing_NativePairing_pairCode`，重新构建并打包原生库 |
| Room schema | 移至 `schemas/seeyuer.sayin.data.OutboxDatabase/1.json`，数据库结构未变 |
| 工具脚本 | 更新 i18n 扫描、Tabler 生成目标目录和真机测试入口 |
| 项目名称与图标 | SayIn / B-field |

新包作为独立应用安装，首次使用需要重新配对。旧开发包未卸载或清除数据；它不参与新包运行。历史文档和日志中的旧包名是当时的事实记录。Windows 的 app_id、配置目录与内部 crate 名称属于电脑端独立身份，本次 Android 包名调整不涉及它们。

## 验证

基线沿用上一轮 21 的完整工作区与双端构建记录；本轮执行：

```powershell
.\scripts\gradle.ps1 :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
python scripts/check_i18n.py
python scripts/check_desktop_dependencies.py
.\scripts\desktop.ps1 test --workspace --all-targets --locked
.\scripts\desktop.ps1 clippy --workspace --all-targets --locked -- -D warnings
.\scripts\desktop.ps1 fmt -p voicelink-desktop -p voicelink-receiver -p voicelink-pairing -p voicelink-transport -- --check
.\scripts\desktop.ps1 build --workspace --locked
.\scripts\verify_android_transport.ps1 -DeviceSerial <serial> -UseExistingRootInstall -SkipBuild -TransportOnly
```

- Android Lint、5 项本地单元测试、应用及测试 APK 构建通过。
- 桌面工作区 93 项回归通过，Clippy、格式、构建、i18n 和依赖检查通过，未修改相邻 Yue 工程。
- 已使用设备现有 Root 授权安装新包。APK label 为 SayIn，启动入口为 `seeyuer.sayin.MainActivity`；已安装 APK 与本地 APK 的 SHA-256 一致。
- 独立真机通信回归通过 1 项：实际调用新包 JNI 进行短码配对、固定证书 TLS、加密 Outbox、held 到期与重连恢复；独立 USB reverse/loopback 假输入服务累计执行恰好 1 次。该测试不访问用户的配对、Outbox 或电脑账本，不证明普通目标软件落字或局域网延迟指标。
- 曾尝试完整 11 项仪器化套件，前 6 项完成，第一个前台 Compose 用例未进入前台，旧包仍是 resumed Activity；已停止该测试进程。完整 UI 套件没有通过，独立通信回归随后重新执行通过。没有更改 MIUI 后台启动权限。
- 新包 MainActivity 已实际启动。旧桌面程序保持运行；构建时将占用中的旧产物移至同一 `target/debug` 目录的临时名称，再生成新的 `sayin.exe`，未强制终止桌面进程。

详细设备日志、哈希、构建输出及截图保存在本地 `docs/research/22-*`；它们不随源码推送。

## Git、许可证与首次提交

仓库：<https://github.com/jinghu-moon/SayIn>，本地主分支为 `main`，远端名为 `origin`。初始化前已确认远端为空，首次推送使用普通推送，不覆盖远端历史。

根目录 [MIT 许可证](../LICENSE) 适用于项目自有源码与文档。Tabler 原始许可证及来源清单保留，Gradle Wrapper 使用 Apache-2.0，详见 [第三方说明](../THIRD_PARTY_NOTICES.md)。依赖源码继续按原许可证授权。

纳入源码、资源、Gradle Wrapper、Cargo 锁文件、Room schema、脚本、设计 demo 与方案文档。`.gitignore` 排除参考工程、构建与 IDE 状态、机器 SDK 配置、AI 工具状态、设备/验收日志、`docs/research`、签名密钥、环境凭据和运行数据库；原文件仍保留在本机。提交前检查暂存清单、大小和常见凭据格式，并执行 `git diff --cached --check`。

首次克隆的构建要求见双端 README；Windows 仍依赖平级目录的 Yue 源码及其固定摘要，不将相邻工程复制进 SayIn 仓库。
