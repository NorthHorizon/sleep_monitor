import 'dart:async';

import 'package:sleep_monitor/src/domain/monitor_models.dart';
import 'package:sleep_monitor/src/platform/monitor_gateway.dart';

class FakeMonitorGateway implements MonitorGateway {
  final events = StreamController<MonitorSnapshot>.broadcast(sync: true);
  MonitorSettings settings = const MonitorSettings();
  MonitorSnapshot snapshot = const MonitorSnapshot();
  List<RecordingClip> recordings = [];
  int starts = 0;
  int stops = 0;
  int saves = 0;
  int recordingReads = 0;
  Object? saveError;
  int diagnosticExports = 0;
  Object? diagnosticError;

  @override
  Stream<MonitorSnapshot> get snapshots => events.stream;

  @override
  Future<MonitorBootstrap> load() async => MonitorBootstrap(
    settings: settings,
    snapshot: snapshot,
    capabilities: const MonitorCapabilities(timedRecording: true),
    recordings: recordings,
  );

  void emit(MonitorSnapshot value) {
    snapshot = value;
    events.add(value);
  }

  @override
  Future<void> saveSettings(MonitorSettings value) async {
    if (saveError != null) throw saveError!;
    saves++;
    settings = value;
  }

  @override
  Future<void> start() async {
    starts++;
    emit(const MonitorSnapshot(phase: MonitorPhase.preparing));
  }

  @override
  Future<void> stop() async {
    stops++;
    emit(const MonitorSnapshot());
  }

  @override
  Future<List<RecordingClip>> listRecordings() async {
    recordingReads++;
    return recordings;
  }

  @override
  Future<void> openRecording(String id) async {}
  @override
  Future<void> shareRecording(String id) async {}
  @override
  Future<void> shareDiagnostics() async {
    if (diagnosticError != null) throw diagnosticError!;
    diagnosticExports++;
  }
}
