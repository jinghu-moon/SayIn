# 60 · C++ Root 剪贴板独立采集实施

日期：2026-11-15。阶段：**模块侧采集、过滤、加密历史与 Root 控制完成并通过限定真机回归；SayIn 面板读取和应用保活尚未实现**。

后续：0.3 增加 Android 17/API 37 配置及 HyperOS 4.0 格式，实际记录见 [61](61-root-helper-android17-validation.md)。本文保留 0.2 当时范围与证据。

设计依据：[56 模块设计](56-root-module-clipboard-and-keepalive-design.md)。前一阶段 [59](59-root-helper-engineering-foundation.md) 是 0.1 Kotlin 诊断骨架的历史记录，本阶段直接替换为 0.2 C++20，不维护双运行时。

## 1. 根因、基线与范围

普通 Android 应用退到后台后，没有默认输入法身份便不能可靠取得跨应用复制记录；只保住 SayIn 进程不解决读取权限。系统剪贴板也没有供应用读取的历史列表。用户确认 Root 手机由独立模块采集，非 Root 手机保持当前单条剪贴板，并明确指定 C++。

实施基线为 `063d455`。工作区另有 Android、桌面与协议改动，本次保留，没有 reset、stash 或启动并行 agent。产品 App、桌面及通信链路未修改。本阶段单独验证原生模块，不用其它改动的构建结果替代模块证据。

| 行为 | 修改前：0.1 骨架 | 修改后：0.2 原生实现 |
| --- | --- | --- |
| 模块运行时 | Kotlin DEX，一次性诊断 | C++20 原生 ELF，无 ART/JVM 依赖 |
| 跨应用文字采集 | 未实现，拒绝常驻 | 系统 Binder 监听，独立 shell UID 子进程 |
| 历史 | 无 | 去重、最近 50 条、有界加密落盘 |
| 锁屏 | 无采集能力 | 暂停，解锁后只接收新事件，不补采锁屏内容 |
| 停止/清空 | 空能力入口 | 原子实例锁、单写者清空屏障、等待停止确认 |
| App 读取/保活 | 未实现 | 仍未实现，不开放空签名白名单 |

**支持范围仅 Android 13/API 33、arm64。** 当前真实证据为 Redmi K40 / `M2012K11AC`，Android 13、MIUI 14.0.8.0、Magisk `30.6:MAGISK:R`。安装器拒绝其它 API/ABI；相同 API 的其它 ROM 仍须独立验证，不能据此声称全机型支持。KernelSU 尚未真机验收。

## 2. 结构与权限

```text
Magisk/KernelSU 生命周期脚本
  └─ C++ Root 父进程：实例锁、Root 控制、唯一存储写入者
       └─ C++ shell UID 子进程：系统剪贴板监听、解锁门禁、严格解析
            └─ 继承 SOCK_SEQPACKET 通道 → Root 父进程 → 加密历史
```

父进程不依赖 SayIn Activity/Service。子进程清空附加组，将真实/有效/保存 UID、GID 降为 2000，以实际 shell 身份和 `com.android.shell` 访问 Binder，不伪造普通 App 包名。设置不可 dump、禁止新增权限及父死亡信号；降权后重新设置 `PDEATHSIG`，父进程结束即停止子进程。父进程在 fork **之后**才加载历史密钥，子进程不继承该密钥。UID 隔离不等于独立的 SELinux 沙箱，不宣称能够抵御系统或 Root。

不装 Zygisk、不 Hook `system_server`，不修改权限/AppOps/SELinux、白名单、省电或自启动设置。没有 C++、Kotlin 双实现；测试写入器的 Java 仅为 Android 仪器化 fixture，不属于模块运行时和 ZIP。

### Binder 后端

采用 NDK `libbinder_ndk`，按固定 AOSP 13 布局调用 `IClipboard`、`ITrustManager` 与 `IActivityManager`。回调只接受系统 UID 1000 的通知并累积有界计数；读取发生在回调处理结束后的主循环，避免继承系统调用身份。未知对象、长度、布局或系统服务错误均不降级成宽松解析。

剪贴板读取由事件驱动，不周期轮询正文；每秒及每次读取前后检查主用户 CE 已解锁、当前用户为 0、设备未锁定。状态未知时拒绝采集，服务/权限故障时停止。锁屏切换、清空和恢复通过时间戳屏障抑制旧内容重新进入。

