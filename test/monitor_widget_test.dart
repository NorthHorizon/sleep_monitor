import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sleep_monitor/src/app.dart';
import 'package:sleep_monitor/src/domain/monitor_models.dart';
import 'package:sleep_monitor/src/platform/monitor_gateway.dart';

import 'support/fake_monitor_gateway.dart';

void main() {
  testWidgets('the plan shows tomorrow morning instead of duration input', (
    tester,
  ) async {
    final gateway = FakeMonitorGateway();
    await tester.pumpWidget(
      SleepMonitorApp(gateway: gateway, now: () => DateTime(2026, 10, 15, 23)),
    );
    await tester.pumpAndSettle();
    expect(find.text('现在开始，将于明天 07:00自动结束'), findsOneWidget);
    expect(find.text('预计监控 8 小时'), findsOneWidget);
    await tester.tap(find.text('设置'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('pick-end-time')), findsOneWidget);
    expect(find.textContaining('最长监控时间'), findsNothing);
    await tester.pumpWidget(const SizedBox());
    await gateway.events.close();
  });

  testWidgets(
    'an active session displays the native end time, not a new plan',
    (tester) async {
      final gateway = FakeMonitorGateway()
        ..snapshot = MonitorSnapshot(
          phase: MonitorPhase.finalizing,
          endsAt: DateTime(2026, 10, 16, 7),
        );
      await tester.pumpWidget(
        SleepMonitorApp(
          gateway: gateway,
          now: () => DateTime(2026, 10, 16, 7, 1),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('本次监控将于今天 07:00自动结束'), findsOneWidget);
      expect(find.textContaining('明天 07:00'), findsNothing);
      await tester.tap(find.text('设置'));
      await tester.pumpAndSettle();
      expect(find.text('本次监控将于今天 07:00自动结束。'), findsOneWidget);
      expect(find.textContaining('明天 07:00'), findsNothing);
      await tester.pumpWidget(const SizedBox());
      await gateway.events.close();
    },
  );

  testWidgets('the idle preview updates when the chosen time passes', (
    tester,
  ) async {
    final gateway = FakeMonitorGateway();
    var now = DateTime(2026, 10, 15, 6, 59);
    await tester.pumpWidget(SleepMonitorApp(gateway: gateway, now: () => now));
    await tester.pumpAndSettle();
    expect(find.text('现在开始，将于今天 07:00自动结束'), findsOneWidget);
    now = DateTime(2026, 10, 15, 7);
    await tester.pump(const Duration(minutes: 1));
    expect(find.text('现在开始，将于明天 07:00自动结束'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    await gateway.events.close();
  });

  testWidgets(
    'start and stop follow native events and recording list is empty',
    (tester) async {
      final gateway = FakeMonitorGateway();
      await tester.pumpWidget(SleepMonitorApp(gateway: gateway));
      await tester.pumpAndSettle();
      expect(find.text('准备好后开始'), findsOneWidget);
      await tester.ensureVisible(find.byKey(const Key('monitor-toggle')));
      await tester.tap(find.byKey(const Key('monitor-toggle')));
      await tester.pumpAndSettle();
      expect(gateway.starts, 1);
      expect(find.text('正在准备录制'), findsOneWidget);
      await tester.ensureVisible(find.byKey(const Key('monitor-toggle')));
      await tester.tap(find.byKey(const Key('monitor-toggle')));
      await tester.pumpAndSettle();
      expect(gateway.stops, 1);
      await tester.tap(find.text('片段'));
      await tester.pumpAndSettle();
      expect(find.text('还没有录制片段'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      await gateway.events.close();
    },
  );

  testWidgets(
    'saved snore mode clearly blocks start while detector is unavailable',
    (tester) async {
      final gateway = FakeMonitorGateway()
        ..settings = const MonitorSettings(mode: TriggerMode.snore);
      await tester.pumpWidget(SleepMonitorApp(gateway: gateway));
      await tester.pumpAndSettle();
      final button = tester.widget<FilledButton>(
        find.byKey(const Key('monitor-toggle')),
      );
      expect(button.onPressed, isNull);
      expect(find.text('鼾声触发'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      await gateway.events.close();
    },
  );

  testWidgets('settings validate input and persist through the gateway', (
    tester,
  ) async {
    final gateway = FakeMonitorGateway()
      ..settings = const MonitorSettings(endHour: 6, endMinute: 45);
    await tester.pumpWidget(SleepMonitorApp(gateway: gateway));
    await tester.pumpAndSettle();
    await tester.tap(find.text('设置'));
    await tester.pumpAndSettle();
    final recording = find.widgetWithText(TextFormField, '单段录制时长（秒）');
    await tester.enterText(recording, '0');
    await tester.ensureVisible(find.byKey(const Key('save-settings')));
    await tester.tap(find.byKey(const Key('save-settings')));
    await tester.pumpAndSettle();
    expect(gateway.saves, 0);
    await tester.ensureVisible(recording);
    await tester.enterText(recording, '15');
    await tester.ensureVisible(find.byKey(const Key('save-settings')));
    await tester.tap(find.byKey(const Key('save-settings')));
    await tester.pumpAndSettle();
    expect(gateway.settings.recordingSeconds, 15);
    expect(gateway.settings.endHour, 6);
    expect(gateway.settings.endMinute, 45);
    expect(gateway.saves, 1);
    await tester.pumpWidget(const SizedBox());
    await gateway.events.close();
  });

  testWidgets(
    'unsupported platforms display no fake recordings or active start',
    (tester) async {
      await tester.pumpWidget(
        SleepMonitorApp(gateway: UnsupportedMonitorGateway()),
      );
      await tester.pumpAndSettle();
      expect(find.text('请在安卓手机上运行以使用监控与录制'), findsOneWidget);
      final button = tester.widget<FilledButton>(
        find.byKey(const Key('monitor-toggle')),
      );
      expect(button.onPressed, isNull);
      await tester.pumpWidget(const SizedBox());
    },
  );
}
