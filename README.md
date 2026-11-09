# Gazeline

Android 手机 + Cardboard 卡纸盒的 VR 视频播放器。核心交互是**凝视 + 停留**：戴上之后不摘机、不用手柄，靠转头把目标移到视点中心完成操作。内容规划支持 360° 全景与 SBS 3D，片源走本地文件或 SMB 局域网串流。

**品牌决定：品牌名仅 `Gazeline`，无中文名。** launcher label、启动页、关于页、商店标题只出现 `Gazeline`。商店标题为 `Gazeline · Cardboard VR Player`，品类词只放副标题与描述。

已知限制：launcher label 是 8 个拉丁字母，默认 4 列图标网格可完整显示；大字体或 5 列网格下可能被省略号截断。不通过缩小字号或改名规避。

> 当前为 alpha 阶段（版本号以 `version.txt` 为准）：本地视频播放、音轨播放、凝视控制与 S5 两段式校准（步进 / 保存 / 回滚）是真实功能；校准参数对画面几何的影响、字幕与 SMB 仍待后续版本或真机验证。

## 当前完成度

这一版把**凝视层**接入本地播放（里程碑 3）：S1–S4 全部控件、回中键、唤醒条、暂停/进度/±10 s、菜单与退出二次确认都由视线触发，不再需要点屏幕：

| 已实现 | 说明 |
|---|---|
| 启动页 | 视镜标记 + `GAZELINE` 字标 + 副标题，无中文品牌名 |
| 手机端入口（alpha6） | 首页预览下方新增显式「打开视频 · 360°/SBS 3D」主按钮，并标注上方为静态预览；不再依赖预览里无标签的中央播放键热区 |
| 外层 HUD（S1–S3） | 播放 Ø10°(0,-18)、±10 s Ø8°(±16,-18)、字幕(-30,-18)、菜单(+30,-18)、回中 Ø7.5°(-37,-25)、字幕带 @-33、时间轴 @-11、顶部状态 @+39 |
| 凝视命中 | 进入 0.85R、离开 1.15R，120 ms 去抖；聚焦 150 ms + 预备 250 ms 后才开始充能 |
| 停留分档 | L1 1.2 s（播放/±10 s）、L2 1.8 s（字幕/菜单/回中/S4 前两行）、L3 2.5 s（S4 退出）；环显示剩余等待 |
| S4 菜单 | 面板 56°×57° @ 视线锚点，四行 52°×8°（字幕/音轨/倍速/退出）；400 ms 保护期、视线移出面板 1.2 s 关闭、30 s 自动关闭且关闭前 3 s 提示 |
| 退出二次确认 | 退出走 L3 → 42°×30° 确认框，默认停在「取消」，确认项再停留 L2 1.8 s；取消/返回键回到菜单或 HUD |
| 退出与撤销 | 确认后回到手机端并携带进度，5 s 内底部出现「已退出播放 · 继续播放」条，点了从原位置续播 |
| 播放速度 | 菜单倍速行循环 0.5/0.75/1.0/1.25/1.5/2.0×，改解码节奏；重进播放器恢复 1.0× |
| 防连发 | 动作后 500 ms 冷却，且同一目标必须移出离开半径再回才可再次充能 |
| 取消 | 移出目标 250 ms、角速度 >25°/s、返回键（物理/线控）立即取消；>10°/s 暂停充能，<6°/s 恢复 |
| 回中键 | 1.2 s 充能 + 0.6 s 待发两段式；110 ms 遮罩 → 冻结画面 → 限速 180°/s（单帧 ≤2.5°）旋转 → 120 ms 淡出；1000 ms 冷却 |
| 唤回 | 播放中闲置 5 s 开始淡出、6 s 只剩唤醒条 25°×3° @-39°；注视 0.8 s 唤回；暂停时永不隐藏（40% 亮度）；新视频前 15 s 常显 |
| 本地视频播放（M2） | 点播放键 → 选 360 / SBS → 系统文件选择器选视频；MediaCodec 解码到 SurfaceTexture |
| 解码器选择 | HEVC 优先 `c2.qti.hevc.decoder`，其次任意 qti、任意硬件、最后平台默认；日志记录实际 codec 名与 `isHardwareAccelerated` |
| 360 管线 | 独立 `Projection360`：水平 FOV 90°、k1 -0.20、k2 0.032、IPD 0°，等距柱状采样 + 镜头预畸变 |
| SBS 管线 | 独立 `ProjectionSbs`：垂直 FOV 85°（水平约 90°）、k1 -0.26、k2 0.045、虚拟屏宽 70°，左右半幅各取一半 + 预畸变 |
| 播放控制 | 播放/暂停、±10 s 跳转（`positionUs` 与时间轴同步）；字幕键切换开关并提示“暂无字幕轨” |
| 音轨播放（alpha9） | 同一来源的音频轨（第 1 条，可循环切换）→ MediaCodec AAC/其他 → `AudioTrack` 44.1/48 kHz 流式播放；音频播放头作为主时钟，视频按帧 PTS 追音频；暂停/恢复/跳转同时作用于两轨；文件无音轨时静默、不报错，菜单音轨行显示「无」 |
| 调试 HUD | 文件名 / 格式 / 分辨率 / 进度 / 实际 codec 写在顶部两枚状态卡片，不再单独占用一行 |

