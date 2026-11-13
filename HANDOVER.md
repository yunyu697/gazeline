# Gazeline 开发交接文档

> 生成时间：2026-11-13 · 当前版本：1.1.0-alpha18（已提交、已打 tag 发布）
> 仓库：https://github.com/yunyu697/gazeline.git（main 分支，annotated tag 发布）

---

## 1. 项目是什么

安卓 VR 视频播放器，品牌名 **Gazeline**（无中文名，禁止出现其他候选名）。双端：

- **手机端**：主页 / 片库（SMB2/3 + 本地）/ 设置，横屏优先，禁止简单拉伸。
- **VR 端**：GvrView 立体渲染，MediaCodec 自研解码管线，凝视交互（中央准星 + 单次触发）。

## 2. 技术栈与硬性约束（违反即返工）

| 项 | 规则 |
|---|---|
| VR SDK | Google VR SDK 1.200（GvrView + StereoRenderer）。头动/畸变/双目投影/回中/触发 **全部交给 SDK，禁止自研**；SDK 范围外才做原创 UI |
| 解码 | 保留自研 MediaCodec 管线（硬解优先 c2.qti.hevc.decoder，目标 4K60 HEVC 10bit），此部分优于成熟方案，**不要替换** |
| 版本规则 | version.txt 是唯一权威来源；格式 `1.1.0-alphaN`；versionCode = major*1000000+minor*10000+patch*100+N；发布用 annotated tag `v1.1.0-alphaN` + GitHub Release（pre-release）+ APK 与 SHA256SUMS |
| 签名 | 正式版不得沿用 debug keystore；release keystore 方案需先问用户 |
| VR 朝向 | 仅 0°/180° 两个离散状态，45° 倾斜滞回阈值；回中采用「触发后预留 3.5s 调整姿态再重锚」 |
| 交互 | 中央准星 + 单次触发；**已删除所有 dwell 自动确认** |
| HUD 锚定 | HUD/菜单/文件管理锚定在回中后的画面正前方（内容空间），不随唤出时头部位置移动 |
| SMB | 高码率视频需分块读取 + LRU 缓存 + 预读；多服务器配置持久化、可一键切回 |
| 完整规则清单 | 见本文末尾附录 A（从项目记忆原样搬运，共 30+ 条，**动手前必读**） |

## 3. 代码结构（关键文件）

```
gazeline/
├── build.ps1                  # 自写构建脚本（javac+d8+aapt2，无 Gradle）
├── version.txt                # 版本号唯一权威来源
├── app/src/main/
│   ├── AndroidManifest.xml
│   └── java/com/yunyu697/gazeline/
│       ├── PlayerActivity.java   # VR 播放核心（~2400 行）：GvrView、传感器、触发、菜单状态机、SMB 浏览、字幕
│       ├── GvrRenderer.java      # StereoRenderer：内容渲染 + HUD 投影 shader + 准星 + 遮罩
│       ├── GazeController.java   # 凝视射线 → 相对角 → focus 判定（线性度命中模型）
│       ├── HudView.java          # HUD 位图绘制（1024²），vrMode 与手机模式双轨
│       ├── HudSpec.java          # 全部 UI 几何常量（度）：菜单行高、面板尺寸、超时参数
│       ├── VideoDecoder.java     # MediaCodec 解码管线
│       ├── AudioPlayer.java
│       ├── SmbClient*.java       # SMB2/3 客户端（smbj 改造）
│       ├── StageView.java        # 手机端主页自绘 View（含横屏双栏 hist* 字段）
│       ├── LibraryActivity.java  # 片库（含横屏侧栏 navBar/applyOrientation）
│       ├── MainActivity.java     # 入口
│       ├── CalibActivity.java    # S5 校准（强制横屏）
│       └── PlayHistory.java / SubtitleTrack.java / ProjectionMode.java 等
└── build/                     # 构建产物 gazeline-<version>-<variant>.apk
```

## 4. 构建与验证（本机环境）

```powershell
# 构建（cwd = gazeline/）
powershell -ExecutionPolicy Bypass -File build.ps1 -Variant debug *> build_out.txt
# 产物：build\gazeline-1.1.0-alpha17-debug.apk

# 安装/启动（adb 在 C:\Users\Tyler Zhang\AppData\Local\Android\Sdk\platform-tools\）
adb install -r build\gazeline-1.1.0-alpha17-debug.apk
adb shell am start -n com.yunyu697.gazeline.debug/com.yunyu697.gazeline.PlayerActivity --es path /data/user/0/com.yunyu697.gazeline.debug/files/test360.mp4

# 日志与截图
adb logcat -d -s Gazeline
adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png .tmp\s.png
```

