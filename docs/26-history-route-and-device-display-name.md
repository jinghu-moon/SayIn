# 26 · 历史去向布局与 Android 设备商品名称

日期：2026-11-13。

## 问题与修改

历史卡片底部与 [demo](demos/sayin-history-card.html) 不一致：来源与目标被固定排成两行，目标直接显示 `WindowsTerminal.exe`。手机端直接将 `Build.MODEL` 用作名称，当前 Redmi K40 因而显示型号编号 `M2012K11AC`；普通认证重连也不刷新设备名称。

本次修正历史卡片去向展示，并实施 [24 设备名称方案](24-android-device-display-name-plan.md)。没有实施另一轮手机页面重设计，也没有修改用户正在调整的接收页 demo。

| 行为 | 修改前 | 修改后 |
| --- | --- | --- |
| 卡片去向 | 手机与软件固定两行 | 图标 + 手机名称 → 图标 + 软件名称，空间不足时折为两行 |
| 目标名称 | exe 文件名 | Windows 版本资源中的可读名称，完整 exe 路径仍在详情 |
| 手机首次配对 | `Build.MODEL` | 商品名优先，当前手机实际解析为 `Redmi K40` |
| 已配对手机重连 | 沿用首次配对名称 | 令牌认证成功后刷新同一个设备的名称，无需重配对 |
| 旧历史的来源名称 | 查询时关联当前设备名称 | 接收时保存独立快照，后续改名不回写旧记录 |

## 卡片去向与软件名称

`HistoryView` 沿用一至三列布局；按实际卡片可用宽度决定去向排列。卡片宽度至少 `26rem` 时，来源、ChevronRight 箭头及目标共用一行；小于此值时隐藏箭头并折为两行，为复制操作留下空间。该判断包含侧边栏、页面内边距、列数和列间距，并随当前 `rem_size` 缩放，不把整个窗口宽度误认为单张卡片宽度。

来源名称保持次级色，目标名称使用正文色；原生应用图标与手机/箭头图标采用接近 demo 的紧凑尺寸并居中对齐。长名称允许截断，详情仍提供完整来源与目标信息。

应用名称与图标复用同一条后台路径缓存：

1. 继续用 `win-icon-extractor =0.3.0` 提取真实图标。
2. 用 Windows `GetFileVersionInfoW`、`VerQueryValueW` 读取文件资源中的语言表、`FileDescription` 与 `ProductName`。
3. 默认使用具体文件描述；描述以产品名开头时使用较短的产品名。例如本机 Terminal 的描述为 `Windows Terminal Host`、产品名为 `Windows Terminal`，卡片显示后者。通用操作系统产品名不会取代资源管理器等具体文件描述。
4. 没有可读资源时回退到去掉 `.exe` 的文件名；不维护硬编码的软件名称映射。文件缺失/读取失败保留现有通用图标回退。

资源读取与图标提取均在后台执行，按路径和文件修改时间复用结果，不在 render 中访问文件。版本资源大小、返回指针、长度和字符串读取范围均有界；不执行历史 exe。Windows 本地化资源反映系统/文件携带的名称，不随 SayIn 界面语言翻译第三方品牌。

## 手机商品名解析

新增集中式 `DeviceDisplayName`，配对与普通 `hello` 连接共用。顺序为：

```text
ro.product.marketname
→ ro.product.vendor.marketname
→ ro.product.odm.marketname
→ Build.MODEL
→ Android
```

每个候选先去除 ISO 控制字符与未配对 UTF-16 代理项、清理首尾空白，再保留最多 64 个完整 Unicode 码点。空值或读取失败继续下一个候选；不改变大小写、不追加品牌、不使用 `Build.DEVICE` 或机型对照表。

通过已有 Rust JNI 库调用公开 bionic `__system_property_find` 和 API 26 起提供的 `__system_property_read_callback`。项目 minSdk 为 26，NDK 为 `29.0.14206865`。原生入口仅允许上述三个属性键；其他键返回空字符串。没有 shell 子进程、ADB 依赖、root 请求或隐藏 Java API 反射；调用位置是接收仓库原有 IO dispatcher。缺少属性、权限限制或 native 入口不可用时仅影响名称回退，不阻止连接和发送。