门禁检查与系统锁屏并非同一原子事务，检查结束到父进程落盘之间仍有竞态窗口；不作“锁屏瞬间绝无并发落盘”的绝对保证。

## 3. 内容、持久化与控制

- 仅接收单项 `text/plain`，合法 UTF-8，单条不超过 16KiB；拒绝敏感标记、HTML、富文本、URI、Intent、图片及复杂/未知 extras。先检查敏感元数据，再提取正文。未标记的密码/验证码不能被保证识别为敏感。
- 最多 50 条，总正文不超过 512KiB；相同正文再次复制移动到最新位置，使用递增记录 ID。既有草稿、Outbox、常用语及发送状态不受影响。
- AES-256-GCM，随机 96-bit nonce、128-bit tag、固定用途 AAD。动态调用当前系统 BoringSSL AEAD，不自制算法、不提供明文降级；缺符号、认证失败或存储不完整时拒绝继续。
- 私有目录 0700、文件 0600，逐级 `NOFOLLOW`，校验属主、普通文件和单链接；有界读，临时文件写入、fsync、rename、目录 fsync。密钥为同目录 Root 私有文件，**不属于硬件 Keystore；同时取得密钥和历史即可解密**。
- `flock` 保证单实例/单写者，不根据名称 kill、不信任 PID 文件。运行时 Root 控制仅接受固定操作及 `SO_PEERCRED` 的 UID 0，不能执行任意 shell。App IPC 未开放，签名白名单为空。
- `clear` 在父进程顺序执行，更新持久清空屏障，不清系统剪贴板；运行时 `list` 被拒绝，先停采集再列出。正文不进入状态诊断日志。
- `disable` 持久关闭并等待退出；`stop` 为升级临时停止，保留启用偏好。停用/移除标记按约 1 秒循环检测，随后等待子进程退出；受 Binder 调用等影响，不承诺硬实时停止。

**回调不是内容快照，不能保证零漏采。** 连续快速覆盖、回调合并、队列背压可能只读到最后一个值；`dropped_or_coalesced` 仅为已观察到的下界。当前屏障依据系统时间戳，相同毫秒内多次写入、时钟回拨也可能漏采。60 条测试全收不代表任意速率无损。重启不重新导入当前系统剪贴板。

## 4. 生命周期与操作

首次安装保持关闭。在 Root 管理器点击 Action 明确启用采集，再点关闭并保留历史。开机只在已有 opt-in 时尝试启动一次，故障后不无限重启。未实现自动拉起 SayIn、自启动设置引导、进程保活或通知入口。

升级脚本先停止旧原生所有者，保留配置/历史；0.1 骨架没有原生所有者。卸载先关闭、等待停止，再删除实现已知的数据和密钥，不递归删除未知目录。管理器真实升级、重启启用和卸载尚需单独验收；主机脚本/隔离运行时验证不能替代。

```sh
BIN=/data/adb/modules/sayin_helper/bin/sayin-helper
$BIN status
$BIN disable
$BIN list
$BIN clear
```

Root CLI 为开发管理入口，不是 SayIn 面板集成。不要把 `list` 正文粘到日志/聊天。此前已安装的 0.1 骨架不会因电脑构建而自动升级。

## 5. 实际验证

构建入口：

```powershell
pwsh -File scripts/build_root_helper.ps1
python root-helper/tests/build_device_fixture.py
python root-helper/tests/verify_device.py --serial dc39c31d --allow-synthetic-clipboard-writes
```

NDK 固定 `29.0.14206865`，C++20，arm64，静态 libc++。主机 CMake/CTest 与原生构建启用告警即错误。模块不接入 App Gradle，不改变其 Kotlin/JDK 版本。

| 验证 | 实际结果 |
| --- | --- |
| 主机 CTest | 1 个程序、**1666 个断言通过**，含 UTF-8、合法 Parcel、所有截断、变异输入、敏感元数据、历史边界、编码损坏和清空 |
| arm64 原生构建 | 通过 `-Wall -Wextra -Wpedantic -Werror`；ELF 检查通过 |
| Python 工程测试 | **8/8 通过，0 跳过**；打包白名单/摘要/确定性/权限、坏 ELF 与失败重建、4 个 Shell 语法、mock 安装/拒绝路径 |
| 真机 Binder/加密 | 实际服务访问、AES-GCM 往返及篡改拒绝通过 |
| 真机最终自动回归 | 下列 **13/13 通过** |
| 人工协助锁屏/解锁 | 锁屏写入 3 条后历史仍为空；解锁没有补入，随后 3 条新样例正常记录 |
| 快速连续样例 | 60 条、写入间隔 10ms，本轮全部接收，最终只保留最近 50 条；非持续吞吐或功耗基准 |

