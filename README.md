# Gazeline

Android 手机 + Cardboard 卡纸盒的 VR 视频播放器。核心交互是**凝视 + 停留**：戴上之后不摘机、不用手柄，靠转头把目标移到视点中心完成操作。内容规划支持 360° 全景与 SBS 3D，片源走本地文件或 SMB 局域网串流。

**品牌决定：品牌名仅 `Gazeline`，无中文名。** launcher label、启动页、关于页、商店标题只出现 `Gazeline`。商店标题为 `Gazeline · Cardboard VR Player`，品类词只放副标题与描述。

已知限制：launcher label 是 8 个拉丁字母，默认 4 列图标网格可完整显示；大字体或 5 列网格下可能被省略号截断。不通过缩小字号或改名规避。

> 当前是 POC 阶段，发布物为当前 alpha 版（版本号以 `version.txt` 为准）。

## 当前完成度

这一版已接入**本地文件解码与双目渲染**（里程碑 2），界面仍是骨架，凝视逻辑尚未实现：

| 已实现 | 说明 |
|---|---|
| 启动页 | 视镜标记 + `GAZELINE` 字标 + 副标题，无中文品牌名 |
| 外层 HUD（静态） | 播放 Ø10°(0,-18)、±10 s Ø8°(±16,-18)、字幕(-30,-18)、菜单(+30,-18)、回中 Ø7.5°(-37,-25)、字幕 @-33、唤醒条 25°×3° @-39 |
| HUD 收起态 | 只剩唤醒条与 1 px 进度线，注视点随 HUD 淡出 |
| S5 校准页（静态） | 10°/5° 网格、四角十字、边缘圆环、中心十字、双眼对齐线、可读性样本、单眼遮盖测试、屈光/参数诊断分叉、四组步进器外观 |
| 本地视频播放（M2） | 点播放键 → 选 360 / SBS → 系统文件选择器选视频；MediaCodec 解码到 SurfaceTexture |
| 解码器选择 | HEVC 优先 `c2.qti.hevc.decoder`，其次任意 qti、任意硬件、最后平台默认；日志记录实际 codec 名与 `isHardwareAccelerated` |
| 360 管线 | 独立 `Projection360`：FOV 90°、k1 -0.20、k2 0.032、IPD 0°，等距柱状采样 + 镜头预畸变 |
| SBS 管线 | 独立 `ProjectionSbs`：FOV 85°、k1 -0.26、k2 0.045、虚拟屏宽 70°，左右半幅各取一半 + 预畸变 |
| 播放期交互 | 拖动转头、双击回中；有 Rotation Vector 时叠加真实头动，无传感器时可用拖动 |
| 调试 HUD | 文件名 / 模式 / codec / 硬解 / 分辨率 / 解码帧 / 迟到帧 / GL 帧 / 解码与绘制帧率 / 镜头参数 |

**里程碑 2 实测（模拟器，2026-11-08）**

- 模拟器 `sdk_gphone64_x86_64`（ranchu），没有 Qualcomm 解码器；实际选中 `c2.goldfish.hevc.decoder`，`isHardwareAccelerated=true`（goldfish 的虚拟硬解，**不代表真机 qti 读数**）。
- 8-bit HEVC Main 1280×640（360）与 1280×720（SBS）：解码 29.8–29.9 fps、GL 绘制 59.5–59.9 fps，迟到帧 0。
- **10-bit HEVC（Main10）在模拟器上失败**：`GoldfishHevcHelper: error in decodeHeader: 0x43dd`，codec 直接进入 Released，App 报 `IllegalStateException` 并停播。真机 qti 是否支持 10-bit 尚未验证。
- 360 拖动后视野变化、双击回中后回到中心列，均已截图确认。

**未实现（不要在演示中当成可用功能）：**

- 10-bit HEVC 真机硬解（模拟器已证实不可用，真机待验）
- 音轨（当前只解码视频轨，无声音）
- 播放控制（暂停 / 进度 / 快进 10 s / 字幕 / 菜单按钮仍是静态 HUD）
- 凝视计时逻辑（命中迟滞、停留分档、角速度门控、误触冷却全部未实现）
- 校准步进器的实际调节与即时生效
- SMB 串流
- 关于页


当前页面切换靠**点屏幕**（HUD 预览 / S5 校准 / 收起 HUD），不是凝视触发。所有角度按 90° 单眼 FOV 定义，像素值随设备换算。

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
app/src/main/java/com/yunyu697/gazeline/MainActivity.java
app/src/main/java/com/yunyu697/gazeline/StageView.java
app/src/main/res/...
build.ps1
version-lib.ps1
version-check.ps1
version.txt
```
