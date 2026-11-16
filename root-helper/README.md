# SayIn Helper

独立 **C++20** Root 模块：监听并加密保存剪贴板文字，SayIn 不承担采集生命周期。当前支持 **Android 13/API 33 和 Android 17/API 37、arm64**；已分别在 Redmi K40 与 Xiaomi 25102RKBEC 验证独立采集。API 37 的 HyperOS 4.0 使用明确的 Bundle 格式配置，不代表其它 ROM 已验收。0.4.0 增加签名校验的本地 socket 和 SayIn 面板读取，实施及本次真机范围见 [62](../docs/62-root-helper-app-clipboard-integration.md)。0.4.1 修复普通文本附带空 span 包装及 TextLinks 分类信息时被误拒绝的问题，并消除面板加载闪现，见 [63](../docs/63-root-clipboard-classification-and-loading-fix.md)。应用保活尚未实现，普通手机继续当前单条剪贴板。

设计：[56](../docs/56-root-module-clipboard-and-keepalive-design.md)；Kotlin 骨架历史：[59](../docs/59-root-helper-engineering-foundation.md)；原生首版：[60](../docs/60-root-helper-native-clipboard-collection.md)；Android 17 适配：[61](../docs/61-root-helper-android17-validation.md)。

```text
root-helper/
  CMakeLists.txt
  include/                 规则、平台和存储接口
  src/
    binder.cpp             原生 Binder shell UID 监听，API 33/37
    parcel.cpp             有界、严格的纯文字 Parcel 解析
    history.cpp            最近 50 条、去重和清空屏障
    storage.cpp            Root 私有目录、原子提交和权限校验
    crypto.cpp             系统 AES-256-GCM，缺失能力立即失败
    main.cpp               单写者、原子实例锁和 Root 控制
    app_bridge.cpp         固定包名/签名鉴权、一次性本地 socket
    app_protocol.cpp       有界预览、按修订读正文和清空
  module/                  管理器安装模板，首次采集默认关闭
  tests/                   CTest、主机工程测试、隔离真机验证
  licenses/                静态 C++ 运行库声明
  build/                   忽略的二进制、测试制品和 ZIP
```

仓库根目录构建：

```powershell
pwsh -File scripts/build_root_helper.ps1
```

使用 NDK **29.0.14206865**、CMake/Ninja；SDK 路径从 `-SdkRoot`、`ANDROID_HOME` 或 `android/local.properties` 解析。主机测试默认使用本机 MinGW GCC，可用 `-HostCompiler` 指定。模块不再依赖 Kotlin、Gradle、DEX、JVM 或 Rust；Android App 的工具链不变。

产物：`root-helper/build/distributions/sayin-helper-0.4.1-dev-arm64.zip` 及 `.zip.sha256`。白名单打包，包含原生 ELF、许可、构建清单及逐文件摘要；没有 APK、设备数据、私钥或用户历史。摘要用于完整性校验，不充当签名信任根。

## 安装与使用

通过 Magisk/KernelSU 管理器从本地安装，重启；Recovery 不支持。KernelSU 0.3.1 正式升级、正式路径隔离回归及相同 ELF 的锁屏/解锁见 61；Redmi K40 的 Magisk 0.4.0 正式更新与开机只读实例复验见 62。实际 Action 切换、启用后开机恢复和卸载仍待验收，不混用不同版本/设备的证据。

**首次安装不采集。** 在管理器点击模块的 Action，明确启用采集；再点关闭，保留加密历史。更新保留启用偏好与历史；卸载停止采集并删除本实现已知的历史/密钥文件，不递归删除未知目录。停用/移除标记最多约一个控制循环后停止，真实时延见实施记录。

Root 调试命令（不要把 `list` 输出保存为普通日志）：

```sh
/data/adb/modules/sayin_helper/bin/sayin-helper status
/data/adb/modules/sayin_helper/bin/sayin-helper diagnose-clipboard
/data/adb/modules/sayin_helper/bin/sayin-helper disable
/data/adb/modules/sayin_helper/bin/sayin-helper list
/data/adb/modules/sayin_helper/bin/sayin-helper clear
```

`list` 是停止实例后的 Root 诊断入口，保证唯一写入者；0.4.0 运行时由 App 使用鉴权 LIST/GET，不使用此命令。运行中支持 `status`、`clear`、`disable`、`stop`。`disable` 持久关闭并等待停止；`stop` 为更新临时停止，保留启用偏好。`clear` 只清历史、不改系统剪贴板。Root CLI 是开发管理入口，不是 App 面板接口。

`diagnose-clipboard` 仅由 Root 在解锁时检查当前格式，输出固定成功/错误码，不输出正文、标签、extras 或原始 Parcel，不保存历史。

只接收单条 `text/plain`，单条 UTF-8 ≤16KiB，总正文 ≤512KiB，历史最多 50 条。接受无实际 span 的纯文本包装和已知格式的 TextLinks 分类元数据，但不保存/执行分类信息。敏感标记、URI、HTML、实际带样式的富文本、图片、复杂 extras 和未知对象跳过；锁屏/非主用户/解锁状态未知时暂停，恢复只接收新事件。系统时间回拨、相同毫秒的多次写入、回调覆盖都存在漏采边界。

Root 父进程唯一持有存储和密钥，采集子进程降为 shell UID，仅经继承 socketpair 交付有界数据。加密使用当前系统 `libcrypto` 的 BoringSSL AEAD API，运行时检查能力；没有内置加密实现，没有不支持时降级明文。该系统 API 不是跨 Android 版本稳定 ABI，因此只开放已核对的版本配置，实际 ROM 验证范围见 61。

密钥为 Root 私有 0600 文件，随历史一同保存在私有目录；能抵御普通 App/shell 直接读取，**不能抵御 Root 或同时取得密钥和历史的攻击者**。不宣称硬件 Keystore 保护。坏密钥/坏密文拒绝读取、启用，不自动重建清空。

持久实例使用 `flock`，不按名称 `kill`，不相信 PID 文件。子进程随父进程结束退出；每次启动仅尝试一次，无无限拉起。采集从不打开 SayIn、麦克风、模型或 WSS，不改变原有发送授权。

## 验证

```powershell
python -m unittest discover -s root-helper/tests -p 'test_*.py' -v
python root-helper/tests/build_device_fixture.py
python root-helper/tests/verify_device.py --serial <设备序号> --allow-synthetic-clipboard-writes
```

配套 App 读取验证先构建隔离 `imageTest` 与仪器化 APK，再运行 `python root-helper/tests/verify_app_bridge.py --serial <设备序号> --allow-synthetic-clipboard-writes --ordinary-regression`。脚本仅使用随机测试目录和隔离 App，验证同包名错误签名拒绝、完整页/快捷卡插入、确认清空及普通剪贴板回归；不替换日常配对。实际版本和设备范围以 62 为准。

真机脚本要求已安装隔离 `seeyuer.sayin.helperfixture`，生成样例会覆盖当前系统剪贴板；不读取原剪贴板，不采集用户历史。历史和 binary 使用独立随机测试目录/前缀，采集期间非测试前缀的正文不存储。测试 fixture 的 Java 仅用于 Android 仪器化写入样例，不属于模块实现或安装包。

`SHA256SUMS` 为安装包清单；Magisk 安装后移除 `customize.sh`，核对正式目录时须明确排除这一项，其余文件仍必须匹配。

0.4.0 开机建立只读控制实例，首次仍不采集；Action 启停采集并接续实例，关闭后可继续查看已保存记录。App 需要配套签名匹配的新版；0.3.1 没有 App IPC。当前仅准入明确本机开发签名的 v2-only APK，详见 62。
