# Gazeline

Android 手机 + Cardboard 卡纸盒的 VR 视频播放器。核心交互是**凝视 + 停留**：戴上之后不摘机、不用手柄，靠转头把目标移到视点中心完成操作。内容规划支持 360° 全景与 SBS 3D，片源走本地文件或 SMB 局域网串流。

**品牌决定：品牌名仅 `Gazeline`，无中文名。** launcher label、启动页、关于页、商店标题只出现 `Gazeline`。商店标题为 `Gazeline · Cardboard VR Player`，品类词只放副标题与描述。

已知限制：launcher label 是 8 个拉丁字母，默认 4 列图标网格可完整显示；大字体或 5 列网格下可能被省略号截断。不通过缩小字号或改名规避。

> 当前是 POC 阶段，发布物为当前 alpha 版（版本号以 `version.txt` 为准）。

## 当前完成度

这一版把**凝视层**接入本地播放（里程碑 3）：S1–S3 全部控件、回中键、唤醒条、暂停/进度/±10 s 都由视线触发，不再需要点屏幕：

| 已实现 | 说明 |
|---|---|
| 启动页 | 视镜标记 + `GAZELINE` 字标 + 副标题，无中文品牌名 |
| 外层 HUD（S1–S3） | 播放 Ø10°(0,-18)、±10 s Ø8°(±16,-18)、字幕(-30,-18)、菜单(+30,-18)、回中 Ø7.5°(-37,-25)、字幕带 @-33、时间轴 @-11、顶部状态 @+39 |
| 凝视命中 | 进入 0.85R、离开 1.15R，120 ms 去抖；聚焦 150 ms + 预备 250 ms 后才开始充能 |
| 停留分档 | L1 1.2 s（播放/±10 s）、L2 1.8 s（字幕/菜单/回中）、L3 2.5 s（S4 退出，alpha5）；环显示剩余等待 |
| 防连发 | 动作后 500 ms 冷却，且同一目标必须移出离开半径再回才可再次充能 |
| 取消 | 移出目标 250 ms、角速度 >25°/s、返回键（物理/线控）立即取消；>10°/s 暂停充能，<6°/s 恢复 |
| 回中键 | 1.2 s 充能 + 0.6 s 待发两段式；110 ms 遮罩 → 冻结画面 → 限速 180°/s（单帧 ≤2.5°）旋转 → 120 ms 淡出；1000 ms 冷却 |
| 唤回 | 播放中闲置 5 s 开始淡出、6 s 只剩唤醒条 25°×3° @-39°；注视 0.8 s 唤回；暂停时永不隐藏（40% 亮度）；新视频前 15 s 常显 |
| 本地视频播放（M2） | 点播放键 → 选 360 / SBS → 系统文件选择器选视频；MediaCodec 解码到 SurfaceTexture |
| 解码器选择 | HEVC 优先 `c2.qti.hevc.decoder`，其次任意 qti、任意硬件、最后平台默认；日志记录实际 codec 名与 `isHardwareAccelerated` |
| 360 管线 | 独立 `Projection360`：水平 FOV 90°、k1 -0.20、k2 0.032、IPD 0°，等距柱状采样 + 镜头预畸变 |
| SBS 管线 | 独立 `ProjectionSbs`：垂直 FOV 85°（水平约 90°）、k1 -0.26、k2 0.045、虚拟屏宽 70°，左右半幅各取一半 + 预畸变 |
| 播放控制 | 播放/暂停、±10 s 跳转（`positionUs` 与时间轴同步）；字幕键切换开关并提示“暂无字幕轨” |
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

**未实现（不要在演示中当成可用功能）：**

- 10-bit HEVC 真机硬解（模拟器已证实不可用，真机待验）
- 音轨（当前只解码视频轨，无声音）
- 字幕渲染（字幕键只切换状态并提示“暂无字幕轨”）
- S4 菜单与 L3 退出二次确认（菜单键当前提示“菜单将在 alpha5 开放”）
- 校准步进器的实际调节与即时生效
- SMB 串流、关于页
- 72 Hz 刷新、长时间耐力测试

启动页与 S5 校准预览页的切换仍靠点屏幕；**进入播放器后 HUD 全部走凝视**。所有角度按 90° 单眼 FOV 定义，像素值随设备换算。

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

- `gazeline-<version>-release.apk`（包名 `com.yunyu697.gazeline`）
- `gazeline-<version>-debug.apk`（包名 `com.yunyu697.gazeline.debug`）
- `SHA256SUMS`

alpha 阶段两个 APK 都用 `debug.keystore` 签名（密码为 Android 调试约定值），因此**不适合上架应用商店**；正式发布前会换成独立 release key。

## 安装

1. 手机允许安装未知来源应用（或开启 USB 调试）。
2. `adb install -r build/gazeline-<version>-release.apk`，或直接把 APK 传到手机点安装。
3. 启动后 1.7 s 自动从字标进入 HUD 预览；底部三个按钮切换页面。
4. 点播放键 → 选 360 / SBS → 系统文件选择器选片，进入播放器。播放器内：有 Rotation Vector 时转头控制视线，没有时拖拽模拟；把控件移到视点中心并停留即可充能，移开或按返回键取消。

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
  MainActivity.java      启动页 / HUD 预览 / S5 校准预览 / 选片入口
  StageView.java         预览页与校准页的骨架绘制
  PlayerActivity.java    播放器：朝向合成、凝视 tick、回中动画、唤回
  HudView.java           双视口 HUD（Canvas），角度即像素
  HudSpec.java           角度表、时序与颜色的唯一来源
  GazeController.java    凝视状态机：迟滞/去抖/预备/分档/冷却/锚点
  Feedback.java          凝视音效（ToneGenerator）
  VideoDecoder.java      MediaCodec 解码线程：暂停、跳转、循环
  StereoRenderer.java    GL 双视口渲染与画面冻结
  Projection360.java     360° 管线
  ProjectionSbs.java     SBS 管线
app/src/main/res/...
build.ps1 / version-lib.ps1 / version-check.ps1 / version.txt
```
