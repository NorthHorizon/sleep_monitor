import 'package:flutter/material.dart';

import '../application/monitor_controller.dart';
import '../domain/monitor_models.dart';
import 'formatters.dart';

class MonitorPage extends StatelessWidget {
  const MonitorPage({
    super.key,
    required this.controller,
    required this.now,
    required this.onSettings,
  });

  final MonitorController controller;
  final DateTime now;
  final VoidCallback onSettings;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final status = controller.snapshot;
    final settings = controller.settings;
    final end = status.isActive ? status.endsAt : settings.nextEndAt(now);
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        Text('留下一晚的片段', style: theme.textTheme.headlineMedium),
        const SizedBox(height: 8),
        Text(
          '记录声音与画面，早晨再慢慢回看。',
          style: theme.textTheme.bodyLarge?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 24),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(
                      status.phase == MonitorPhase.recording
                          ? Icons.fiber_manual_record
                          : Icons.nightlight_round,
                      color: status.phase == MonitorPhase.recording
                          ? theme.colorScheme.error
                          : theme.colorScheme.primary,
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        status.phase.label,
                        style: theme.textTheme.titleLarge,
                      ),
                    ),
                  ],
                ),
                if (status.phase != MonitorPhase.preparing &&
                    status.phase != MonitorPhase.finalizing) ...[
                  const SizedBox(height: 20),
                  Text(
                    status.isActive
                        ? formatCountdown(status.remainingSeconds)
                        : '准备好后开始',
                    style: theme.textTheme.headlineLarge?.copyWith(
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ],
                const SizedBox(height: 8),
                Text(status.message ?? '手机独立运行，电脑可以保持关机。'),
                if (end != null) ...[
                  const SizedBox(height: 12),
                  Text(
                    status.isActive
                        ? '本次监控将于${formatEndTime(end, now)}自动结束'
                        : '现在开始，将于${formatEndTime(end, now)}自动结束',
                    key: const Key('monitor-end-time'),
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: theme.colorScheme.primary,
                    ),
                  ),
                  if (!status.isActive)
                    Text(
                      '预计监控 ${formatPlannedDuration(now, end)}',
                      style: theme.textTheme.bodySmall,
                    ),
                ],
                if (status.startedAt case final DateTime startedAt) ...[
                  const SizedBox(height: 12),
                  Text(
                    '${formatDateTime(startedAt)} 开始 · 已保存 ${status.completedClips} 段',
                    style: theme.textTheme.bodySmall,
                  ),
                ],
                const SizedBox(height: 24),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    key: const Key('monitor-toggle'),
                    onPressed: controller.busy
                        ? null
                        : status.isActive
                        ? () => controller.stop()
                        : controller.canStart
                        ? () => controller.start()
                        : null,
                    icon: Icon(
                      status.isActive
                          ? Icons.stop_rounded
                          : Icons.play_arrow_rounded,
                    ),
                    label: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 14),
                      child: Text(status.isActive ? '结束监控' : '开始监控'),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 20),
        Text('本次计划', style: theme.textTheme.titleMedium),
        const SizedBox(height: 12),
        Card(
          child: Column(
            children: [
              ListTile(
                leading: const Icon(Icons.schedule),
                title: Text(settings.mode.label),
                subtitle: Text(
                  '录制 ${formatDuration(settings.recordingSeconds)}，'
                  '录完后间隔 ${formatDuration(settings.intervalSeconds)}',
                ),
                trailing: IconButton(
                  tooltip: '修改设置',
                  onPressed: onSettings,
                  icon: const Icon(Icons.tune),
                ),
              ),
              const Divider(height: 1, indent: 16, endIndent: 16),
              ListTile(
                leading: const Icon(Icons.videocam_outlined),
                title: Text(settings.camera.label),
                subtitle: const Text('带声音的视频 · 到点停止并保存当前片段'),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        if (controller.capabilities.message case final String message)
          _Notice(message: message),
        if (controller.capabilities.timedRecording) ...[
          const SizedBox(height: 12),
          const _Notice(message: '开始后会先录第一段，之后按设置间隔录制。熄屏运行效果需要在你的手机上验证。'),
        ],
      ],
    );
  }
}

class _Notice extends StatelessWidget {
  const _Notice({required this.message});
  final String message;

  @override
  Widget build(BuildContext context) => Row(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Icon(
        Icons.info_outline,
        size: 18,
        color: Theme.of(context).colorScheme.onSurfaceVariant,
      ),
      const SizedBox(width: 8),
      Expanded(
        child: Text(message, style: Theme.of(context).textTheme.bodySmall),
      ),
    ],
  );
}