- 模拟器 AVD `gazeline`（system-images;android-35;google_apis;x86_64），**无陀螺仪**：屏幕拖拽模拟转头（10px=1°，moved≥48px 判为拖拽）。
- ⚠️ 教训：`adb devices` 挂起会拖死整个 Shell 会话，需 `taskkill /F /IM adb.exe` 恢复。
- 模拟器是横屏 2400×1080，`input tap` 坐标要用 (1200,540)，竖屏坐标会被丢弃。

## 5. 当前状态（最重要）

### 5.1 用户对 alpha17 真机反馈（本次接手的全部动机）

> 「凝视操作完全废了，播放也很卡顿。APP 的横屏界面就是个垃圾，之前就提醒不要简单拉伸。」

三个待修问题 + 一个发布任务，详见 5.3。

### 5.2 alpha18 的内容（已提交为 `fix(vr)` / `feat(ui)` 两个 commit）

`GvrRenderer.java` `HudView.java` `GazeController.java` `PlayerActivity.java` `StageView.java` `LibraryActivity.java`。改动内容：

1. **凝视修复（已基本完成，模拟器已验证）**：
   - 病根①：HUD 位图在眼缓冲里被当全屏贴图，转头时 HUD 与菜单不同步 → 改为 HUD 内容空间投影 shader（`K=0.00959931`，与 GazeController 线性命中模型严格对齐），准星改为眼空间 SDF 圆环每帧跟随真实注视射线。
   - 病根②：SDK 触发回调在 **手指按下瞬间** 就 fire（日志实锤：`touch DOWN` 与 `SDK trigger fired` 同毫秒），拖拽转头的按下会误触发。已改为按下时挂起（`pendingScreenTap`），UP 且未拖出 48px 才真正触发；无触摸的回调（Cardboard 物理按钮/磁铁）立即生效。
   - 附带：HUD 重绘节流 65ms（~15Hz）、signature() 去掉 relYaw/relPitch（转头不再触发重绘）、移除 `enableCardboardTriggerEmulation`（会把快速拖拽误判为 tap）。
   - 模拟器证据：菜单以内容空间投影正确打开（`.tmp\s5b.png` 双眼截图）、准星随拖拽正确移动、rel 坐标随 swipe 变化（日志 `rel=-0.25,26.3`）。
   - 临时诊断日志已在 alpha18 发布前删除（现已无任何 `Log` 调用）。
2. **卡顿缓解（部分验证）**：HUD 重绘节流已做；模拟器上屏间隔数据见 `.tmp` 中三组对比（抖动帧占比中位 19.8%→13.2%，但区间重叠，**不足以宣称提升**），真机帧率仍未验证。
3. **横屏真重排（模拟器已截图验证）**：StageView 主页横屏双栏（左=入口卡片+VR按钮，右=最近播放列表，`histLeft/histRight/histTop`）；LibraryActivity 横屏左侧 104dp 导航栏（`applyOrientation()`，onConfigurationChanged 触发）。截图：`.tmp\o2-home-landscape.png`、`.tmp\o3-lib-landscape.png`、`.tmp\o5-lib-list-landscape.png`（竖屏对照 `o1`/`o4`）。
4. ⚠️ 已知测试序列陷阱：启动 6s 后 HUD 自动淡出，第一次 tap 只 showHud() 不开菜单——脚本测试要先 tap 唤出 HUD。

### 5.3 接手者的 TODO（按序）

1. ~~删临时日志 → 重跑凝视全流程~~ 已完成（`.tmp\g2-menu.png` / `g3-aim.png` / `g4-select.png` / `g5-after.png`）。
2. ~~横屏截图验证 StageView 双栏 + LibraryActivity 侧栏~~ 已完成（`.tmp\o1`~`o5`）。
3. ~~卡顿量化~~ 已完成，结论见 5.2 第 2 条；**真机帧率仍是空白项**。
4. ~~version.txt → `1.1.0-alpha18`、构建 debug+release、提交、打 tag、发布~~ 已完成。
5. 每版发布后匿名核对（仓库公开可访问、Release 附件匿名可下载、哈希一致）——已固化为发布流程的最后一步。

下一版优先项：真机验证（凝视/回中/S5 校准注视失效/4K60 HEVC 10bit/72Hz）、SMB 实网串流。

## 6. 验证过的结论（不要重新怀疑）

- HUD 投影 shader 的 K 值与 HudSpec 的 pxPerDeg/width 推导一致，转头时菜单与内容同步移动、准星独立跟随注视（几何正确，模拟器弧线透视属正常）。
- 菜单自动关闭参数：离开面板 1200ms 关、30s 超时关、打开后 400ms 保护期；面板判定 |yaw|≤29° 且 |pitch|≤35°（HudSpec）。
- s4「菜单已关闭」异常已定位为触发路径问题，修复后未再复现。
- S5 校准页在 VR 环境下注视按钮无响应，是**已知未修 BUG**（project_memory 有记录）。

## 7. 交接方式说明

