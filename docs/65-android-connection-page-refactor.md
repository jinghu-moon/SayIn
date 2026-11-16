# Android 连接页重构

日期：2026-11-16。设计依据为用户更新后的 `refer/sayin-connect-page-demo.html`；该目录被 Git 忽略，本机参考文件 SHA-256 为 `6e76f7b03817bf9c0d8066edc031ee794afb0cd0768e27117b88757d86524ddc`。

## 基线与问题

共同基线为 `91742e6`。在 `../.zcf/VoiceInput/connect-page` 的 `feat/connect-page` worktree 实施，保留主工作区其他任务的未提交修改，不 reset、不 stash、不批量暂存。修改前执行 `scripts/gradle.ps1 :app:testDebugUnitTest` 成功，基线为 87 项 JVM 测试。

旧连接页的页头连接按钮会再次打开同一连接面板；页内还有重复的大标题。首次连接缺少电脑批准这一步的清楚引导；掉线主要展示大重连按钮，容易让用户把自动恢复理解为需要手工操作。相机／局域网权限拒绝后只有文案，没有恢复入口；尚未配对时授予局域网权限后，Repository 还可能保留旧错误。

## 实际实现

| 项目 | 修改前 | 修改后 |
| --- | --- | --- |
| 页头 | 可点击连接入口和重复动态标题 | 非交互状态徽标、单一页面标题；说话页仍有连接入口 |
| 首次连接 | 扫码与配对码入口 | 电脑打开接收页 → 手机扫码 → 电脑核对手机名称并批准；扫码为主，配对码为文字次入口 |
| 配对码 | 独立地址与密钥输入逻辑 | 继续使用单一 IPv4:端口输入、8 位数字短码和明确提交，短码不持久化 |
| 等待批准 | 普通连接提示 | 独立进度与取消；批准不会直接显示可以输入 |
| 已配对状态 | 状态说明和常显维护按钮 | 一张电脑状态卡；更多菜单收纳详情、地址维护和忘记；忘记仍需明确确认 |
| 掉线 | 大重连按钮 | 保存端点连接、实际发现、退避分别使用真实状态；只在退避期显示轻量重试 |
| 权限 | 被拒提示 | 可再次请求时提供授权；不可再次请求时进入应用设置；返回设置刷新实际状态 |
| 权限恢复 | 未配对时可能保留旧错误 | 前台重新检查授权后清除局域网旧错误，不发起配对、不删除身份 |
| 说话页连接面板 | 与连接页重复维护展示 | 使用同一 ConnectionDetails／ConnectionActions、相同状态优先级 |

页面保留 Miuix 原生组件、Tabler 图标、Yue 功能色、48dp 操作目标，使用可滚动内容及最大 600dp 宽度。菜单采用原生 WindowIconDropdownMenu；详情及表单在卡内展开。首次连接步骤直接位于页面，已配对信息使用卡片。进度仅绑定真实 busy／discovering／recovering，不增加模拟延迟。

## 与电脑端及协议的关系

本轮没有新增协议消息、监听、发现循环或电脑端恢复按钮。复核电脑 `approve_pairing`、`maybe_enable` 和已有 `welcome/state`：配对批准建立信任；能否接收仍由活动设备、目标、暂停原因、存储和收据恢复共同决定。主动暂停和 `outcome_review_required` 必须在电脑处理；手机不远程恢复、不自动释放 held 或重发旧请求，不修改草稿、历史、Outbox 和固定证书。

局域网拒绝优先于掉线展示，不出现无效的重试按钮。连接恢复仍由原 Repository 的保存端点优先、有界 NSD、固定证书及原身份认证、旧请求查询和退避驱动。更多菜单打开、页面切换和布局重组不会自动调用配对或发送。相机拒绝不阻碍配对码路径；代码配对开始后不继续显示无关相机权限操作。

