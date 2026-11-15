# Android 三区语音栏重构

## 目标与基线

2026-11-15，以 main `b0321dc` 为共同基线，工作区起始无改动。参考用户指定的 `D:/000_Inbox/020_Browser/021_Edge/SayIn 底部控制区对比.html` 中「语音栏 · 三区」，只移植这一布局，不移植比较页开关和模拟网络逻辑。用户在真机评审外观，本轮不通过截图评审 UI。

现有说话页把输入来源、提示、自动发送开关、翻译行和三个圆形操作分开排列，挤占草稿且状态反馈位置分散。目标为 neutral 页面、白色/深色草稿卡、卡内工具栏、独立键盘圆钮与固定 56dp 蓝色语音栏。关键操作最小 48dp；窄屏、横屏 IME 和大字时收起次级信息，不能压没编辑行。

本轮采用整页参考，而非只替换底部控件。参考文件 SHA-256：`77ff2f846d46117edfecea49a76ea573237c516b25e8a3f767e9d12f366ff7bc`。

| 参考区域 | 原生实现 |
| --- | --- |
| 顶部品牌、更多与连接胶囊 | 已确认 B-field 品牌、Miuix 菜单、电脑图标与真实连接状态；连接中显示进度，已连接收起冗余文字 |
| 草稿主体 | neutral 卡片、28dp 圆角、22sp 正文、空稿波形；完整页与快捷卡共享唯一草稿 |
| 草稿顶部 | 撤销/重做图标，自动模式仅显示闪电；真实模型加载、预热和短暂就绪提示 |
| 草稿底部 | 字数在左，原文/译文切换、翻译、复制、清空在右；翻译过程可取消 |
| 语音栏 | 独立键盘/取消钮，固定蓝色三区胶囊，真实音量、累计时长、字数及发送状态 |
| 底部导航与设置 | 图标加短标签、细分隔线；识别来源改为底部离线/云端两行选项 |

参考页的其余三个 tab 只有占位图标；产品保留实际历史、连接、设置功能。系统状态栏、安全区、输入法、48dp 无障碍触控区及快捷卡窗口由原生环境适配，不能以删除实际功能来复制 demo 的空状态。

## 设计与边界

- 草稿卡圆角 28dp，正文 22sp/35sp；顶部撤销/重做，底部字数、原文/译文切换及翻译/复制/清空图标。顶部更多收纳语音引擎、自动发送和图片入口。
- 语音栏固定 56dp，内部 48dp / 弹性信息 / 48dp。空稿时麦克风与「说话」居中；准备时真实进度；录音时停止、PCM 音量波形及计时；结束后保留时长/字数，右侧发送；不人为拖延 final。
- 左侧独立键盘钮在录音准备/录音/尾包时变取消；按住麦克风可上滑取消，明确按钮也可取消，不能把手势作为唯一路径。取消恢复录音前文本、选区及已完成译文，旧回调不得写入或发送。
- 撤销/重做属于 Application 唯一 SpeakSession，完整页与快捷卡共用。最多 60 步；口述 partial 不入栈，整段完成记一步；连续编辑 900ms 合并，IME composition 一个事务；翻译完成/清空各一步。提交中、待核对、识别或翻译进行中不能撤销。已提交输入后清历史，不把撤销解释成撤回电脑输入。
- 录音中右箭头是一次性的明确「结束并发送」，绑定本次录音和原 ReceptionScope；final 到达前不发送。切网、连接面板、离页、取消撤销该意图。自动偏好仍默认关闭，启用后仍为三秒可取消倒计时。固定证书、Outbox、旧请求查询、uncertain/held/暂停语义不变。
- 两端协议无需改变；电脑仍按当前焦点输入并返回真实收据。「已提交输入」不等于目标软件已显示。不引入新目标选择、自动重试或后台发送。

## 验证计划

修改前 Android JVM 与 i18n 通过。修改后运行应用/翻译 JVM、Debug 构建、Lint、i18n、图标来源与原生打包检查；独立 imageTest 包运行相关会话/翻译/UI 和通信回归，保留原有配对、手动发送、图片与旧请求不重放断言。新增撤销事务、录音取消/迟到 final、明确结束发送及范围撤销、固定语音栏边界检查。只使用隔离身份/数据/端口，不触动日常配对；不把 fake ASR、模拟回执或回环传输视为真实麦克风/云准确率/局域网落字验收。

## 实施与结果

已完成原生代码替换与回归扩充，最终验证记录见下文。正常高度按参考 56dp 语音栏、14dp 外边距和 28dp 卡片圆角；系统大键盘横屏只剩约 90dp 高度时，次级工具收起，控制区缩为最小 48dp，从而保留至少一整行 34sp 编辑区。这是必要的系统窗口适配，不对常规按钮做大小形变。品牌使用已确认 B-field，不复制 demo 中的占位麦克风 logo。

### 本轮修正发现