- 本文 + git 历史 = 全部上下文。提交本文后 `git log`、代码注释（英文、解释 why）都是一手资料。
- AI 会话记忆（Trae 本地 memory）不随机器迁移，**本文档已把其中对开发有影响的部分全部收录**（附录 A）。
- 真机测试需要用户配合（用户是产品决策者，交互规则改动必须先说明缘由征得同意，见附录 A 第 5 条）。

---

## 附录 A：项目硬约束与工程规约（来自项目记忆，原样保留）

### 硬约束
1. 品牌名仅为 Gazeline，无中文名。
2. 版本规则对齐 Renomio：`1.0.0-alphaN` 格式、annotated tag、Release 标 pre-release、version.txt 唯一权威、versionCode=major*1000000+minor*10000+patch*100+N（稳定版尾段 99）。
3. 每版附 APK+SHA256SUMS，发布后匿名核对。
4. 界面/功能严格遵循已确认方案，改已确认规则必须提前说明缘由。
5. 正式版不用 debug keystore，release keystore 方案提前确认。
6. 必须有文件历史 + 断点续播（应用端与 VR 端）。
7. VR 端文件管理与应用端设置一致，适配 VR 操作。
8. 应用端提供直接进入 VR 模式入口；VR 模式仅横屏。
9. 片库 P1/P2 按原设计图实现，不得简化。
10. 片库仅保留搜索/基础筛选/续播，封面渐变占位，元数据按文件大小和文件名推断。
11. VR 朝向仅 0°/180° 离散，45° 倾斜滞回。
12. 打开设置菜单必须完全隐藏 HUD；设置界面有返回按钮。
13. HUD/设置/文件管理锚定回中后画面正前方，回中不修改朝向和投影。
14. 手机端所有界面必须适配横屏，禁止简单拉伸变形/溢出/半屏空白。

### 工程规约
15. 硬解优先 c2.qti.hevc.decoder，目标 4K60 HEVC 10bit。
16. 360 与 SBS 两条独立渲染管线，参数不共用。
17. 凝视参数/回中/校准严格遵循已确认规格，不得随意调整。
18. 回中：触发后预留调整姿态时间，再基于当前姿态执行。
19. SMB 配置持久化、自定义名称保存、一键切回。
20. 文件列表不用弹窗，直接集成在界面，带收藏。
21. SMB 高码率：分块读取+LRU+预读。
22. VR 默认视角中心 = 手机垂直放置正前方，上下以实际放置方向为准。
23. VR 内上下方向切换 45° 滞回阈值。
24. 唤回：低头手势（默认 40°，三档）+ 临时边缘进度条，收起后无常驻遮挡，首次收起提示一次。
25. VR 菜单两页：P1 播放/画面，P2 系统（音量、服务器、字幕样式）。
26. S5 校准：进入立即按当前朝向重锚，注视确认 1.2s，无头部事件 1.2s 退回拖动瞄准。
27. 服务器界面按协议分段，WebDAV/HTTP 未支持给提示，测试展示真实协商协议/延迟/目录统计。
28. 视频中心 = 每只眼合成后画面中心（SBS/上下按单眼画幅算）。
29. 排布自动识别：首帧低性能自检，左右半一致判 SBS，上下半一致判 OU。
30. HUD 提供文件管理直接入口。
31. 唤回支持凝视唤回 + 双击手机背面（加速度计尖峰对）。
32. 进度条拖动：凝视 1.2s 进入拖动，拖动期间暂停播放，视线离开/其他操作结束并跳转，不依赖凝视焦点。
33. 开孔安全区：VR 播放页 SHORT_EDGES 由 HUD 自避让；手机端主页/片库 fitToCutout。
34. HUD 内置视频类型快速切换按钮，用户选择持久化，自动识别为默认。
35. 头部跟踪加死区+低通滤波，保证 HUD 稳定不抖。
36. 头部姿态/畸变/双目投影/回中/触发用官方 VR SDK，禁止自研。
37. 保留自研 MediaCodec 解码管线。
38. 交互 = 中央准星 + 单次触发，无 dwell。

### 教训（前人踩坑）
39. 版本号多位置分散必然不一致 → 单一权威 + 自检。
40. SMB 逐次读请求 = 请求风暴 → 分块+缓存+预读。
41. 传感器旋转矩阵未做屏幕坐标系适配 → 水平转头出现垂直旋转。
42. 错读旋转矩阵元素 → 画面 180° 误翻转。
43. 反复加自动修正规则修 HUD 飘移引入新副作用 → 改固定锚点+手动开关的确定性方案。
44. 自研成熟 VR 基础组件 = 大量实机测试成本且效果差 → 用官方 SDK，只在 SDK 外做原创。
45. 未对齐核心定义就动手 = 无效返工 → 先确认技术逻辑一致再改代码。