广播名称、host ID 或地址不用于展示“已验证电脑名称”，也不能据此判定证书错误。当前基线没有电脑显示名称和当前应用展示字段，因此本轮使用通用电脑标识和真实接收状态，不填 demo 的示例电脑／应用名。其他任务正在主工作区开发实时应用信息，不能将其未提交协议改动顺带纳入本次提交。身份／存储异常不提供隐式重新配对或清除资料。

## 回归与验收

新增 2 项 JVM 状态测试，覆盖权限／存储优先级及发现、重试、接收闸门；新增 9 项无截图设备行为测试，挂载产品组件和隔离 Repository，覆盖：

- 首次三步引导，填完代码不自动提交；等待批准有取消，不承诺已经可以输入。
- 暂停、待核对、恢复、非活动设备、无目标、存储阻断都不能显示可以输入或出现手机恢复动作。
- 自动连接及发现期间不提供并行重试，退避可明确重试。
- 地址编辑不提前更新；忘记有取消和确认。
- 局域网授权／设置恢复入口；未配对授权恢复后返回原连接流程。
- 页头徽标无点击动作；连接页和面板状态一致，往返保留草稿。
- 320dp、English、150% 字体时，电脑信息、状态及操作位于卡片范围内。

实际结果：

| 验证 | 命令／结果 |
| --- | --- |
| Android JVM／Debug／Lint | `scripts/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`；89 项 JVM 全过，构建和 Lint 成功 |
| 隔离设备构建 | `scripts/gradle.ps1 -Psayin.instrumentationBuildType=imageTest :app:assembleImageTest :app:assembleImageTestAndroidTest` 成功 |
| 设备完整通信／界面 | `scripts/verify_android_transport.ps1 -DeviceSerial dc39c31d -UseExistingRootInstall -SkipBuild -FullSuite`；102 项文字／界面 + 独立电脑身份的 4 项图片全部通过；分别恰好 2／1 次 fake 输入，没有重放 |
| 设备新连接用例 | 初轮 8 项独立执行通过；加入未配对权限恢复用例后，全部 9 项纳入上述完整套件通过 |
| 桌面接收／传输／配对 | `cargo test -p voicelink-transport -p voicelink-receiver -p voicelink-pairing --locked` 通过，包含集成测试；没有修改桌面可执行代码，未重跑 GPUI 完整工作区／Clippy |
| 依赖与国际化 | `python scripts/check_desktop_dependencies.py`、`python scripts/check_i18n.py` 成功；没有新增依赖 |
| 安装 | 已验证 Debug 包通过 `pm install -r` 更新 K40 日常 `seeyuer.sayin`，未卸载或清数据 |

完整设备套件首轮有一项旧断言按文字定位同时命中顶栏和卡片；修为精确状态卡标记后完整重跑通过，未减少测试或弱化闸门断言。桌面核心测试初次因并行运行的隔离 fixture 占用 exe 导致 LNK1104；待该验证正常结束后原命令重新执行通过。

设备证据目录为 `desktop/target/image-android-fixture-019122fd-d82c-4ea1-b91a-01da55584009`（文字）和 `desktop/target/image-android-fixture-0d37bafc-46f7-4731-963a-0b3997d92f28`（图片）；这些本地临时结果不包含在发布包中。概要见 [验证 JSON](ui-verification/65-connection/summary.json)。

真机为 Redmi K40 / Android 13 / API 33；使用独立 `seeyuer.sayin.imagetest`、隔离偏好／数据库、独立电脑身份、随机端口和 fake 输入，日常配对不变。会写剪贴板的原有套件期间暂停模块采集并保留启用偏好，结束后恢复。

用户负责真实界面观感验收；新增测试以语义、行为和坐标检查为主。API 37 的真实系统授权界面、相机硬件扫码、本轮真实 Wi-Fi 发现／目标软件落字均不能由 API 33 组件测试或回环 fixture 证明，本轮未重新验收。

安装包 SHA-256：`df8a856638708cde4542ab34ebcc314c97096d0794d38a57661c9647553d1f8f`。模块采集在套件结束后已恢复 `running/enabled/capture_ready=true`；未读取、导出或清空日常模块历史。
