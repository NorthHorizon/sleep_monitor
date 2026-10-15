import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../domain/monitor_models.dart';
import '../platform/monitor_gateway.dart';

class MonitorController extends ChangeNotifier {
  MonitorController(this._gateway);

  final MonitorGateway _gateway;
  StreamSubscription<MonitorSnapshot>? _subscription;
  bool _disposed = false;
  bool initializing = true;
  bool busy = false;
  String? error;
  MonitorSettings settings = const MonitorSettings();
  MonitorSnapshot snapshot = const MonitorSnapshot();
  MonitorCapabilities capabilities = const MonitorCapabilities();
  List<RecordingClip> recordings = const [];

  bool get canEdit => !initializing && !busy && !snapshot.isActive;
  bool get canStart => canEdit && capabilities.supports(settings.mode);

  Future<void> initialize() async {
    try {
      final bootstrap = await _gateway.load();
      if (_disposed) return;
      settings = bootstrap.settings;
      snapshot = bootstrap.snapshot;
      capabilities = bootstrap.capabilities;
      recordings = bootstrap.recordings;
      _subscription ??= _gateway.snapshots.listen(
        _receiveSnapshot,
        onError: (Object exception) => _setError(exception),
      );
      error = null;
    } catch (exception) {
      _setError(exception);
    } finally {
      initializing = false;
      _notify();
    }
  }

  void _receiveSnapshot(MonitorSnapshot next) {
    final refresh =
        next.completedClips != snapshot.completedClips ||
        (snapshot.isActive && !next.isActive);
    snapshot = next;
    _notify();
    if (refresh) unawaited(refreshRecordings());
  }

  Future<bool> saveSettings(MonitorSettings next) async {
    if (!canEdit) return false;
    if (next.validationError case final String message) {
      error = message;
      _notify();
      return false;
    }
    return _perform(() async {
      await _gateway.saveSettings(next);
      settings = next;
    });
  }

  Future<bool> start() async {
    if (!canStart) return false;
    return _perform(_gateway.start);
  }

  Future<bool> stop() async {
    if (busy || !snapshot.isActive) return false;
    return _perform(_gateway.stop);
  }

  Future<void> refreshRecordings() async {
    try {
      final items = await _gateway.listRecordings();
      if (_disposed) return;
      recordings = items;
      _notify();
    } catch (exception) {
      _setError(exception);
    }
  }

  Future<bool> openRecording(String id) =>
      _perform(() => _gateway.openRecording(id));
  Future<bool> shareRecording(String id) =>
      _perform(() => _gateway.shareRecording(id));

  void dismissError() {
    error = null;
    _notify();
  }

  Future<bool> _perform(Future<void> Function() action) async {
    if (busy || _disposed) return false;
    busy = true;
    error = null;
    _notify();
    try {
      await action();
      return true;
    } catch (exception) {
      _setError(exception);
      return false;
    } finally {
      busy = false;
      _notify();
    }
  }

  void _setError(Object exception) {
    error = switch (exception) {
      PlatformException(:final message) => message ?? '安卓服务调用失败',
      MissingPluginException() => '安卓服务尚未连接，请完整重新启动应用',
      _ => exception.toString(),
    };
    _notify();
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    unawaited(_subscription?.cancel());
    super.dispose();
  }
}
