# 所有分支归并到 main

2026-11-16。用户要求合并所有分支，在 main 统一提交并推送。本次包含分支历史，以及各 worktree 尚未提交内容的审查；没有删除分支、清理工作区、reset 或 stash。

## 共同基线与归并方式

核对并 fetch 后，main 与 origin/main 均为 `96becf29d34c6f41703c0c9ed35af7478f8033f5`（连接页重构）。共有 7 个本地分支、1 个远端分支，以及 8 个 worktree（其中一个 detached）。

| 分支 | 审查时提交 | 与 main 的关系 |
| --- | --- | --- |
| main | 96becf2 | 统一提交所在分支 |
| feat/connect-page | 96becf2 | 相同提交 |
| codex/image-input | f9b52be | 已是 main 祖先 |
| codex/image-input-core | 7c90899 | 已是 main 祖先 |
| codex/image-network-bench | f9b52be | 已是 main 祖先 |
| codex/local-translation | b0321dc | 已是 main 祖先 |
| codex/paired-host-discovery | aee0294 | 唯一额外提交是早期共享 WIP 快照；`git cherry main codex/paired-host-discovery` 返回 `-`，补丁等价内容已在主线 |

paired-host-discovery 的正常三方合并预览会将旧共享快照与之后的主线修改混合并产生冲突。先审查其工作区全部文件，确认功能和后续修复已在 main，再使用 `git merge --no-ff --no-commit -s ours codex/paired-host-discovery` 记录这段冗余历史的祖先关系。没有借此丢弃未整合功能。最终在同一 merge commit 中加入下列待提交功能；其余分支已可从 main 到达，不制造空合并。

## 未提交内容审查

归并前在被忽略的 `root-helper/build/branch-consolidation/` 保存工作区清单、Git 二进制补丁及原始文件副本，共 8 个 worktree、202 份文件快照。副本只保留本机，不把原始工作区备份或构建产物上传 GitHub。

非 main 工作区的 123 份变更文件逐一比较：71 份与当前 main 内容相同，28 份已在主线历史中；其余 24 份均已解释：

- 20 份 benchmark 文件仅有 BOM / 换行差异，保留 main 的规范化内容。
- 两份旧的图片设计文档仍声称“未实施”，保留主线已有的双 WSS 实施及验收状态。
- 两份旧 fixture helper 已被 `voicelink_receiver::images::payload` 和 `windows_clipboard` 替代，当前 fixture 已直接复用生产实现；不恢复孤立重复代码。

各原工作区的修改、暂存区和分支保留，避免破坏工作证据。保留旧 worktree 不代表其快照比主线新；以后继续开发应先基于新的 main 整理独立工作区。

## 本次统一提交的功能

- [54 说话页信息微调](54-android-speak-page-information-polish.md)：字数、翻译按钮状态与草稿交互，以及认证通道中的当前前台应用名称和真实图标。前台信息只用于提示，不参与目标选择、信任或输入授权；同步更新协议权威说明。
- [58 常用语排序动画](58-android-phrase-reorder-animation.md)：整项跟手、邻项让位、松手弹簧回落和持续边缘滚动；保留一次提交、取消不落盘、写入失败回滚和 revision 冲突处理。六份必要参考源码附来源摘要和 Apache-2.0 许可。
- 已提交的 [65 连接页重构](65-android-connection-page-refactor.md) 与上述修改共同回归。配对、手动路由维护、有界发现、草稿及旧请求查询语义不变。

## 验证

合并前以 96becf2 及各功能记录作为基线。本次对组合后的实际 main 工作树重新运行 Android JVM / 构建 / Lint / i18n、桌面完整测试 / Clippy / 限定包格式 / 构建 / 依赖检查，以及 K40 隔离设备套件。最终数量、命令和结果见 [验证记录](research/66-main-consolidation-verification.json)。

| 验证 | 最终结果 |
| --- | --- |
| Android JVM | app 98 + translation 1，共 99 项，零失败/错误/跳过 |
| Android 构建 / Lint / i18n | Debug、imageTest 及仪器化 APK 构建通过；Lint 零错误（109 Warning、6 Hint），国际化通过 |
| Windows | 144 项测试通过；Clippy `-D warnings`、限定包格式、完整工作区独立构建和依赖/引用源摘要检查通过 |
| Redmi K40 / API 33 | 115 项常规设备测试通过（111 文字/UI + 4 图片）；文字恰好 2 次 fake 执行、图片恰好 1 次，无重放 |
| 补验 / 文件检查 | 3 项失败用例定向修正后通过；六份参考源码摘要、Git 暂存差异检查通过；采集器恢复 running/enabled/capture_ready |

设备测试只使用 `seeyuer.sayin.imagetest`、随机端口、独立 host/device 与数据目录。文字和图片使用不同电脑身份；测试前暂时停止 root 剪贴板采集器，结束后恢复原启用状态，不导出或清除日常剪贴板记录。

首轮完整文字/UI 回归为 108/111，三项失败如实保留。整合审查定位并修正的是测试假设，没有修改产品逻辑或放宽动画指标：

- 空草稿的 BasicTextField 节点会合并占位提示；改为精确检查 `EditableText` 为空，保留两次撤销后真实会话分别为 X、空字符串的断言。
- 普通总字数已在语音栏，顶部仅补充选区和接近上限的信息。测试验证普通顶部计数不存在、语音栏为 3 个码点，并在足够宽度验证选中 emoji 为 1 个码点；窄屏及大字布局继续独立覆盖。
- 排序保存提示将列表根坐标从 1009px 移到 1078px。原断言将这 69px 位移误算成松手动画；改用列表内坐标，仍断言跟手跨槽位无跳变、邻项及落位经过中间位置、朝新槽位靠近、一次落盘、无草稿修改或发送。定向三项复测通过后，重新运行完整设备套件。

桌面原默认输出构建被运行中的日常 `sayin.exe` 占用；完整工作区改用 `cargo build --workspace --locked --target-dir target/main-consolidation-build` 成功。没有终止或替换日常进程。

本轮常规设备通信通过 USB adb reverse 与 fake 输入验证，不能据此宣称真实 Wi-Fi 换 IPv4、多网卡、目标软件输入、实际录音或长期功耗完成新一轮验收。既有真机/LAN 验收证据及未覆盖边界仍以各功能实施记录为准。
