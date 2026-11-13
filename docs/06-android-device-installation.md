# VoiceLink：无SIM手机的调试安装记录

日期：2026-11-12。

## 1. 设备与现象

已连接设备为小米`M2012K11AC / alioth`，Android13/API33，MIUI14，版本`V14.0.8.0.TKHCNXM`。普通`adb install`此前返回`INSTALL_FAILED_USER_RESTRICTED`。

Android安装应用本身不要求SIM卡；厂商的USB安装验证可能另有限制。本次不依赖插卡或修改USB安全配置，使用设备已有的Root授权完成项目调试APK安装。

## 2. 已验证的方法

先核对本地APK的SHA-256与05国际化构建记录一致，再依次执行：

```powershell
adb shell su -c id
adb push android/app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/voicelink-debug.apk
adb shell "su -c 'pm install -r /data/local/tmp/voicelink-debug.apk'"
adb shell am start -W -n seeyuer.voice.link/.MainActivity
adb shell rm /data/local/tmp/voicelink-debug.apk
```

上述命令适用于当前仅连接一台设备的情况；多设备时需用`adb -s <设备序列号>`明确指定。Root入口存在不代表已获授权，必须先检查`su -c id`的实际结果。

## 3. 实际结果

- `su -c id`返回`uid=0(root)`，SELinux上下文为`magisk`，确认ADB Shell已有Root授权。
- Root `pm install -r`返回 **`Success`**。
- 已安装包为`seeyuer.voice.link`，versionName`0.1.0`、versionCode`1`、minSdk26、targetSdk37。
- 应用运行UID为10470，按普通应用运行；本次未给应用申请或授予Root能力。
- `am start -W`返回`Status: ok`，启动Activity为`.MainActivity`，并确认应用进程存在。
- 已实际查看启动截图：简体中文页面、输入区、未连接状态及语言选择区域正常显示。截图保存在本机忽略目录`android/build/verification/installed-home.png`。
- 手机上的临时APK已删除；未禁用安全中心、包验证器或修改未知来源安装策略。

这是安装和启动冒烟验证。尚未据此宣称语言切换、草稿重建、输入法组合输入、旧系统行为及WSS链路已完成验证。

设备已有Root使本次安装可行；若其他设备没有Root，可将APK复制到下载目录后通过手机文件管理器执行本地安装，按系统提示授予该来源安装权限。普通`adb install`不存在一个通用的`--force`参数能够无条件覆盖厂商或设备管理限制。

此次使用现有Root进行开发安装，不代表[02辅助模块计划](02-voicelink-root-assistant-plan.md)已开始实现。