1. 首轮设备套件 52 项中 5 项失败。其中 3 项揭示 Miuix 禁用 IconButton 不带 disabled 语义，已在发送及草稿工具补齐真实禁用的无障碍状态；原点击限制保留，未放宽断言。
2. 原横屏高 IME 仅余约 90dp，48dp 工具行与 74dp 控制区让编辑区变 0。已按实际高度收起工具并减掉控制区留白，极端情况下使用 48dp 控制区，重跑原完整编辑行断言。
3. 双语大字测试打开识别设置时使用宿主中文资源查找英文界面，改为匹配当前两种实际界面标签；仍检查每个引擎标题不截断。
4. 构建过程中修正了资源读取编码、Miuix 进度颜色参数、嵌套布局 receiver、配置感知资源和 PowerShell 属性参数引号；这些失败均未当作通过。
5. 新增回归验证整段 partial 合并撤销、取消后迟到 final、译文/选区还原、明确结束发送只消费一次、范围/面板变化撤销、旧稿不能授权本段空录音发送及固定栏边界。实际 PCM 音量不上传额外数据，不播放伪造的音频反馈。
6. 实际触摸测试发现局部函数引用捕获了首次组合的 ASR 状态，第二次物理点击不能停止录音。改为在事件发生时读取共享 `session.asr`、当前语音引擎与最新云配置；真实点击、长按、上滑取消均保留操作级回归。
7. 英文大字号标签的 Compose paragraph 约束宽度大于最终字形框，`hasVisualOverflow` 因此误报。测得实际行右边界 571.1094px，小于渲染宽度 572px。断言改为逐行核对左右边界、底部边界、无省略号、无竖向溢出及仅词间换行；没有取消完整文本可见性要求。
8. 清空、原文成功提交以及明确开始新草稿后计时归零；撤销清空恢复本段时长。该行为与正文、译文和选区使用同一个历史快照，避免新一段继承上一条已提交内容的计时。

### 最终执行记录

2026-11-15，最终源码验证通过。摘要与原始日志摘要值见 [51 验证 JSON](ui-verification/51-three-zone.json)。构建和设备测试按不同 variant 分开执行，等待 APK 构建完成后再安装，不复用构建中的旧制品。

| 检查 | 实际结果 |
| --- | --- |
| 修改前基线 | 应用 JVM 与 i18n 通过，起始工作区干净 |
| Android JVM | 应用 58 项、translation 1 项，合计 59 项，失败/错误/跳过均为 0 |
| Android 制品 | Debug、imageTest、imageTestAndroidTest 构建通过，Lint 通过；现有弃用提示不当作失败或隐藏 |
| Android 真机仪器化 | Redmi K40 / API 33：53 项常规 + 独立身份 4 项图片 = 57 项，通过 |
| 不重放约束 | 文字 fixture 恰好 2 次 fake 输入，图片 fixture 恰好 1 次；独立身份、目录和随机端口，不改日常配对 |
| 桌面回归 | 138 项工作区测试通过；Clippy `-D warnings`、四产品包格式检查及工作区构建通过 |
| 静态检查 | 双语键/占位符、Yue 实际依赖图/源摘要、26 个 Tabler SVG 与许可证摘要、diff 检查通过 |
| 翻译原生打包 | JNI 导出、arm64 依赖闭包、ELF/ZIP 16KB 对齐检查通过；APK 无 GGUF |

执行命令（仓库根目录）：

```powershell
./scripts/gradle.ps1 :app:testDebugUnitTest :translation:testDebugUnitTest :app:assembleDebug :app:lintDebug
./scripts/gradle.ps1 '-Psayin.instrumentationBuildType=imageTest' :app:assembleImageTest :app:assembleImageTestAndroidTest
./scripts/verify_android_transport.ps1 -DeviceSerial dc39c31d -FullSuite -SkipBuild -UseExistingRootInstall
python scripts/check_i18n.py
python scripts/check_translation_package.py android/app/build/outputs/apk/debug/app-debug.apk
python scripts/check_desktop_dependencies.py
```

桌面目录执行：

```powershell
cargo test --workspace --all-targets --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo fmt -p voicelink-desktop -p voicelink-receiver -p voicelink-pairing -p voicelink-transport -- --check
cargo build --workspace --all-targets --locked
```

本轮未重新验证真实麦克风→本地/付费云识别→真实 Wi-Fi→目标光标输入整链路，也未重跑真实翻译 JNI、API 29/37 系统、局域网发现、多网卡和长期性能。仪器化中的 ASR/翻译为受控 fake，双端 fixture 通过 ADB reverse 使用隔离假输入；不能据此宣称真实落字或延迟目标已通过。原生 PCM 波形已接线，但设备声学表现由用户实际说话验收。已有自动测试会生成其常规截图制品，本轮未查看截图；真机视觉效果由用户验收。

最终 Debug 制品 `android/app/build/outputs/apk/debug/app-debug.apk` 已用 `pm install -r -t` 覆盖安装到连接的日常 `seeyuer.sayin`，返回 `Success`；未清数据、未更改配对或后台权限。启动 `.MainActivity` 返回 `Status: ok`，系统 `topResumedActivity` 确认为 SayIn 主界面。安装前确认日常应用无活动麦克风服务。隔离测试仍使用独立包，与此次日常包更新分开。

### 真机查看重点

打开「说话」页，检查顶部连接胶囊、草稿上下工具栏与底部导航；有草稿时检查字数/译文切换/翻译三个图标的排列。点麦克风开始，再点停止应保留草稿；录音期间右箭头结束并发送，左侧 X 或按住上滑取消应恢复原稿。撤销/重做按完整口述操作回退；清空可撤销，已提交输入后不能用撤销恢复旧请求。键盘弹出和横屏时仍需保留可编辑完整行及发送入口。