**里程碑 2/3 实测（模拟器，2026-11-08/09）**

- 模拟器 `sdk_gphone64_x86_64`（ranchu），没有 Qualcomm 解码器；实际选中 `c2.goldfish.hevc.decoder`，`isHardwareAccelerated=true`（goldfish 的虚拟硬解，**不代表真机 qti 读数**）。
- 8-bit HEVC Main 1280×640（360）与 1280×720（SBS）：解码 29.8–29.9 fps、GL 绘制 59.5–59.9 fps，迟到帧 0。
- **10-bit HEVC（Main10）在模拟器上失败**：`GoldfishHevcHelper: error in decodeHeader: 0x43dd`，codec 直接进入 Released，App 报 `IllegalStateException` 并停播。真机 qti 是否支持 10-bit 尚未验证。
- 凝视链路：拖拽把目标移到视点中心后，150 ms 聚焦 + 250 ms 预备 → 环与“继续注视 X.X 秒”出现 → L1 1.2 s 执行；执行后再盯住同一目标不会连发，需先移出离开半径再回。
- 回中：双击/凝视回中键均触发，遮罩、冻结、限速旋转与淡出完成，结束后视野回到锚定方向。
- 唤回：播放中闲置 5–6 s 淡出到只剩唤醒条，注视 0.8 s 后 HUD 原样唤回；暂停时 HUD 常留。
- 播放键执行后暂停/继续、快进键执行后 `seek 10000ms` 均在 `logcat` 与时间轴读数上确认；360 与 SBS 两种管线各跑一遍。
- 模拟器没有真实头动，验证时用 `--ez sensor false` 启动 PlayerActivity，以拖拽代替转头（横屏拖拽按屏幕轴映射）。
- S4 菜单（alpha5）：凝视菜单键 1.8 s 打开面板，四行分别为字幕/音轨/倍速/退出；倍速行循环切换并改解码节奏；退出行走 L3 2.5 s 打开二次确认，确认框默认焦点在「取消」，确认项再停留 1.8 s 才退出。
- 退出链路（alpha5）：确认后回到手机端并带 `position`，5 s 内底部显示「已退出播放 · 继续播放」条；点它从原位置续播（`seek` 到上次进度）。
- S5 两段式校准（alpha8）：手机端选型号 / 拖 IPD 滑杆 / 记屏幕尺寸，「开始校准」进专用校准页；校准页先做 20 s 可读性自测，再进四组 `−/＋` 微调（瞳距、k1、k2、每眼垂直）。`−/＋` 走 L1 1.2 s，`恢复默认`/`保存并继续` 走 L2 1.8 s，每次执行后 500 ms 冷却；保存写入 SharedPreferences 并回到手机页，返回键回滚到上次保存值。模拟器上实测：k1 由 −0.20 步进到 −0.22、再 −0.24 且测试卡网格即时变化；保存后重启仍为 −0.22；返回键丢弃 −0.24 后重进回到 −0.22。
- 音轨（alpha9，模拟器）：H.264 1280×720 + 双 AAC 44.1 kHz 立体声轨测试片，日志 `AudioTrack 44100Hz ch=2 enc=2`、`c2.android.aac.decoder`；`dumpsys audio` 显示 `state:started`、`channelMask=0x3, sampleRate=44100`。连续播放约 3.5 分钟，视频与音频各自循环 17 次，循环点相差 15–30 ms（视频先 `seek 0`，音频 `drainTail` 播完尾音后跟到）。带 `--el position 5000` 启动时，视频与音频都执行 `seek 5000ms`。纯视频文件日志为 `source has no audio track, staying silent`，无异常、无 AudioTrack、无错误提示。
- 音轨切换、倍速静音与暂停/恢复的音频侧：代码路径与视频共用 `renderer.pause/resume/seek/setSpeed`，但模拟器上用拖拽把视点精确停到菜单/暂停键的成功率不稳定，**未实测**（见下）。

