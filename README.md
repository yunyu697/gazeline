# Gazeline

Android 手机 + Cardboard 卡纸盒的 VR 视频播放器。核心交互是**凝视 + 停留**：戴上之后不摘机、不用手柄，靠转头把目标移到视点中心完成操作。内容规划支持 360° 全景与 SBS 3D，片源走本地文件或 SMB 局域网串流。

> 当前是 POC 阶段，发布物为当前 alpha 版（版本号以 `version.txt` 为准）。

## 当前完成度

这一版**只有静态界面骨架**，能安装、能启动、能在手机上看到已经定稿的视觉形态：

| 已实现 | 说明 |
|---|---|
| 启动页 | `GAZELINE` 字标 + 副标题，无中文品牌名 |
| 外层 HUD（静态） | 播放 Ø10°(0,-18)、±10 s Ø8°(±16,-18)、字幕(-30,-18)、菜单(+30,-18)、回中 Ø7.5°(-37,-25)、字幕 @-33、唤醒条 25°×3° @-39 |
| HUD 收起态 | 只剩唤醒条与 1 px 进度线，注视点随 HUD 淡出 |
| S5 校准页（静态） | 10°/5° 网格、四角十字、边缘圆环、中心十字、双眼对齐线、可读性样本、单眼遮盖测试、屈光/参数诊断分叉、四组步进器外观 |

**未实现（不要在演示中当成可用功能）：**

- 视频解码与播放（没有接入 MediaCodec，硬解基线 `c2.qti.hevc.decoder` 尚未接线）
- 双眼渲染与镜头预畸变
- 360 与 SBS 两条管线
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
