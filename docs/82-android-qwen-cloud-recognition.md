# Android 千问云端流式识别

## 接入

在「设置 → 语音识别 → 云端服务」选择千问，填入北京业务空间 ID 和百炼 API Key。设置页复用 miuix 的 `WindowDropdownPreference`、`Card`、`TextField` 和 `WindowDialog`。两家凭据分别加密保存，切换服务后可直接使用该服务已保存的配置。热词由当前服务读取，清除配置只影响当前服务的凭据。

手机直连北京业务空间的官方 WebSocket，使用 `qwen-audio-3.1-asr-flash-streaming`。千问和豆包共用 WebSocket 生命周期、PCM 编码、发送队列、取消和超时处理。千问协议等待 `task-started` 后开始采集音频，每帧发送 100 ms 的 16 kHz 单声道 PCM。临时结果按句覆盖，已定稿的句子合并为全文。客户端停止采集并发送 `finish-task` 后，收到 `task-finished` 才能进入发送策略判断。服务拒绝调用、断线和超时会结束识别并保留已有草稿。 [官方接入文档](https://help.aliyun.com/zh/model-studio/qwen-audio-asr-streaming-websocket-api) · [客户端事件](https://help.aliyun.com/zh/model-studio/qwen-audio-asr-streaming-client-events) · [服务端事件](https://help.aliyun.com/zh/model-studio/qwen-audio-asr-streaming-server-events)

「测试连接」使用与录音相同的接入层，发送 1 秒合成静音。千问上报的累计输入、输出 Token 随识别结果返回。当前范围包含解析与测试，用量持久化和费用统计尚未接入。

## 验证

| 项目 | 修改前 | 修改后 |
| --- | --- | --- |
| 云端服务 | 豆包 | 豆包与千问分别配置，共用流式会话 |
| 千问定稿 | 无 | 模拟 WebSocket 验证就绪、音频尾包、分句合并、定稿、超时、权限拒绝和累计用量 |
| JVM | 192 项通过 | 197 项通过 |
| K40 隔离回归 | 155 项文字与界面、4 项图片通过 | 157 项文字与界面、4 项图片通过 |

```powershell
python scripts/check_i18n.py
scripts/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
scripts/gradle.ps1 '-Psayin.instrumentationBuildType=imageTest' :app:assembleImageTest :app:assembleImageTestAndroidTest
scripts/verify_android_transport.ps1 -DeviceSerial dc39c31d -FullSuite -SkipBuild
adb -s dc39c31d install -r android/app/build/outputs/apk/debug/app-debug.apk
```

`check_i18n.py`、Lint 和 Debug 构建通过。K40 隔离套件新增服务下拉切换及配置持久化测试、双服务凭据加密测试。文字组记录两次假输入，独立图片组记录一次，均符合预期。结果见本机 `desktop/target/image-android-fixture-627205f2-02b5-40ff-a703-18beb39fce10/result.json` 和 `desktop/target/image-android-fixture-f463436c-ff7c-4397-8821-ad65acfa1d59/result.json`。

日常 Debug 包 `seeyuer.sayin` 已覆盖安装到 K40，ADB 返回 `Success`。

2026-11-21，K40 发起真实千问请求后收到服务端失败事件。电脑以同一业务空间分别发送静音和项目音频样本，两次都收到 `task-started`，随后收到 `task-failed`，错误码为 `AccessDenied.Unpurchased`。新版真机已验证该响应映射为 `CloudIssue.AccessDenied`。设置页会提示检查该北京业务空间是否已开通千问 Audio 3.1 流式识别，以及 API Key 是否有调用权限。

覆盖安装后，用户确认千问语音识别在手机端测试正常。早期隔离测试发送项目样本和合成静音，临时凭据已清理。设备通信回归使用假输入服务。真实服务返回的 Token 数值尚待核验。
