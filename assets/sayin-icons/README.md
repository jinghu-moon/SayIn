# 言达 · SayIn 图标资源

两个图标（B-field 光标入框、F-yan 言字标），各自包含 Android 与 Windows 两套资源。

## Android（android/）
- res/ 直接合并进工程的 app/src/main/res：自适应图标（前景 + 背景 + 单色层）、旧系统回退 PNG（mdpi–xxxhdpi，方形与圆形）。
- Manifest：android:icon="@mipmap/ic_launcher"  android:roundIcon="@mipmap/ic_launcher_round"
- play-store/ic_launcher-playstore.png：Google Play 商店图标，512×512，32 位 PNG，满铺方形（圆角由 Google Play 处理）。

### Android 其他场景（res/drawable/）
- ic_stat_sayin.xml：状态栏通知小图标，24dp 纯白剪影，图形在 20dp 内。用 setSmallIcon(R.drawable.ic_stat_sayin)。
- ic_qs_tile_sayin.xml：控制中心（快捷设置）磁贴图标，24dp 单色剪影，由系统按开/关状态着色。
- ic_float_ball.xml：悬浮球默认态，56dp 深色圆底 + 图形，带一圈极淡的浅色描边，深色壁纸上也不会消失。
- ic_float_ball_active.xml：悬浮球激活态（例如正在听写），反色为浅色圆底 + 深色图形。
- 贴边收起时建议把悬浮球透明度降到约 60%。

## Windows（windows/）
- sayin.ico：应用图标（16 / 20 / 24 / 32 / 40 / 48 / 64 / 96 / 256），小尺寸为按像素单独调整的版本。
- tray/：托盘图标。for-dark-taskbar 为浅色图形，for-light-taskbar 为深色图形，按系统任务栏主题切换。
- png/：各尺寸 PNG。
- msix/Assets/：若以后打包为 MSIX，可直接使用（含 targetsize、altform-unplated、altform-lightunplated，以及 Square44 / Square150 / Wide310x150 / StoreLogo 各缩放档）。磁贴背景色在清单里设为 #252525。

## 源文件
svg/sayin-brand.svg（深色底板）与 svg/sayin-mono.svg（单色）。
