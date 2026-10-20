# 项目架构与行为约定

## 当前范围

设备：红米 K30S 至尊纪念版 / MIUI 12；具体 Android 版本待真机确认。单人睡眠，小夜灯，固定支架距头部约 0.3–0.5 米。App 离线运行，文件之后导出到电脑，不依赖服务器。

当前代码包含定时录制链路、配置持久化、服务状态同步、片段索引及系统播放/分享。相机、熄屏运行与录制效果尚需真机验证。鼾声检测只有接口，未集成模型；界面和原生服务均阻止启动鼾声模式。没有使用音量阈值或模拟事件冒充鼾声识别。

## 分层

```text
Flutter 界面
  └─ MonitorController：应用状态、能力检查、配置保存、操作错误
      └─ MonitorGateway：平台边界
          ├─ AndroidMonitorGateway：MethodChannel + EventChannel
          └─ UnsupportedMonitorGateway：其他平台只显示界面，禁止采集

Android
  ├─ MonitorBridge：权限、命令、事件订阅、系统播放/分享
  ├─ MonitorService：前台服务、会话状态机、通知、唤醒锁
  │   ├─ SessionSchedule：单调片段计时及固定结束时刻
  │   └─ ClipRecorder → CameraXClipRecorder：带音轨的 MP4
  ├─ MonitorRepository：设置、AtomicFile 片段索引、文件定位
  ├─ MonitorRuntime：进程内服务快照及界面订阅
  ├─ EndTimeResolver：本地结束时刻 → 下一次实际日期时间
  └─ SnoreDetector：预留的本地声音分类接口
```

Flutter 负责界面，服务负责采集与计时。退出 Flutter 页面不等于结束监控；用户通过应用按钮或通知栏结束。跨平台通道只传递配置、状态与片段索引，不传原始视频或整段 PCM。

## 触发与计时

每次会话只能选择一种模式。配置在开始时固定，监控期间不能修改。默认录 300 秒、录完等待 900 秒、自动结束时刻 07:00，使用后置摄像头。用户点击开始后立即监控，无预约自动开始功能。

设置页使用 24 小时时间选择器；设置页和监控页显示今天或明天几点结束，并预估现在开始可监控多久。界面每分钟和恢复前台时刷新日期预览。服务启动后使用原生返回的固定 `endsAt`，不随预览逻辑重新滚到第二天。启动相机与完成文件阶段仅显示状态文字。

定时模式暂按“首次立即录制，之后录完再等待”实现，这一间隔定义仍可按用户反馈调整：

```text
idle → preparing → recording → finalizing → waiting → preparing → …
                                       ↘ 停止 / 到自动结束时刻 → idle
任意采集或存储错误 → 保存可完成的当前片段 → error
```

- 单段时长从 CameraX 的实际开始事件计算，初始化耗时不占用录制时长。
- 等待从文件封装且索引保存完成后开始，不从上一段开头计算。
- 单段录制、等待和超时使用 `SystemClock.elapsedRealtime()`；监控结束使用开始时固定的 Unix 毫秒时间。调整系统时钟不会改变片段/等待长度，但会改变距离目标结束时刻的实际等待时间。会话中改变时区不重新选择结束日期。
- 正在录制时用户停止，会先结束并封装当前片段；等待时停止不会额外录一段。
- 到自动结束时刻即停止采集并保存当前片段，即使尚未达到原定片长。原生结束回调独立于每秒状态更新；每秒计时另作到期检查。
- 结束日期在用户请求开始时、显示权限弹窗前确定；如果授权结束后目标时间已经过去，启动失败并提示，不延长到下一天。
- 相机准备和文件封装有超时退出，失败不自动高频重试。
- 进程被杀后不自动重启相机；再次打开时提示上次会话中断。
- 前台服务与限时唤醒锁是实现策略，MIUI 的整晚稳定性需要实测。

未来鼾声模式：

```text
listening → 检出鼾声 → preparing → recording → finalizing → cooldown → listening
```

