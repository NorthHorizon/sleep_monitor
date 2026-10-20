import 'package:flutter_test/flutter_test.dart';
import 'package:sleep_monitor/src/domain/monitor_models.dart';
import 'package:sleep_monitor/src/ui/formatters.dart';

void main() {
  const settings = MonitorSettings();

  test('a future clock time ends today', () {
    final now = DateTime(2026, 10, 15, 6, 30);
    final end = settings.nextEndAt(now);
    expect(end, DateTime(2026, 10, 15, 7));
    expect(formatEndTime(end, now), '今天 07:00');
    expect(formatPlannedDuration(now, end), '30 分钟');
  });

  test('a bedtime start resolves to tomorrow morning', () {
    final now = DateTime(2026, 10, 15, 23);
    final end = settings.nextEndAt(now);
    expect(end, DateTime(2026, 10, 16, 7));
    expect(formatEndTime(end, now), '明天 07:00');
    expect(formatPlannedDuration(now, end), '8 小时');
  });

  test('equal or passed clock times use the next calendar day', () {
    expect(
      settings.nextEndAt(DateTime(2026, 10, 15, 7)),
      DateTime(2026, 10, 16, 7),
    );
    expect(
      settings.nextEndAt(DateTime(2026, 10, 15, 7, 0, 1)),
      DateTime(2026, 10, 16, 7),
    );
  });

  test('midnight and year boundaries preserve the selected clock time', () {
    const midnight = MonitorSettings(endHour: 0);
    final now = DateTime(2026, 12, 31, 23, 30);
    final end = midnight.nextEndAt(now);
    expect(end, DateTime(2027, 1, 1));
    expect(formatEndTime(end, now), '明天 00:00');
  });

  test(
    'old duration settings adopt 07:00 without losing recording preferences',
    () {
      final migrated = MonitorSettings.fromMap({
        'mode': 'timed',
        'recordingSeconds': 15,
        'intervalSeconds': 10,
        'sessionSeconds': 60,
        'camera': 'front',
      });
      expect(migrated.endHour, 7);
      expect(migrated.endMinute, 0);
      expect(migrated.recordingSeconds, 15);
      expect(migrated.intervalSeconds, 10);
      expect(migrated.camera, CameraFacing.front);
      expect(migrated.toMap().containsKey('sessionSeconds'), isFalse);
    },
  );

  test(
    'native snapshot carries a fixed end instant even after it has passed',
    () {
      final end = DateTime(2026, 10, 16, 7);
      final snapshot = MonitorSnapshot.fromMap({
        'phase': 'stopping',
        'endsAt': end.millisecondsSinceEpoch,
      });
      expect(snapshot.endsAt, end);
      expect(
        formatEndTime(snapshot.endsAt!, DateTime(2026, 10, 16, 7, 1)),
        '今天 07:00',
      );
    },
  );
}