最终自动回归逐项检查：Binder/加密；5 条连续中文与 emoji；敏感/超大/URI 跳过；重复实例拒绝；清空屏障及持久化；静态文件无测试明文；普通 shell 读取拒绝；重启保留历史且不重导入、stop 保留 opt-in；杀死核验过的隔离父进程后 shell 子进程退出；模块 disable 标记停止双进程；篡改历史拒绝读取及启用；丢失密钥拒绝重建；已知数据清理。

采集使用随机 `/data/local/tmp/sayin-native-verification-*` 数据目录、测试专用前缀、最长 120 秒会话及隔离 `seeyuer.sayin.helperfixture`。生成样例会覆盖系统当前剪贴板，不备份或输出原内容；仅保存生成的样例。测试没有修改用户日常配对、端口、电脑数据或发送状态，没有强停日常 SayIn，**没有把“代码无 App 依赖”冒充“App 被强停后实测通过”**。

一次最终脚本初跑因本 ROM 无 `/proc/PID/task/PID/children` 失败，改从 `ps` 的真实 PPID 取得子进程，再核验父进程 ELF 路径后终止。父死亡断言保留，随后完整 13 项通过。

可复核的本机产物在被忽略的 `docs/research/native-clipboard/`（固定 AOSP 源码及 `evidence/device-final.json`）、`root-helper/build/host/Testing/Temporary/LastTest.log`。最终真机验证 ELF SHA-256：

```text
4cb771f24799008618b3b04618ec24fd95eaca840b5cae92f8dc9db37c760cbc
```

本次没有修改产品代码/资源/发送链路，未重跑 Android JVM/Lint/i18n 或桌面完整回归；这些测试也不能证明 Root 采集正确。后续 App 接入必须补相应产品回归。

## 6. 安装包与后续验收

产物：`root-helper/build/distributions/sayin-helper-0.2.0-dev-arm64.zip`，276069 字节。SHA-256：

```text
d50195539936393e93a0450551a40af04e1403b954a3b082bda828e4d116ed2f
```

已通过 ADB 传至当前手机 `/sdcard/Download/sayin-helper-0.2.0-dev-arm64.zip`，手机 `sha256sum` 与电脑一致；未自动安装或启用，原 0.1 骨架仍保留。

ZIP 含原生 ELF、生命周期脚本、默认关闭策略、许可、构建清单和逐文件摘要，不含 DEX、APK、历史、私钥或设备日志。摘要用于完整性校验，不是签名信任根。Magisk 会移除安装专用 `customize.sh`，核验安装目录时只能明确排除这一项，不能宽泛忽略错误。

尚未验证：0.2 管理器实际升级/重启/卸载；KernelSU/其它 ROM/API；主用户切换的真机行为；长时间待机/功耗；系统服务重启、磁盘满/断电；用户实际跨应用操作的长期漏采率。未来要支持其它 Android 版本，应逐版本核对 Binder/Parcel 与系统加密 ABI 并真机验收，不能只扩大版本号条件。

下一阶段是固定操作的 App 身份鉴权 IPC、同一组合面板读取/删除/清空及状态反馈，随后再单独实现应用保活。不得开放原始文件、通用 Root 命令或绕过配对/发送授权来省略接口设计。

## 7. 官方依据

- [AOSP 13 IClipboard](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/core/java/android/content/IClipboard.aidl)、[ClipboardService](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/clipboard/ClipboardService.java)：事务定义、shell UID 与后台限制。
- [ClipData](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/core/java/android/content/ClipData.java)、[ClipDescription](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/core/java/android/content/ClipDescription.java)、[TextUtils](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/core/java/android/text/TextUtils.java)：固定 API 的 Parcelable 格式和敏感标记。
- [NDK Binder](https://developer.android.com/ndk/reference/group/ndk-binder)：公开 Binder NDK 接口；框架服务的内部 AIDL/Parcel 布局不因此成为稳定公共协议。
- [Magisk](https://topjohnwu.github.io/Magisk/guides.html)、[KernelSU](https://kernelsu.org/guide/module.html)：模块生命周期，不能代替本模块设备验收。
