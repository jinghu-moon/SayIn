# 21 · SayIn 命名与 B-field 图标接入

日期：2026-11-13。

用户确认实际手机到当前光标落字已可用，随后将产品正式名称改为 **SayIn**，图标使用 `assets/sayin-icons` 中的 **B-field（光标入框）**。F-yan（言字标）作为未采用的源方案保留。

## 命名范围

- 简体中文、English 均显示 SayIn，无额外中文别名。Android 应用名标记为不可翻译品牌，避免伪语言或翻译资源改变商标拼写。
- 更新 Android 启动器名称、页面品牌和扫码说明，桌面主窗口标题、页面品牌、托盘提示与菜单、权限说明和关闭提示。
- Android Gradle 工程名称为 SayIn；Windows 产物为 `desktop/target/debug/sayin.exe`，内嵌 ProductName/FileDescription 为 SayIn，OriginalFilename 为 `sayin.exe`。新增根 README 及更新双端入口说明。

Android `applicationId/namespace` 继续使用用户确认的 `seeyuer.voice.link`；Windows app_id、既有 `%APPDATA%\seeyuer\VoiceLink\config` 数据路径、数据库身份、DPAPI/Keystore 凭据、协议和 Rust 内部 crate 名称保留。名称更新不创建新配对身份或空白历史，用户无需重新配对。历史方案、参考对话及之前验收日志保留当时命名，不批量改写历史证据。

## 图标范围

全部从用户提供的 B-field 原始资源复制，不重画、不改色：

| 场景 | 资源与接入 |
| --- | --- |
| Android 启动器 | mdpi–xxxhdpi 方形/圆形 PNG；v26 自适应前景、背景及单色层；Manifest 同时声明 icon/roundIcon |
| Android 页面品牌 | 原始 PNG 全彩图像展示，保持本身颜色；业务功能图标仍采用 Tabler |
| Android 其他资产 | 保留资源包中的通知、磁贴、悬浮球 drawable；没有因图标接入而新增这些功能 |
| Windows exe | 原始多尺寸 `sayin.ico` 作为 PE 图标资源，附产品版本信息 |
| Windows 窗口/任务栏 | 显式为自家主窗口设置小/大图标，窗口关闭重开后仍加载同一资源 |
| Windows 托盘 | 深色任务栏用浅色图形、浅色任务栏用深色图形；只读 SystemUsesLightTheme，设置/主题变化及 Explorer 重启时刷新 |
| Windows 页面品牌 | PNG 图像替换原麦克风品牌占位，缓存图像，保持用户指定的深底 neutral 风格 |

Windows 图标通过已有锁文件中的 `embed-resource 3.0.12` 编译进所有相关产物，执行程序不依赖运行目录中的图标文件。模块共享的原生 HICON 不重复销毁。未修改相邻 Yue 库，界面主题色仍为 `#1f75db`，其他为 neutral。

`python scripts/sync_brand_assets.py` 原样同步 24 个平台目标文件，并生成 [来源与 SHA-256 清单](../desktop/assets/brand-assets.json)。原来的未限定密度 adaptive launcher 和旧麦克风托盘 PNG 已移除，防止旧资源被意外使用。源资源包中的商店、MSIX 资产继续保留；当前没有新增应用商店发布或 MSIX 构建流程。

## 验证与交付

检查包括完整工作区回归、Clippy `-D warnings`、工作区构建、包限定格式、双端 i18n/依赖检查、Android Lint/单元测试/Debug 构建。既有原生主窗口回归增加小/大 WM_GETICON 断言，托盘回归覆盖主题刷新消息和 Explorer 重建后的操作。

实际核对 Windows PE 产品名称/文件描述及提取图标，Android APK 应用 label、自适应图标资源与包名；24 个平台文件的 SHA-256 与 B-field 原始文件一致。手机通过保留数据的 `pm install -r` 更新，启动 MainActivity 后检查实际画面；没有卸载、清库或改系统权限。

- [手机实际画面](research/21-android-sayin.png)
- [Windows exe 提取图标](research/21-exe-icon.png)
- 桌面生产 Shell 在独立临时数据上渲染，双语/明暗/两种窗口尺寸画面位于 `research/21-native-render`，使用假历史和离线接收；它不访问生产账本，也不执行输入。
- 最终检查和部署状态：[验收记录](research/21-sayin-brand-verification-2026-11-13.json)。手机整套前台 UI 仪器化测试本轮未重复运行；前次 MIUI 限制及边界仍见 19/20，不能将构建、截图或桌面回归描述为该套测试全部通过。

启动命令仍为 `scripts/desktop.ps1`（默认 Cargo run 运行唯一的 SayIn bin），也可直接运行 `desktop/target/debug/sayin.exe`。旧版运行时须先从托盘退出以释放独占账本；重命名不会自动结束旧进程或清除显式暂停。新 exe 路径若触发 Windows 防火墙询问，应按原网络使用方式授权，程序不自动改防火墙规则。

用户确认旧版已从托盘退出后，已用普通可见模式启动新版，实际主窗口标题为 SayIn、窗口句柄非零，启动错误日志为空。手机最终安装包与本地最新 Debug APK 的 SHA-256 一致，应用 label 为 SayIn、包名仍为 `seeyuer.voice.link`。本轮部署保留原有数据，没有新增发送或执行输入来验收品牌。
