# 27 · 桌面端设置改用下拉选择器

日期：2026-11-13。

## 修改范围

设置页的界面语言、外观、关闭窗口行为由展开的单选按钮组改为现有 Yue `YueSelect` 单选组件，移除卡片内重复的字段标题。保留原有选项、说明、自动保存和保存失败重试。

| 项目 | 修改前 | 修改后 |
| --- | --- | --- |
| 选项展示 | 单选按钮展开占据多行 | 下拉框展示当前值，打开菜单选择 |
| 字段标题 | 卡片标题与字段标签重复 | 卡片标题同时用作无障碍名称 |
| 键盘操作 | 单选组导航 | 方向键导航、Enter 确认、Escape 取消并返回焦点 |
| 窄窗口 | 展开的选项占据空间 | 控件最大宽度 20rem，随可用空间收缩 |

网络接口选择不在本次设置页替换范围内。

## 状态与行为

`Shell` 保留 `SettingsControls` 及其三个选择器实体和事件订阅；`PreferencesState` 仍是持久偏好的唯一来源。用户选择通过 `SelectEvent::Change` 映射到类型化偏好，仅值变化时保存。

偏好通知通过选择器的静默 setter 同步本地化选项、当前值与禁用状态，不产生用户选择事件，不重复保存。语言切换保留控件实体和焦点，不影响正文、发送或重试。说明文案继续作为无障碍描述。

## 验证

修改前设置页测试基线 13 项通过。修改后复用原测试并操作实际 ComboBox/ListBoxOption，验证语言切换后焦点、键盘选择、Escape 取消、主题、保存失败重试、关闭行为及最小窗口。

以下检查全部通过：

- `cargo test --workspace --all-targets --locked`：104 项测试。
- `cargo clippy --workspace --all-targets --locked -- -D warnings`。
- `cargo build --workspace --locked`。
- `cargo fmt -p voicelink-desktop -p voicelink-receiver -p voicelink-pairing -p voicelink-transport -- --check`。
- `python scripts/check_i18n.py`。
- `python scripts/check_desktop_dependencies.py`。
- `git diff --check`。

通过 `verify_desktop_shell` 使用真实 Shell 和原生 D3D 渲染，在简体中文/English、明/暗主题、720/1000/1200/1600 宽度下生成页面与展开菜单截图，并检查菜单对齐和视口边界。渲染使用隔离账本，不操作真实输入目标。

本地日志与截图位于未纳入 Git 的 `docs/research/28-settings-*`。本次未改 Android，也未重启用户正在使用的旧桌面进程；新程序位于 `desktop/target/debug/sayin.exe`，完全退出托盘旧版后打开即可。
