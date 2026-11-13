# VoiceLink：Windows 基础工程实施记录

日期：2026-11-12。基础代码、Windows Debug 构建和自动化交互检查已完成；实机视觉验收待完成。

后续接收核心和桌面接线已继续实施，当前状态见 [09 接收核心实施记录](09-desktop-receiver-core-implementation.md)。下文 11 项测试与窗口生命周期描述是基础工程交付时的验证快照，不代替后续工作区验证。

## 1. 本次交付

- `desktop/` 独立 Rust 工作区，锁定本机官方渠道核查到的 Rust 1.99.1；安装独立版本，不修改系统默认工具链，不卸载旧版本。
- 本地 Yue 组件、系统字体 Segoe UI/Microsoft YaHei UI、内置 Tabler 图标。消费方精确约束 Kit `=0.8.0`，显式配置三个本地补丁。
- 连接、接收记录、设置三个同级 YueTabs 页面，未实现服务显示未就绪，配对按钮禁用，无假设备/假回执。
- 品牌角色 `#1f75db`；Yue `Accent::Neutral` / `NeutralTone::Neutral` 提供全部普通表面和控件。当前库没有完整 custom accent API，不能声称已将所有组件主色修改为蓝色；应用品牌图标和 Base 主色/焦点角色使用指定品牌值，不使用默认 Azure 的其他蓝色或彩色状态。
- Fluent 中英文资源和稳定类型化消息键。跟随系统读取 Windows 有序 UI 语言列表，窗口激活时重新读取；显式语言独立保存，切换更新窗口标题。当前支持简体，不把繁体误判为已支持语言；按系统后续首选项匹配，最终英文兜底。
- 偏好设置由应用实体持有。语言/主题即时变更，后台串行原子写入，快速修改合并到最后完整快照。失败保留内存选择并显示重试；损坏配置不自动覆盖。
- 外观支持跟随 Windows、浅色、深色，订阅系统外观变化。原生窗口边框和标题栏保留，最小逻辑大小 720 × 540。

## 2. 修复与验证依据

首次不加精确约束时，Cargo 选择 Kit 0.8.1 / Base 0.8.1 / backend 0.3.10，三个本地 patch 被绕过，Yue 编译缺少维护接口。根因是 semver 范围允许升级；通过 Kit 精确约束和 lockfile 统一回到库已验证的 0.8.0 / 0.3.9 组合。`scripts/check_desktop_dependencies.py` 现检查版本、路径、features 和实际目标依赖树。

`cargo metadata` 会列出 weak optional 的 Fast 依赖节点，但 `cargo tree` 的实际目标编译树未启用它；检查实际编译树，不把 metadata 的所有包当成已链接后端。测试仅启用 Base 的 test-support。

首次交互测试发现保存时禁用 RadioGroup 会丢失焦点。已调整为应用内即时选择、后台单写入者合并，真实指针与方向键路径验证焦点保持。保存失败通过目标文件被目录占用触发，UI 保留选择，修复目录后点击重试验证落盘。

## 3. 实际检查结果

```powershell
python scripts/check_desktop_dependencies.py
cargo fmt -p voicelink-desktop --check
cargo clippy --all-targets --locked -- -D warnings
cargo test --all-targets --locked
cargo build --locked
python scripts/check_i18n.py
```

- Rust 单元测试 6 项通过；生产界面的 GPUI 窗口集成测试 5 项通过，共 11 项。
- 覆盖配对禁用、标签页指针/方向键、语言/主题选择、焦点保持、重启读取、快速修改合并、真实保存失败及重试、中英文最小窗口几何。
- Clippy `-D warnings`、应用限定 rustfmt、Debug build 通过。
- 进程真实启动，标题“声达”、原生窗口句柄非零且响应；测试启动窗口已正常关闭。旧预览进程也已清理。
- Android 原有 i18n 检查仍通过；未修改 Android 业务与构建配置。
- Computer Use 本机 native pipe 不可用，多次恢复失败。没有实机截图、鼠标或 DPI 验收证据，不能将 headless 几何测试写为视觉验收通过。
- 引用库曾被 `cargo fmt --all` 遍历；确认均为 formatter-only 后恢复内容和 checkout 行尾。当前外部库保持原内容，不交付任何引用库修改。后续只对本包格式化。

引用源修订与归一化文本摘要见 `desktop/yue-source.json`。具体构建输出位于忽略的 `desktop/*.log`；可通过上述命令复验。

## 4. 后续顺序

1. 实机检查中英文、深浅主题、Tab/方向键、最小窗口、Windows 125%/150% 缩放；工具恢复后补齐截图。
2. 持久接收核心：严格协议模型、SQLite WAL/FULL、去重、执行标记和保守崩溃恢复、串行 Win32 输入。
3. 安全配对与 WSS，GUI 订阅真实核心状态。
4. 手机持久 Outbox 和真实通信，双端联调、故障恢复及延迟测量。

基础交付时没有接收服务，关闭最后窗口会在偏好写入完成后退出。09 已加入本地接收核心的有序停止；关闭窗口后维持接收/托盘的生命周期仍待实现。
