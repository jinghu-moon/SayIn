# 配对二维码与手机扫码方向修复记录

日期：2026-11-12。

## 问题与根因

用户反馈电脑配对面板没有二维码，手机进入扫码页后强制横屏。本次修复这两处问题，继续使用既有二维码/8 位短码二选一、电脑本地批准、TLS 主机固定及公用网络会话授权流程。

### 电脑二维码

原实现通过 GPUI 的 `svg()` 图标管线绘制完整二维码。该管线是 alpha mask：丢弃 SVG 的前景/背景 RGB，再使用图标颜色着色。二维码的深色方格和不透明浅色底因而不能保留，属于渲染管线选择错误。

排查中改用完整颜色图像后，又通过原生画面回读发现第二个问题：高密度邀请在非整数模块宽度下，ZXing 会出现 `FormatException`。从原始 SVG 重建的整数像素图像可解码，缩放至非整数模块宽度的图像失败；逐模块中心对比未发现内容缺失。单纯提高 SVG 分辨率或打开 `TRY_HARDER` 不能解决。

最终设计：

- 后端继续生成纠错等级 M 的 QR，SVG 使用每模块一个坐标单位，并保留四模块静区。`PairingWindow::qr_modules()` 明确传递含静区的边长，UI 不从 GPUI 内部超采样比例猜测模块数。
- `ReceptionState` 按配对窗口 ID 缓存 `Arc<Image>`；同一窗口内切换主题/语言不重复创建。关闭配对、服务停止或退出时清除引用，重新配对使用新邀请和新图像。
- 生产界面的 `shell::pairing_qr` 使用完整颜色图像管线，按 `floor(可用物理像素 / 模块数)` 计算方格宽度，居中并对齐物理像素原点，使用维护版 Windows 后端已有的 `paint_image_nearest`。不经过图标 mask 或线性插值。
- 外层仍为既有 20 rem 方形区域；二维码内部可略小以容纳整数方格。无障碍角色为 Image，中英名称分别为「配对二维码」/「Pairing QR code」。
- 深色/浅色主题均保留 neutral `#0a0a0a` 方格与不透明 `#fafafa` 底及静区。二维码不反色、不用品牌色着色；其余既有品牌 `#1f75db` 与 neutral 规则不变。

### 手机扫码方向

JourneyApps 4.3.0 的 `CaptureActivity` 在依赖 AAR manifest 中默认声明 `sensorLandscape`。仅调用 `setOrientationLocked(false)` 不会覆盖该 Activity 的方向声明。

应用 manifest 显式覆盖为 `fullUser`，并通过 `tools:replace="android:screenOrientation"` 处理合并；生产 `pairingScanOptions()` 保持 `setOrientationLocked(false)`。扫码仍仅识别 QR、禁用提示音，提示文字继续来自中英资源。页面遵循系统旋转偏好，可以竖屏使用；没有改为另一种强制方向，也没有改动手机系统旋转设置。

## 关键代码

| 路径 | 改动 |
| --- | --- |
| `desktop/crates/transport/src/server.rs` | QR 模块边长、单模块 SVG 坐标与静区 |
| `desktop/src/reception.rs` | 按配对窗口缓存/清除完整颜色图像 |
| `desktop/src/shell.rs` | 生产二维码组件、物理像素对齐与 nearest 采样 |
| `desktop/src/reception/tests.rs` | 生产 Shell 的图像/采样/几何、双主题双语言、缓存与关闭/重开回归 |
| `desktop/locales/{en,zh-Hans}.ftl` | 二维码无障碍名称 |
| `android/app/src/main/AndroidManifest.xml` | 覆盖扫码 Activity 的 `fullUser` 方向策略 |
| `android/app/src/main/java/seeyuer/voice/link/ui/PairingScanner.kt` | 生产扫码选项唯一构造入口 |
| `android/app/src/androidTest/kotlin/seeyuer/voice/link/ui/ScannerInstrumentedTest.kt` | 核对实际安装 manifest 与生产扫码 Intent |
| `desktop/examples/verify_pairing_qr.rs` | Windows GPUI/D3D 原生画面回读，复用生产二维码组件 |
| `scripts/verification/VerifyPairingQr.java` | 用手机依赖的 ZXing core 3.4.1 解码并逐字核对邀请 |
| `scripts/verify_pairing_qr.ps1` | 多组独立邀请、两种主题、三种尺寸的可复现验证 |

`desktop/Cargo.toml` 只在 Windows dev-dependencies 启用维护版 `gpui-pre-windows` 的 `test-support`，用于 D3D 画面回读。未全局开启 Kit 可选 Fast backend，未升级 GPUI/Kit，也未修改相邻 Yue 仓库。

## 实际验证

