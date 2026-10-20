enum TriggerMode {
  timed('定时录制'),
  snore('鼾声触发');

  const TriggerMode(this.label);
  final String label;
}

enum CameraFacing {
  back('后置摄像头'),
  front('前置摄像头');

  const CameraFacing(this.label);
  final String label;
}

enum MonitorPhase {
  idle('尚未开始'),
  preparing('正在准备录制'),
  recording('正在录制'),
  finalizing('正在保存片段'),
  waiting('等待下一段'),
  listening('正在监听鼾声'),
  cooldown('冷却中'),
  stopping('正在结束监控'),
  error('监控已中断');

  const MonitorPhase(this.label);
  final String label;
  bool get isActive => this != idle && this != error;
}

class MonitorSettings {
  const MonitorSettings({
    this.mode = TriggerMode.timed,
    this.recordingSeconds = 300,
    this.intervalSeconds = 900,
    this.endHour = 7,
    this.endMinute = 0,
    this.camera = CameraFacing.back,
  });

  final TriggerMode mode;
  final int recordingSeconds;
  // The interval begins after the previous recording has been finalized.
  final int intervalSeconds;
  final int endHour;
  final int endMinute;
  final CameraFacing camera;

  String? get validationError {
    if (recordingSeconds < 1 || recordingSeconds > 300) {
      return '单段录制时长须为 1–300 秒';
    }
    if (intervalSeconds < 1 || intervalSeconds > 86400) {
      return '录制间隔须为 1 秒至 24 小时';
    }
    if (endHour < 0 || endHour > 23 || endMinute < 0 || endMinute > 59) {
      return '请选择有效的自动结束时间';
    }
    return null;
  }

  MonitorSettings copyWith({
    TriggerMode? mode,
    int? recordingSeconds,
    int? intervalSeconds,
    int? endHour,
    int? endMinute,
    CameraFacing? camera,
  }) => MonitorSettings(
    mode: mode ?? this.mode,
    recordingSeconds: recordingSeconds ?? this.recordingSeconds,
    intervalSeconds: intervalSeconds ?? this.intervalSeconds,
    endHour: endHour ?? this.endHour,
    endMinute: endMinute ?? this.endMinute,
    camera: camera ?? this.camera,
  );

  Map<String, Object?> toMap() => {
    'mode': mode.name,
    'recordingSeconds': recordingSeconds,
    'intervalSeconds': intervalSeconds,
    'endHour': endHour,
    'endMinute': endMinute,
    'camera': camera.name,
  };

  factory MonitorSettings.fromMap(Map<Object?, Object?> map) => MonitorSettings(
    mode: TriggerMode.values.byName(map['mode'] as String),
    recordingSeconds: (map['recordingSeconds'] as num).toInt(),
    intervalSeconds: (map['intervalSeconds'] as num).toInt(),
    // Settings from the duration-based version adopt the new 07:00 default.
    endHour: (map['endHour'] as num?)?.toInt() ?? 7,
    endMinute: (map['endMinute'] as num?)?.toInt() ?? 0,
    camera: CameraFacing.values.byName(map['camera'] as String),
  );

  /// Preview only. Android resolves and freezes the actual deadline on start.
  DateTime nextEndAt(DateTime now) {
    DateTime onDay(int day) => now.isUtc
        ? DateTime.utc(now.year, now.month, day, endHour, endMinute)
        : DateTime(now.year, now.month, day, endHour, endMinute);
    final today = onDay(now.day);
    return today.isAfter(now) ? today : onDay(now.day + 1);
  }
}

class MonitorSnapshot {
  const MonitorSnapshot({
    this.phase = MonitorPhase.idle,
    this.sessionId,
    this.startedAt,
    this.endsAt,
    this.remainingSeconds = 0,
    this.completedClips = 0,
    this.message,
  });

  final MonitorPhase phase;
  final String? sessionId;
  final DateTime? startedAt;
  final DateTime? endsAt;
  final int remainingSeconds;
  final int completedClips;
  final String? message;
  bool get isActive => phase.isActive;

  factory MonitorSnapshot.fromMap(Map<Object?, Object?> map) => MonitorSnapshot(
    phase: MonitorPhase.values.byName(map['phase'] as String),
    sessionId: map['sessionId'] as String?,
    startedAt: map['startedAt'] == null
        ? null
        : DateTime.fromMillisecondsSinceEpoch(
            (map['startedAt'] as num).toInt(),
          ),
    endsAt: map['endsAt'] == null
        ? null
        : DateTime.fromMillisecondsSinceEpoch((map['endsAt'] as num).toInt()),
    remainingSeconds: (map['remainingSeconds'] as num?)?.toInt() ?? 0,
    completedClips: (map['completedClips'] as num?)?.toInt() ?? 0,
    message: map['message'] as String?,
  );
}

class MonitorCapabilities {
  const MonitorCapabilities({
    this.timedRecording = false,
    this.snoreDetection = false,
    this.message,
  });

  final bool timedRecording;
  final bool snoreDetection;
  final String? message;

  bool supports(TriggerMode mode) => switch (mode) {
    TriggerMode.timed => timedRecording,
    TriggerMode.snore => snoreDetection,
  };

  factory MonitorCapabilities.fromMap(Map<Object?, Object?> map) =>
      MonitorCapabilities(
        timedRecording: map['timedRecording'] as bool? ?? false,
        snoreDetection: map['snoreDetection'] as bool? ?? false,
        message: map['message'] as String?,
      );
}

class RecordingClip {
  const RecordingClip({
    required this.id,
    required this.sessionId,
    required this.mode,
    required this.startedAt,
    required this.durationMilliseconds,
    required this.sizeBytes,
  });

  final String id;
  final String sessionId;
  final TriggerMode mode;
  final DateTime startedAt;
  final int durationMilliseconds;
  final int sizeBytes;

  factory RecordingClip.fromMap(Map<Object?, Object?> map) => RecordingClip(
    id: map['id'] as String,
    sessionId: map['sessionId'] as String,
    mode: TriggerMode.values.byName(map['mode'] as String),
    startedAt: DateTime.fromMillisecondsSinceEpoch(
      (map['startedAt'] as num).toInt(),
    ),
    durationMilliseconds: (map['durationMilliseconds'] as num).toInt(),
    sizeBytes: (map['sizeBytes'] as num).toInt(),
  );
}

class MonitorBootstrap {
  const MonitorBootstrap({
    required this.settings,
    required this.snapshot,
    required this.capabilities,
    required this.recordings,
  });

  final MonitorSettings settings;
  final MonitorSnapshot snapshot;
  final MonitorCapabilities capabilities;
  final List<RecordingClip> recordings;

  factory MonitorBootstrap.fromMap(
    Map<Object?, Object?> map,
  ) => MonitorBootstrap(
    settings: MonitorSettings.fromMap(map['settings'] as Map<Object?, Object?>),
    snapshot: MonitorSnapshot.fromMap(map['snapshot'] as Map<Object?, Object?>),
    capabilities: MonitorCapabilities.fromMap(
      map['capabilities'] as Map<Object?, Object?>,
    ),
    recordings: (map['recordings'] as List<Object?>)
        .map((item) => RecordingClip.fromMap(item as Map<Object?, Object?>))
        .toList(growable: false),
  );
}
