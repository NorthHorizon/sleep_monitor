# 首次整夜试用与运行诊断

## 今晚开始前

1. 在工作电脑重新构建 ARM64 APK，覆盖安装；这次含 Kotlin 和 FileProvider 改动，需要完整安装。
2. 先用录 15 秒、等待 10 秒、约 3 分钟后自动结束，熄屏验证两轮以上。确认声音、画面、到时停止和“设置 → 导出运行日志”都正常。
3. 改回今晚参数并保存，例如录 300 秒、录完等待 900 秒、07:00 自动结束。首页应显示正确的今天／明天结束日期。
4. 手机按今晚实际位置固定，保留小夜灯。确认 MIUI 后台电量管理允许应用运行，再手动开始监控并熄屏。电脑可以关机，日志不依赖 ADB。
5. 记录一下开始电量、是否接电以及大致开始时刻，便于明天与日志对照。充电状态会被采样，但不能把充电中的电量变化直接当作耗电率。

默认参数下，8 小时约录 24 段，总视频时长约 2 小时；相机启动、保存和结束时刻会影响实际数量。用已经录出的一个 5 分钟片段大小乘以约 24，可以估计今晚需要的存储空间。

## 明早检查

1. 打开应用，检查是“已到自动结束时间”还是出现中断／错误提示。
2. 在片段页检查首段、末段与几段中间片段的音画；日志里的保存成功表示音视频轨和索引已通过现有检查，不代替实际回放。
3. 在监控停止后进入“设置 → 导出运行日志”，使用系统分享把 ZIP 保存或传到电脑。未正常停止时先手动结束；重开应用的中断提示也会记录到日志。
4. 使用 Python 3.9+，在项目根目录执行（替换 ZIP 路径）：

   ```powershell
   python tools/summarize_diagnostics.py "C:\path\sleep-diagnostics-xxx.zip" -o night-report.md
   ```

脚本只使用标准库，离线读取 ZIP 或单个 JSONL，不修改视频或原日志。报告包含成功片段数、视频总时长／大小、启动与保存耗时、电量首末采样、电池温度采样最高值、剩余空间采样最低值、调度空档及等待超时。时间统一输出 UTC，香港／北京时间加 8 小时。

## 日志记录什么

- `session_start`：本次配置、固定结束时刻、设备型号、Android／应用版本、调试标记、时区。
- `clip_prepare`、`clip_started`、`clip_stop_requested`、`clip_saved`：片段 ID、时间、启动耗时、保存耗时、视频时长与文件大小。
- `waiting`：文件保存后开始等待及等待秒数。
- `heartbeat`：每 5 分钟一次，携带当前阶段和已完成片段数。
- 设备状态：开始、保存、停止和心跳时采样电量、电池温度、接电状态、可用空间、设备是否处于交互状态、省电／空闲状态及系统电量优化白名单状态。温度来自电池传感器，不代表 CPU 温度；白名单状态不代表 MIUI 的所有后台策略。
- `scheduler_gap`：服务两次计时回调间隔超过 15 秒，记录实际间隔；不自动判定原因。
- `stop_requested`、`session_end`：区分手动停止、到时结束和错误；保存超时等错误也会记录。
- `service_destroyed`：服务销毁回调发生时会话尚未正常结束。
- `previous_session_interrupted`：重新打开时发现上次活动标记未清除。此事件时间是发现时间，无法据此推断确切的中断时间。

每条 JSONL 同时带 Unix 毫秒时间、UTC 字符串和 `elapsedRealtimeMs`。持续时长使用单调时钟，事件顺序使用 ZIP 内的旧到新顺序，不按可能被调整的手机时钟重排。

原始日志位于应用内部 `files/diagnostics/`，单文件最多 256 KiB，保留当前文件及 3 个轮换文件，总计最多约 1 MiB。超过后覆盖最旧日志，不承诺固定保留多少晚；每次导出生成独立的 ZIP 快照，放在应用缓存目录。日志写入失败只写 Logcat，继续录制。

日志不包含原始音视频、设备序列号，也不会自动上传。导出 ZIP 可能包含多次会话，只有用户主动使用系统分享时才交给所选应用。应用进程被强杀或手机断电时可能来不及写最后一条；缺少结束事件只代表缺少证据，不应直接显示为正常完成或认定为系统杀进程。

## 工作电脑的回归检查

```powershell
flutter analyze
flutter test
flutter build apk --debug --target-platform android-arm64
cd android
.\gradlew.bat :app:testDebugUnitTest
cd ..
python -m unittest discover -s tools -p "test_*.py"
```

新增用例覆盖日志按字节轮换、旧日志淘汰、导出快照稳定性、空日志导出、损坏尾行、时钟回拨、缺少会话开始／结束及导出失败后的界面状态。当前电脑按约定只做静态检查；这些测试和安卓构建须在工作电脑执行。

平台 API 参考：[BatteryManager](https://developer.android.com/reference/android/os/BatteryManager)、[PowerManager](https://developer.android.com/reference/android/os/PowerManager)、[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)。
