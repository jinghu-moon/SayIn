# SayIn

在手机上说话或输入，文字通过局域网直接进入电脑当前光标所在的输入区域。电脑端常驻托盘，记录每条原文、来源手机、执行时目标程序和结果，无需逐条桌面确认。

- Android：Kotlin / Jetpack Compose / Miuix，包名 `seeyuer.sayin`。
- Windows：Rust / GPUI / Yue-GPUI-Component，桌面程序 `sayin.exe`。
- 主题：`#1f75db`，其他界面颜色使用 Yue neutral；简体中文、English 和跟随系统。
- 项目图标：用户选定的 [SayIn B-field](assets/sayin-icons/README.md)，F-yan 保留为源方案。

构建与启动说明：[Android](android/README.md)、[Windows](desktop/README.md)。工程决策和验收记录在 [docs](docs/)。

更新资源：`python scripts/sync_brand_assets.py`。原始图标位于 `assets/sayin-icons/B-field`，脚本原样同步平台资源，Windows 任务栏明暗主题使用对应托盘图标。

Windows 保留既有 `%APPDATA%\seeyuer\VoiceLink\config` 数据目录、配对和历史。Android 最终包名为 `seeyuer.sayin`，与旧开发包 `seeyuer.voice.link` 独立安装，新包需要重新配对，旧包数据保留。

源码采用 [MIT 许可证](LICENSE)。第三方资源保留各自许可证，见 [第三方说明](THIRD_PARTY_NOTICES.md)。本地参考工程、构建产物、设备日志和验收截图不纳入源码仓库。
