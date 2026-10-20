# 夜间记录 · Sleep Monitor

用于闲置安卓手机的离线睡眠音视频记录工具。Flutter 提供界面，Kotlin 前台服务负责录制、计时与本地存储。当前基础版本以红米 K30S 至尊纪念版为目标设备。

## 当前代码

- 监控、片段、设置三个页面；中文深色界面。
- 设置持久化：模式、单段时长、录完后的等待/冷却时间、自动结束时刻、前后摄像头。
- 定时录制链路：首次立即录像，文件保存完成后等待，再录像；输出带音轨的 MP4。
- 原生服务状态同步、录制/等待倒计时、通知栏停止，以及到指定时间自动结束。
- 设置页与监控页显示“今天 / 明天 HH:mm 结束”；启动相机、完成文件阶段不显示倒计时。
- 本地片段索引、调用系统播放器和系统分享导出。
- 鼾声检测接口和能力开关；**尚未接入模型，鼾声模式不可启动**。

这是基础实现，安卓编译、真机录制和整晚熄屏稳定性需要在有工具链的机器上验证。不会在其他平台生成模拟录像或虚假的鼾声事件。

## 开发环境

- Flutter 3.38.5 / Dart 3.10.4 或更新的兼容稳定版。
- 安卓最低 API 26（Android 8.0）。目标 K30S 为 Android 11 / MIUI 12.1.2，CPU 架构为 arm64-v8a。
- 工作电脑安装 Flutter 所需 Android SDK，以及兼容当前 Gradle / AGP 的 JDK 17 或更新支持版本。先用 `flutter doctor -v` 检查。

```powershell
flutter pub get
flutter analyze
flutter test

# 工作电脑：先构建 APK（Flutter 会准备 Gradle wrapper），再运行原生测试
flutter build apk --debug
cd android
.\gradlew.bat :app:testDebugUnitTest
cd ..
flutter run
```

监控需要相机和麦克风权限。Android 13+ 会请求通知权限，通知栏提供停止入口。网络与电脑都不是录制前提。MIUI 下需检查该应用的后台电量管理，并用短参数验证熄屏行为。

## 打包到红米真机

在有 Android 工具链的工作电脑、项目根目录执行：

```powershell
flutter pub get
flutter build apk --debug --target-platform android-arm64
adb -d install -r .\build\app\outputs\flutter-apk\app-debug.apk
```

`-d` 指定 USB 真机，`-r` 覆盖安装并保留应用数据。ADB 不在 PATH 时，使用 Android SDK 下的 `platform-tools/adb.exe` 完整路径。也可以把此 APK 传到手机后安装。

如果同一个 APK 需要同时用于 ARM 真机和 x86_64 模拟器，改用：

```powershell
flutter build apk --debug --target-platform android-arm,android-arm64,android-x64
```

直接复制运行模拟器时生成的 `app-debug.apk`，可能只包含模拟器架构。已在 K30S 抓到的启动异常为 `MissingLibraryException: Could not find 'libflutter.so' ... only found: [x86_64]`；这种情况需要重新打包含 ARM Flutter 库的 APK。可用 Android Studio 的 **Analyze APK** 检查是否有 `lib/arm64-v8a/libflutter.so`。无需因此调整 Android 最低版本、相机权限或渲染器。

复现闪退后，可以用 ADB 导出崩溃日志；即使应用已经退出，也能读取尚在缓冲区中的记录：

```powershell
adb devices -l
adb -d logcat -b crash -d -v threadtime | Out-File -Encoding utf8 .\sleep-crash.log
```

连接状态为 `unauthorized` 时，先解锁手机并允许 USB 调试。以上打包和安装命令需在工作电脑执行；此电脑仅抓日志和做静态检查。

## 先在真机验证

将录制设为 15 秒、等待设为 10 秒，自动结束时间选为当前时间之后约 2–3 分钟：开始后应立即录第一段，录完且保存后等待 10 秒，再录下一段。到指定时刻会停止并保存当前片段，等待中则直接结束。检查每段音画、播放、系统分享以及等待期间手动停止。之后再进行熄屏与整晚验证。

默认是每段 5 分钟、录完等 15 分钟、自动结束时刻 07:00。点击开始后立即监控，结束时刻未到则在今天结束，已经到达或经过则显示明天；开始前明确显示实际结束时间及预计监控时长。开始时会固定本次结束日期，即使权限弹窗耗时，也不会悄悄延长到下一天。录完再计间隔的规则不变。

从旧版本升级时，原来的“最长监控时间”改为默认 07:00，其他录制设置和历史视频保留。请在开始前查看或修改自动结束时间。此版本修改了 Kotlin 代码，需完整重新编译安装，热重载不足以更新原生服务。

视频保存在应用专属目录，卸载应用会一并删除。需保留的片段请先使用“导出 / 分享”。当前不自动删除旧文件，不自动恢复进程被杀时的半成品视频。

## 目录

```text
lib/src/
  domain/          配置、能力、服务快照、片段模型
  application/     控制器与操作状态
  platform/        安卓通道和非安卓回退
  ui/              监控、片段、设置页面
android/app/src/main/kotlin/com/nightowl/sleep_monitor/monitor/
  MonitorBridge.kt       Flutter 通道、权限、播放/分享
  MonitorService.kt      会话状态机与前台服务
  SessionSchedule.kt     片段/等待计时及本次固定结束时间
  EndTimeResolver.kt     将本地时刻解析为今天或明天的结束日期
  CameraXClipRecorder.kt  音视频录制适配器
  MonitorRepository.kt   配置与片段持久化
  SnoreDetector.kt       本地鼾声检测扩展点
test/                    Flutter 控制器与界面测试
android/app/src/test/    原生计时边界测试
docs/architecture.md    触发约定、平台协议、存储、实现边界
```

技术方案和后续扩展详见 [架构说明](docs/architecture.md)。
