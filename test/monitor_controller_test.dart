import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sleep_monitor/src/application/monitor_controller.dart';
import 'package:sleep_monitor/src/domain/monitor_models.dart';

import 'support/fake_monitor_gateway.dart';

void main() {
  late FakeMonitorGateway gateway;
  late MonitorController controller;

  setUp(() async {
    gateway = FakeMonitorGateway();
    controller = MonitorController(gateway);
    await controller.initialize();
  });

  tearDown(() async {
    controller.dispose();
    await gateway.events.close();
  });

  test(
    'active native session blocks duplicate start and settings writes',
    () async {
      expect(await controller.start(), isTrue);
      expect(controller.snapshot.phase, MonitorPhase.preparing);
      expect(await controller.start(), isFalse);
      expect(
        await controller.saveSettings(
          const MonitorSettings(recordingSeconds: 15),
        ),
        isFalse,
      );
      expect(gateway.starts, 1);
      expect(gateway.saves, 0);
      expect(await controller.stop(), isTrue);
      expect(controller.canEdit, isTrue);
    },
  );

  test(
    'unavailable snore detector cannot start a simulated monitoring session',
    () async {
      await controller.saveSettings(
        const MonitorSettings(mode: TriggerMode.snore),
      );
      expect(controller.canStart, isFalse);
      expect(await controller.start(), isFalse);
      expect(gateway.starts, 0);
    },
  );

  test('failed persistence keeps the previously saved configuration', () async {
    gateway.saveError = PlatformException(code: 'DISK', message: '存储空间不足');
    expect(
      await controller.saveSettings(
        const MonitorSettings(recordingSeconds: 15),
      ),
      isFalse,
    );
    expect(controller.settings.recordingSeconds, 300);
    expect(controller.error, '存储空间不足');
    expect(controller.busy, isFalse);
  });

  test('invalid durations are rejected before native persistence', () async {
    for (final settings in [
      const MonitorSettings(recordingSeconds: 0),
      const MonitorSettings(recordingSeconds: 301),
      const MonitorSettings(intervalSeconds: 0),
      const MonitorSettings(sessionSeconds: 86401),
    ]) {
      expect(await controller.saveSettings(settings), isFalse);
    }
    expect(gateway.saves, 0);
  });

  test(
    'native completion refreshes clips without polling every countdown tick',
    () async {
      gateway.emit(
        const MonitorSnapshot(
          phase: MonitorPhase.recording,
          remainingSeconds: 10,
        ),
      );
      gateway.emit(
        const MonitorSnapshot(
          phase: MonitorPhase.recording,
          remainingSeconds: 9,
        ),
      );
      expect(gateway.recordingReads, 0);
      gateway.emit(
        const MonitorSnapshot(phase: MonitorPhase.waiting, completedClips: 1),
      );
      await Future<void>.delayed(Duration.zero);
      expect(gateway.recordingReads, 1);
    },
  );
}
