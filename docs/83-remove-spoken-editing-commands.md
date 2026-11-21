# 移除 Android 语音编辑指令

日期：2026-11-21。范围：完整删除“语音编辑指令”（spoken editing commands）功能，不做兼容保留。

## 1. 决定与原因

用户明确要求移除该功能：语音识别的最终文本应始终作为普通草稿内容，不再根据“换行 / 删除上一句 / 清空 / 翻译 / 发送”等口令执行编辑或发送。

该功能默认关闭，删除后默认行为不变；仅原先主动开启的用户会失去口令解释，口令词将作为正文写入草稿。

## 2. 删除内容

代码：

- 删除 `ui/VoiceCommands.kt`（命令枚举、别名表、片段解析与编辑逻辑）。
- `SpeakSession` 删除 `voiceCommandsEnabled`、`changeVoiceCommands()`，以及 final 文本处理中的命令分支与“翻译/发送”意图；final 现在只做：
  - transcript 非空 → 以 `range.text(transcript)` 写回冻结选区；
  - transcript 为空且此前有文字 → 恢复原草稿；
  - 完成输入、自动发送、直接发送、语音后自动润色等原有分支保持不变。
- 设置 → 语音识别页删除“语音编辑指令”开关、摘要与帮助文案（`voice-commands` 测试标签随之消失）。
- 删除中英文 `settings_voice_commands`、`settings_voice_commands_summary`、`settings_voice_commands_help` 三条资源。

测试：

- 删除 JVM `VoiceCommandsTest`（11 项）。
- 删除设备 `VoiceCommandSessionInstrumentedTest`（4 项）。
- `MobileShellInstrumentedTest` 的设置用例移除口令开关断言并更名为 `settingsExposeModelManagementAndFloatingButtonOptions`。

## 3. 数据与升级

- 旧安装的 `presentation.voice_commands` 键保留但不再读取；当前阶段不做迁移或清理，不影响草稿、配对、Outbox 或接收闸门。
- 无 Adapter、无兼容分支、无隐藏开关。“口令不生效”是唯一行为。

## 4. 验证

```powershell
python scripts/check_i18n.py
scripts/gradle.ps1 :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin
scripts/gradle.ps1 :app:assembleDebug
adb -s dc39c31d install -r android/app/build/outputs/apk/debug/app-debug.apk
```

结果：

- `check_i18n.py`：通过（中英键完整、占位符一致）。
- JVM：186 项通过、0 失败（当前工作区同时包含并行进行的 Qwen 云端识别改动，因此总数不是单纯 192−11）。
- `:app:lintDebug`：0 errors（231 warnings + 7 hints，均为既有风格或无关提示）。
- `assembleDebug` 成功；日常包 `seeyuer.sayin` 已覆盖安装，配对、草稿、历史保留。
- 真机设置页确认“语音编辑指令”开关与说明已消失，离线/云端识别、热词等其余项正常。
- 设备套件未整体运行；当前合并工作区的期望数为 153（155 基线 + 并行 Qwen 新增 2 项常规用例 − 移除口令 4 项），仅按算术更新，未以整套运行核对。

## 5. 历史文档

[68](68-android-workflow-features.md) 第 3 节与 [73](73-android-ai-polish-design.md) 的 final 意图列表记录的是当时的实现/设计，已加注“已移除”，不再代表当前行为。