**未实现（不要在演示中当成可用功能）：**

- 10-bit HEVC 真机硬解（模拟器已证实不可用，真机待验）
- 字幕渲染（字幕键与菜单字幕行只切换状态并提示“暂无字幕轨”）
- 倍速下的音频变速：0.5–2.0× 时音轨静音（占位）；回到 1.0× 重新对齐视频位置
- 音轨切换/暂停/恢复的音频侧未在模拟器实测；真机耳机输出与延迟未验
- SMB 串流、关于页
- 72 Hz 刷新、长时间耐力测试
- 校准参数的**画面侧效果**（IPD→SBS 窗口偏移、每眼 Y 偏移）只做到每帧下发 uniform；模拟器无法打开视频源核对，真机待验

启动页与选片仍靠点屏幕；S5 的「开始校准」也是戴前的一次触摸（这是规格要求：戴前输入、戴后全程凝视）。**进入播放器和校准页后，HUD 全部走凝视**。所有角度按 90° 单眼 FOV 定义，像素值随设备换算。

## 构建

无 Gradle。构建链是 `aapt2 → javac → d8 → zipalign → apksigner`，脚本为 `build.ps1`。

依赖：

- JDK 17（`javac` / `keytool` 在 PATH）
- Android SDK：`platforms;android-35`、`build-tools;35.0.0`
- PowerShell 5.1 或 7+

```powershell
$env:ANDROID_SDK_ROOT = 'C:\path\to\sdk'
powershell -File build.ps1            # 同时出 release 和 debug
powershell -File build.ps1 -Variant release
```

产物在 `build/`：

- `gazeline-<version>-release.apk`（包名 `com.yunyu697.gazeline`，label「Gazeline」）
- `gazeline-<version>-debug.apk`（包名 `com.yunyu697.gazeline.debug`，label「Gazeline debug」，仅用于模拟器/本机调试）
- `SHA256SUMS`

debug 变体改过包名，**会与正式版并存**，不会覆盖它；GitHub Release 只上传 release APK，避免装出两个同名图标。debug 变体额外打开 `android:debuggable`（`build.ps1 --debug-mode`），方便把测试片放进应用私有目录做音轨/字幕验证（模拟器上可直接 `adb root` 后 push）；**release 包不可调试**。alpha 阶段两个 APK 都用 `debug.keystore` 签名（密码为 Android 调试约定值），因此**不适合上架应用商店**；正式发布前会换成独立 release key。

## 安装

1. 手机允许安装未知来源应用（或开启 USB 调试）。
2. 只安装 `gazeline-<version>-release.apk`：`adb install -r build/gazeline-<version>-release.apk`，或直接把 APK 传到手机点安装。
3. 启动页最下方实时显示已安装的 `versionName`（如 `v1.0.0-alphaN`）；如果显示的不是你要的版本，说明点开的是另一个安装（见下）。
4. 启动后 1.7 s 自动从字标进入 HUD 预览；底部三个按钮切换页面。
5. 点琥珀色「打开视频」→ 选 360 / SBS → 系统文件选择器选片，进入播放器。播放器内：有 Rotation Vector 时转头控制视线，没有时拖拽模拟；把控件移到视点中心并停留即可充能，移开或按返回键取消。

### 装完还是旧界面 / 旧图标？

装出两个图标、或升级后仍是旧界面，几乎都是这两个原因：