API 依据为已安装 NDK 的 `sys/system_properties.h` 和 [AOSP bionic 官方头文件](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/include/sys/system_properties.h)。使用回调接口而非头文件标为 deprecated 的固定缓冲区 getter。

## 双端认证与数据

协议权威文档 [01 v2 通信方案](01-voicelink-protocol-v2.md) 已将 `hello.name` 定义为必需字段；双端一起更新，不引入旧握手兼容分支。

桌面 `authenticate_named` 在协调线程内校验名称、认证原有 UUID/令牌并持久化显示名称，再返回 welcome。无效名称、错误令牌或已撤销设备不得更新名称。名称不是认证因子，不参与 HMAC、身份、Outbox 范围或请求去重；改名不改变暂停、接收范围、控制修订号或待执行队列，不触发发送、补发或恢复接收。既有会话仍按普通重连的会话 fencing 规则处理。

账本 schema 升为 **5**，`message_history.source_name` 在接收新请求的同一事务中取设备当前名称保存。首次打开现有账本时，将已有记录当时可取得的旧设备名称固化为快照；不能恢复更早未保存的真实改名历史，也不会在这次手机名称刷新时把旧记录改成 `Redmi K40`。保存开关、保留策略、删除/撤销、收据与去重身份继续保留。

## 验证与本机产物

验证证据位于本机忽略目录 `docs/research/27-*`。

- 修改前：Rust 工作区 102 项测试通过；Android 原有单元测试及 Debug 构建通过。
- 修改后：Rust 工作区 104 项测试通过，含真实文件图标/名称提取、改名的认证/暂停/去重/历史快照断言及原生 UI 去向几何验证。
- Android 单元测试 8 项通过，其中新增 3 项名称解析回归；Debug、AndroidTest、Release 构建及 lintDebug 通过。
- Clippy `-D warnings`、包限定格式检查、工作区构建、双端 i18n、桌面依赖图及 `git diff --check` 通过；外部 Yue 工作区未修改。
- Redmi K40 上运行新增 instrumentation 用例，普通应用 UID 的原生读取实际返回 `Redmi K40`；这不是用 ADB `getprop` 的结果冒充应用实现。
- 同设备的配对、持久 Outbox、断线重连及仓库重建用例已通过，使用独立命名空间和 loopback 假输入服务，不读取或覆盖用户配对/Outbox，不向真实目标输入。
- 真机单独重跑名称/Outbox/协议/扫码配置共 7 项通过，其中显式断言 `expectedDeviceDisplayName="Redmi K40"`；transport-only 1 项通过，完整过程仅产生 1 次假输入。
- 真实原生 D3D 历史页截图覆盖双语、明暗主题、720/1000/1200/1600 四种尺寸。`27-terminal-final` 额外使用本机 Windows Terminal 的真实 exe 资源，已检查一列及三列的箭头、图标和 `Windows Terminal` 名称；截图中的手机名和发送结果为隔离测试数据。

整套 Android instrumentation 已实际尝试，但未全部完成：前 7 项通过后，`MobileShellInstrumentedTest` 在启动 `androidx.activity.ComponentActivity` 时长时间停住；前置启动测试 Activity 后单独重跑，第一项通过，第二项再次停在 Activity 启动，随后结束测试进程。未修改或跳过用例来制造全套通过，也未改变手机后台启动/安全策略。原日志为 `27-android-suite-stalled.log`、`27-android-ui-retry.log`；**本轮不能声称 12 项 instrumentation 全部通过**。此次没有修改手机页面 UI，名称/协议的上述 8 项真机检查是独立完成的验证。

手机 Debug 新包已覆盖安装，保留用户数据；安装沿用已授权 root 方法，应用本身仍以普通 UID 运行。桌面产物为 `desktop/target/debug/sayin.exe`。需同时使用新版桌面与手机，让已有配对完成一次普通重连后，设备列表、批准信息及新记录来源才会使用新解析的名称；旧历史保留原快照。
