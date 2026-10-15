import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../domain/monitor_models.dart';

abstract interface class MonitorGateway {
  Future<MonitorBootstrap> load();
  Stream<MonitorSnapshot> get snapshots;
  Future<void> saveSettings(MonitorSettings settings);
  Future<void> start();
  Future<void> stop();
  Future<List<RecordingClip>> listRecordings();
  Future<void> openRecording(String id);
  Future<void> shareRecording(String id);
}

MonitorGateway createMonitorGateway() =>
    !kIsWeb && defaultTargetPlatform == TargetPlatform.android
    ? AndroidMonitorGateway()
    : UnsupportedMonitorGateway();

class AndroidMonitorGateway implements MonitorGateway {
  AndroidMonitorGateway({MethodChannel? commands, EventChannel? events})
    : _commands = commands ?? const MethodChannel(_commandChannel),
      _events = events ?? const EventChannel(_eventChannel);

  static const _commandChannel = 'com.nightowl.sleep_monitor/commands';
  static const _eventChannel = 'com.nightowl.sleep_monitor/events';
  final MethodChannel _commands;
  final EventChannel _events;

  @override
  Future<MonitorBootstrap> load() async {
    final map = await _commands.invokeMapMethod<Object?, Object?>('bootstrap');
    if (map == null) throw StateError('安卓服务未返回初始化数据');
    return MonitorBootstrap.fromMap(map);
  }

  @override
  Stream<MonitorSnapshot> get snapshots => _events.receiveBroadcastStream().map(
    (event) => MonitorSnapshot.fromMap(event as Map<Object?, Object?>),
  );

  @override
  Future<void> saveSettings(MonitorSettings settings) =>
      _commands.invokeMethod<void>('saveSettings', settings.toMap());
  @override
  Future<void> start() => _commands.invokeMethod<void>('startSession');
  @override
  Future<void> stop() => _commands.invokeMethod<void>('stopSession');

  @override
  Future<List<RecordingClip>> listRecordings() async {
    final items = await _commands.invokeListMethod<Object?>('listRecordings');
    return (items ?? [])
        .map((item) => RecordingClip.fromMap(item as Map<Object?, Object?>))
        .toList(growable: false);
  }

  @override
  Future<void> openRecording(String id) =>
      _commands.invokeMethod<void>('openRecording', {'id': id});
  @override
  Future<void> shareRecording(String id) =>
      _commands.invokeMethod<void>('shareRecording', {'id': id});
}

/// Renders the UI on other platforms without pretending to record.
class UnsupportedMonitorGateway implements MonitorGateway {
  @override
  Stream<MonitorSnapshot> get snapshots => const Stream.empty();
  @override
  Future<MonitorBootstrap> load() async => const MonitorBootstrap(
    settings: MonitorSettings(),
    snapshot: MonitorSnapshot(),
    capabilities: MonitorCapabilities(message: '请在安卓手机上运行以使用监控与录制'),
    recordings: [],
  );

  Never _unsupported() => throw UnsupportedError('此功能仅支持安卓手机');
  @override
  Future<void> saveSettings(MonitorSettings settings) async => _unsupported();
  @override
  Future<void> start() async => _unsupported();
  @override
  Future<void> stop() async => _unsupported();
  @override
  Future<List<RecordingClip>> listRecordings() async => [];
  @override
  Future<void> openRecording(String id) async => _unsupported();
  @override
  Future<void> shareRecording(String id) async => _unsupported();
}