- 之前装过 `com.yunyu697.gazeline.debug`（旧版 Release 里同时挂了 debug APK）。它包名不同，会作为**第二个应用**安装，图标和名字都跟正式版一样，打开旧图标看到的还是旧版本。到「设置 → 应用」卸载 `Gazeline debug`，只保留 `Gazeline`。
- 旧安装与当前 APK 签名不同（例如早期用别的密钥自建过），安装器会拒绝覆盖并另装一份。卸载旧版本后再装 release APK。

核对办法：启动页底部的版本号 + `adb shell dumpsys package com.yunyu697.gazeline | findstr versionName`，以及系统「应用信息」里的版本号，三处必须一致。

## 版本号

- 唯一权威来源：`version.txt`（构建、打包、校验都从这里读，不手抄）。
- 形态：`MAJOR.MINOR.PATCH-alphaN`，N 从 1 递增；git tag 为 annotated `v<version>`；GitHub Release 标记 pre-release。
- `versionCode` 固定映射：
  - `1.0.0-alphaN` → `major*1000000 + minor*10000 + patch*100 + N`（例：alpha1 → `1000001`）
  - 稳定版 `1.0.0` → 同公式，尾段固定 99（保证稳定版永远大于任何 alpha）
- 自检：

```powershell
powershell -File version-check.ps1
```

校验 version.txt 格式、manifest 无重复版本字段、仓库内无第二处硬编码版本串、tag 为 annotated 且指向 HEAD、APK 的 `versionName`/`versionCode` 与 version.txt 一致。

## 图标与品牌资产

- 自适应图标：108×108 dp 画布，前景图形外接框 60×50 dp（画布宽 55.6%、高 46.3%），完全落在中央 72 dp 安全区，也落在 66 dp 圆形 keyline 内。
- 背景色 `#0D1526` 取自图标 SVG 自带的底色，写在 `values/colors.xml`。
- 主题图标：`drawable/ic_launcher_monochrome.xml`，只保留视镜轮廓（含底部凹口）与播放三角，丢弃光晕、拱形、机身浅蓝填充和两侧短划。
- 旧式 mipmap：mdpi 48 / hdpi 72 / xhdpi 96 / xxhdpi 144 / xxxhdpi 192，方形与圆形各一套；Play 商店 512×512 由 `.tmp` 同源 SVG 渲染，不进仓库。
- 通知小图标：`drawable/ic_stat_gazeline.xml`，24 dp 简化版，加粗视镜轮廓并放大播放三角；发布时尚未接入通知功能。

## 目录

```
app/src/main/AndroidManifest.xml
app/src/main/java/com/yunyu697/gazeline/
  MainActivity.java      启动页 / HUD 预览 / 选片入口 / 拉起校准页
  StageView.java         预览页、S5 手机端参数表单、选片与最近列表绘制
  CalibActivity.java     VR 内校准页：传感器朝向、凝视 tick、自测→微调、保存/回滚
  CalibView.java         校准页绘制：r=k 反解的测试卡、四组 −/＋、眼罩、动作与提示
  CalibSpec.java         校准页几何与目标表（角度唯一来源）
  CalibParams.java       校准参数：型号预设、范围钳制、SharedPreferences 存取
  PlayerActivity.java    播放器：朝向合成、凝视 tick、S4 菜单、退出确认、回中、唤回
  HudView.java           双视口 HUD（Canvas），S1–S4 全部层与确认框
  HudSpec.java           角度表、时序与颜色的唯一来源（含菜单/确认框几何）
  GazeController.java    凝视状态机：迟滞/去抖/预备/分档/冷却/锚点/分层目标集
  Feedback.java          凝视音效（ToneGenerator）
  VideoDecoder.java      MediaCodec 解码线程：暂停、跳转、循环
  AudioPlayer.java       音频轨解码 → AudioTrack：AudioTrack 播放头作主时钟、暂停/跳转/循环
  StereoRenderer.java    GL 双视口渲染与画面冻结
  Projection360.java     360° 管线
  ProjectionSbs.java     SBS 管线
app/src/main/res/...
build.ps1 / version-lib.ps1 / version-check.ps1 / version.txt
```
