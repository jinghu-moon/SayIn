# 49 · Android 平台范围与局域网权限验收

日期：2026-11-15。本记录属于图片阶段 6 的平台补验；不改日常手机包、配对、草稿或 Outbox。图片生产性能见 [46](46-image-input-production-validation.md)。

## 1. 最低系统不能与 TLS 前提矛盾

修改前 manifest 声明 minSdk 26，但 `HostClient` 使用系统 `SSLContext`，要求 TLS 1.3 且拒绝降级。两条 WSS 共用此客户端。可安装不等于能连接，图片回退解码器正常也不证明整个应用受支持。

在独立 AOSP API 26/x86_64 模拟器运行修改前图片代码：

- EXIF 八方向逐角像素、去 GPS、截断拒绝通过；透明 PNG、真实 AndroidKeyStore 密文/篡改/身份边界通过；Room 2→3 与图片独立配额通过，共 3 项。
- 单独运行 1 项平台探针，确认系统 `SSLContext` 的 supported protocols **不含 TLSv1.3**。未发送配对令牌、未尝试低版本 TLS。

Android 官方将系统 TLS 1.3 默认支持列为 Android 10 的平台行为。开发期没有旧版本兼容需求，因此把最低版本改为 **Android 10/API 29**，同步 NDK 链接 API；不新增 Conscrypt/provider，不降低通信安全条件。图片准备统一使用平台 ImageDecoder，删除旧 BitmapFactory 全图解码/手动 EXIF 变换分支；仍用 bounds 预读先检查预算。没有删掉 EXIF/透明度/畸形与配额测试。

新包在同一 API 26 系统安装时实际返回 `INSTALL_FAILED_OLDER_SDK`，避免可安装但核心连接不可用。API 26 的 3+1 项仅是修改前证据，不计入修改后正常回归通过数。API 28 不冒充已实测，其排除是产品明确选择以平台 TLS 1.3 为最低前提。

## 2. 隔离平台探针

默认打包仍只有 `arm64-v8a`。只有同时传入以下两个显式参数，才允许构建 x86_64：

```powershell
./scripts/gradle.ps1 '-Psayin.instrumentationBuildType=imageTest' '-Psayin.emulatorTests=true' :app:assembleImageTest :app:assembleImageTestAndroidTest
```

此模式关闭 imageTest 以外的 variants，不会产生 x86_64 日常 Debug/Release。权限探针 Activity 仅存在 imageTest source set，`exported=false`，运行时系统授权只操作 `seeyuer.sayin.imagetest`。没有通过 shell grant 代替拒绝/允许弹窗验收。

```powershell
$env:CARGO_TARGET_DIR='D:/100_Projects/110_Daily/VoiceInput/desktop/target'
./scripts/verify_android_platform.ps1 -DeviceSerial emulator-5584 # API29
./scripts/verify_android_platform.ps1 -DeviceSerial emulator-5582 # API37
```

脚本拒绝物理设备 serial，校验 APK package，等待模拟器启动，使用独立目录/随机端口/主机身份；finally 撤销 reverse、停止自身 fixture、删除短期配对码文件。不会读取用户日常包。

API 37 已实际通过两个独立 instrumentation 进程：

1. 无授权时 Repository 返回 LOCAL_NETWORK_PERMISSION，不启动发现；点击真实系统拒绝按钮后仍不连接。第二次请求点击真实系统允许按钮，系统权限检查通过；真实 NSD 空浏览按预算结束、另一次浏览取消回收；通过原固定证书链路配对成功。
2. 对该模拟器隔离包执行 `pm revoke ... ACCESS_LOCAL_NETWORK` 后重启测试。保留完整身份的 SHA-256 对照一致，离线、不发现、不输入。撤销会结束进程，本用例证明重新启动后的拒绝行为，不能冒充未终止进程中的授权撤销时序测试。

最终证据目录：`android-platform-api37-6a0586af-1aae-40bc-b726-97b498f567e8`。拒绝/允许截图已检查，确为系统 PermissionController 的 Nearby devices 授权弹窗；两阶段输入次数均为 0。较早截图发生在首帧前、只有背景，已增加首帧等待并重跑，不把空白图当成界面证据。

随后在该模拟器仅对隔离包恢复权限，实际执行独立图片数据 4 项：EXIF、透明度/Keystore、Room、完整证书双 WSS 上传/取消/重启查询均通过，恰好一次 fake 输入。证据 `image-android-fixture-5d3a1566-2ca9-438f-a67a-54dfbc5ec274`。该补验不声称真实 Wi-Fi、物理发现或 NativeInput。

## 3. 最低版本扩展回归

API 29 的平台 TLS 1.3 能力测试通过，证据 `android-platform-api29-1938b983-67de-4d16-b396-91ecb33663b1`。进一步主动执行原有 40 项完整设备套件，首轮发现 3 项失败：窄屏大字号来源标签换行，以及纵/横屏键盘弹出后导航未收起。原失败证据 `image-android-a9254f9c-05c0-49e5-a292-bcdc06e3ae1d.log` 保留，不记为全部通过。

