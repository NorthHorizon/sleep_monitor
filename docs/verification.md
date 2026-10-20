# 当前验证记录

日期：2026-10-15。

按用户要求，本机仅执行静态检查；不运行安卓编译、Flutter 测试或真机测试。

已完成：

- `dart format lib test`：格式化 Dart 源码及测试代码。
- `dart analyze`：`No issues found!`。
- AndroidManifest、FileProvider 路径、通知图标 XML：解析成功。
- 静态核对 Flutter / Kotlin 的命令通道、事件通道、配置字段和单位。
- 静态检查关键流程：重复开始、配置锁定、录完计间隔、指定时刻到期、停止时完成封装、超时及资源清理。
- `git diff --check`：无空白错误。

已编写但未执行：Flutter 控制器/界面测试、结束时间日期预览测试、Kotlin 调度与本地结束时刻测试。新增用例覆盖今天/明天、同一时刻、跨年、夏令时、界面跨分钟刷新、固定会话结束日期与旧设置迁移。静态分析不证明这些测试已通过，也不证明原生代码能编译。

工作电脑需验证：

1. `flutter doctor -v`、`flutter pub get`、`flutter analyze`、`flutter test`。
2. `flutter build apk --debug`，然后在 android 目录执行 `gradlew.bat :app:testDebugUnitTest`。
3. 连接手机，用 15 秒录像 / 10 秒等待 / 结束时间约在 2–3 分钟后检查完整链路。
4. 分别检查权限拒绝、相机占用、录像中停止、等待中停止、指定时刻到期、系统播放和分享。另测跨午夜和授权弹窗期间越过结束时刻，不应继续到下一天。
5. 熄屏、MIUI 后台电量管理以及整晚运行的温度、耗电、文件完整性。

鼾声模式当前不可启动，须接入并验证真实本地检测器后再做触发与冷却验收。