录制及冷却期间暂停检测；冷却期的事件不排队，到期后必须使用新窗口。监听用 AudioRecord，触发后释放监听采集器，再交给 CameraX 录像。首版不回补触发前视频；若之后要求回补，需要增加持续视频缓存并重新评估功耗。

## 平台协议

命令通道 `com.nightowl.sleep_monitor/commands`：

| 方法 | 参数 | 结果 |
| --- | --- | --- |
| `bootstrap` | 无 | settings、snapshot、capabilities、recordings |
| `saveSettings` | settings map | 成功或平台错误 |
| `startSession` | 无，读取已保存设置 | 请求已提交；实际状态以事件为准 |
| `stopSession` | 无 | 停止请求已提交；以最终事件为准 |
| `listRecordings` | 无 | 已完成片段，按开始时间倒序 |
| `openRecording` | `{id}` | 调用系统播放器 |
| `shareRecording` | `{id}` | 调用系统分享面板 |

事件通道 `com.nightowl.sleep_monitor/events`：订阅时立即发送服务快照，运行期间约每秒更新。

settings：`mode: timed|snore`、`recordingSeconds: 1..300`、`intervalSeconds: 1..86400`、`endHour: 0..23`、`endMinute: 0..59`、`camera: front|back`。缺少结束时刻的旧设置采用默认 07:00，不再使用旧 `sessionSeconds`。

snapshot：`phase`、`sessionId`、`startedAt`、`endsAt`（时间均为 Unix 毫秒，可空）、`remainingSeconds`、`completedClips`、`message`。运行时 `endsAt` 在本次会话中不变。phase 同时在 Dart 与 Kotlin 维护；更改协议时同步修改两侧。

startSession 返回成功仅表示已请求启动服务。权限、硬件初始化或后续采集失败会通过错误或 error 快照呈现，不把请求已提交显示为已完成录像。

## 文件与持久化

设置使用原生 SharedPreferences；片段索引保存在应用内部的 `recordings.json`，通过 AtomicFile 更新。索引包含配置快照、会话 ID、开始时间、实际时长、大小和文件路径；新片段的快照还包含本次 `endsAt`。历史片段的原始配置快照保留，不将旧时长配置伪改成新结束时刻。相机失败的片段不作为成功记录展示。

视频写入应用专属外部目录的 `Movies/clips/`，不可用时回退到内部 `clips/`。播放和分享通过 FileProvider 临时授予文件读取权限，不暴露整个应用目录。需要导出的视频可从片段页分享。卸载应用会删除视频，当前无自动上传或自动删除旧片段。

录像以 CameraX HD 画质为起点，有硬件兼容回退。当前不承诺固定帧率或码率，应以真机生成文件为准。编码完成后检查文件包含视频轨与音轨；“存在音轨”不保证每段声音质量良好，需实际回听。开始片段前低于 256 MB 可用空间时停止，已有文件保留。

进程被强制终止时，正在写的 MP4 可能不完整或未加入索引；当前没有自动修复或孤立文件恢复机制。不要把这套基础实现作为整晚不丢片段的保证。

## 扩展顺序

1. 工作电脑编译与真机验证：短参数多轮录制、熄屏、停止、相机占用、权限拒绝、空间不足、指定时刻自动结束、分享。
2. 接入本地鼾声模型与 AudioRecord；只在模型加载、输入预处理和类别映射就绪后公布检测能力。
3. 用实际卧室样本校验灵敏度和触发延迟，新增检测窗口投票、冷却测试。
4. 再增加电脑导入与离线分析。心率、血氧、呼吸率和睡眠分期不在当前范围。

参考：[CameraX 录制](https://developer.android.com/media/camera/camerax/video-capture)、[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[Flutter 平台通道](https://docs.flutter.dev/platform-integration/platform-channels)、[YAMNet 模型](https://github.com/tensorflow/models/blob/master/research/audioset/yamnet/README.md)。