来源切换原先等宽分配，英文 Keyboard 在 360dp/150% 下被拆词；现改为按内容自然宽度排列、保持最小触摸尺寸，过窄时横向滚动，不缩小系统字号、不删文字。既有单行断言保留，并增加不可视觉溢出的检查。

键盘失败时，平台 `ViewCompat.getRootWindowInsets` 已报告 IME bottom=526px，但 Compose 布局仍按全高绘制，原导航节点仍存在。只给窗口安装兼容 sibling dispatch 没有解决问题，已撤掉该尝试，没有留下无效层。

API 29 的 IME 高度由可见区域推断，原 insets 分发可以早于可见区域稳定。现仅在该受支持平台的根组合生命周期观察 global layout：原生 IME bottom 真正改变时 requestApplyInsets，使 Compose 重新收到最新平台数据。没有第二份 IME 布局状态、估算键盘高度、定时轮询或自定义 padding；Compose 继续独占导航显隐和 imePadding，离开组合时移除监听。API 30+ 保持原生动画分发路径。

修复后原纵/横屏测试已越过导航、编辑区高度和发送位置断言；随后 AOSP LatinIME 把光标处旧单词重新放入 composing span，发送按钮按原安全规则正确禁用。测试样本改成光标位于句号后的已完成句子，仍要求按钮启用、明确点击一次、零提前发送、原文完整保留；末尾期望同时改为含句号的完整相等。未解除应用的组词保护。临时反射诊断已删除，失败截图/Insets/语义树留存机制保留。

自然宽度布局还发现无界滚动测量会给文本段落提供 Int.MAX_VALUE 宽度，不能把这种布局的 hasVisualOverflow 单独当作可见裁切。标签进一步以 IntrinsicSize.Max 约束为实际文字宽度；原单行和新增不可溢出断言均保留，不靠删断言绕过。

最终在相同修改上完成以下回归，均无失败或跳过：

| 平台 | 常规界面/文字 | 独立图片数据 | 精确输入执行数 | 证据目录后缀 |
| --- | ---: | ---: | --- | --- |
| API 29 / AOSP x86_64 | 40 | 4 | 文字 2、图片 1，均为 fake，无重放 | `6435e1f6-9a27-4672-ace3-7f1e88c45e2a` / `26be198d-3c4f-4136-9763-17268b8b643d` |
| API 33 / Redmi K40 arm64 | 40 | 4 | 文字 2、图片 1，均为 fake，无重放 | `594a2915-e148-4975-a453-68561f6ee8e4` / `d465ff0f-8e94-4db5-ac0d-0a72266206e2` |

上述目录前缀均为 `image-android-fixture-`。文字和图片分别使用独立电脑身份与目录；这两轮使用 ADB reverse，不能称为真实 LAN 或实际粘贴。API 33 的真实 LAN NSD、生产 NativeInput 与人工应用验收另见 46。

Android JVM 51 项、Debug 构建、Lint 和默认 arm64 隔离应用/测试 APK 构建通过。最终日常 Debug APK 实际核对 minSdk=29、原生库仅 arm64-v8a，没有打入测试权限 Activity；i18n 检查通过。纵/横屏键盘、大字号英文来源切换的最终截图已检查，没有裁切。可归档摘要见 [platform-validation.json](benchmarks/2026-11-15-production-images/platform-validation.json)。

## 4. 工具和边界

系统镜像与独立 emulator 从 Google 官方 HTTPS 仓库下载，按官方 repository XML 校验长度/SHA-1，未替换用户已安装的 emulator 或既有 AVD。原始镜像、APK、测试数据库和系统截图仅存忽略的 target 目录；下载/解压未更改系统代理、防火墙、网络类别或虚拟化设置。

| 下载归档 | 字节 | 官方 SHA-1 |
| --- | ---: | --- |
| AOSP x86_64-26_r01.zip | 474178332 | 432f149c048bffce7f9de526ec65b336daf7a0a3 |
| AOSP x86_64-29_r08-windows.zip | 689676765 | e4b798d6fcddff90d528d74ef22ce3dd4a2ca798 |
| Google APIs x86_64-37.0_r08.zip | 2143899425 | a266cfbcf4d6c769b166c0af4d68eff0e4f4a5e3 |
| emulator-windows_x64-16740729.zip / 37.2.13 | 455344678 | 4d7268aa7787c640ad892844e6c9a5f159e91ad3 |

API 37 镜像归档名为 r08，内部 source.properties 的 Pkg.Revision 为 7、ExtensionLevel 22；两者如实记录，不改写元数据。模拟器不能替代真实局域网发现，API 33 手机真实 LAN 的既有独立测试继续单独记证。

依据：[Android 10 平台行为／TLS 1.3](https://developer.android.com/about/versions/10/behavior-changes-all#tls-1.3)、[Android 本地网络权限](https://developer.android.com/privacy-and-security/local-network-permission)。官方行为页本轮已实际获取核对；新授权行为另以实际 API 37 系统测试验证。