| 项目 | 修改前或排查中行为 | 最终结果 |
| --- | --- | --- |
| 二维码显示 | SVG icon mask 无法保留黑白内容 | 使用完整颜色图像与 nearest 绘制，原生截图可见 |
| 密集二维码 | 普通非整数缩放出现 ZXing `FormatException` | 3 组独立邀请 × 2 主题 × 3 尺寸，共 18 张画面全部精确解码 |
| 原生渲染范围 | 无解码回归 | 实际 Windows GPUI/D3D、系统缩放 150%，每张 600×600；rem 12/16/20 改变有效显示尺寸。本次邀请含静区 133 模块，对应 2/3/4 物理像素每模块；没有改系统 DPI |
| 桌面回归 | 本轮开始前既有库测试通过 | `cargo test --workspace --all-targets --locked`：61 通过、0 失败；原有公用网络/加密通信/接收测试继续通过 |
| 代码质量 | 既有构建 | 全 targets Clippy `-D warnings`、包限定 fmt、workspace build、双端 i18n 与 Yue 依赖/引用源检查通过 |
| 手机扫码页 | 依赖 manifest 强制横屏 | 已覆盖安装新版，从生产首页进入扫码；截图为竖屏，系统报告 `ROTATION_0`、1080×2400、实际前台为 CaptureActivity |
| Android 单元测试/构建 | 本轮修改前 JVM 与 Debug 构建通过 | JVM 5 通过；Debug APK、测试 APK、lint 构建通过 |
| Android lint | 既有 16 条警告 | 0 错误、17 警告；新增一条方向声明的通用 `DiscouragedApi` 警告，未抑制 |
| 真机回归 | 原有 6 项通信检查 | 7 项仪器化测试全部通过，新增安装后方向/Intent 检查；重连与仓库重建后仍恰好 1 次 fake input |
| 系统旋转设置 | 自动旋转开启、user rotation 0 | 核查前后仍为相同值；验证后退出扫码页停止相机 |

截图与日志为本地构建产物，路径、SHA-256 和证据边界见 [verification JSON](research/12-pairing-qr-verification-2026-11-12.json)。原生二维码截图来自**生产二维码组件的独立窗口**，不是完整产品 Shell 的截图；完整 Shell 由 UI integration testing 覆盖。

### 复现命令

```powershell
# 项目根目录；原生二维码回读需 Windows 图形会话，依赖已构建 Android 的 ZXing 缓存
.\scripts\verify_pairing_qr.ps1

# Android 构建和静态检查
.\scripts\gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug

# 当前已授权 Root 安装设备；该脚本仅使用 adb reverse 与 loopback fake input
.\scripts\verify_android_transport.ps1 -DeviceSerial dc39c31d -UseExistingRootInstall -SkipBuild

# desktop/ 下
cargo test --workspace --all-targets --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo fmt -p voicelink-desktop -p voicelink-receiver -p voicelink-pairing -p voicelink-transport -- --check
cargo build --workspace --locked

# 项目根目录
python scripts/check_i18n.py
python scripts/check_desktop_dependencies.py
```

二维码诊断使用一次性身份、拒绝输入的适配器及 loopback 服务；不会批准手机或向目标窗口输入。诊断后关闭服务并删除临时接收目录，脚本在成功或解码失败时都删除邀请原文；截图可能包含已关闭的测试邀请，保留在忽略的 `target/` 中。不要将此自动化入口用于产品监听。

## 用户验收步骤

1. 手机新版已安装。项目根目录执行 `.\scripts\desktop.ps1` 打开新电脑端。
2. 点击「配对手机…」，选择与手机互通的实际网卡。Windows 标为公用网络时，确认可信后勾选「本次会话允许所选公用网络」，再点击「开始配对」。保持现有网络分类；防火墙仍需独立允许当前应用入站。
3. 确认二维码清晰出现，并使用**本次新生成**的二维码。旧配对窗口关闭后，其二维码/短码已经失效。
4. 手机竖持，进入「扫描二维码」，页面应保持竖屏。对准电脑新二维码，确认回到配对流程；系统自动旋转开启时，可顺便检查横持/竖持跟随正常。
5. 在电脑本地批准手机，确认双方连接状态。二维码与手工输入地址/短码是二选一，不需要两种方式都做。
6. 若继续验收输入，在普通权限文本编辑器准备空白文档，电脑「锁定窗口…」后切换至编辑器，再回声达「恢复输入」。手机发送唯一短句，检查只出现一次、没有自动 Enter，并核对手机回执。当前语音识别仍为后续功能。

本轮尚未执行手机摄像头对实体显示器的光学扫码、真实局域网入站配对或普通目标应用落字；18 张画面解码和 USB loopback 回归不替代这些验收，也不代表输入时延目标已实测达成。
