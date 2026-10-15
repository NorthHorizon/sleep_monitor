import 'package:flutter/material.dart';

import '../application/monitor_controller.dart';
import 'formatters.dart';

class RecordingsPage extends StatelessWidget {
  const RecordingsPage({super.key, required this.controller});
  final MonitorController controller;

  @override
  Widget build(BuildContext context) => RefreshIndicator(
    onRefresh: controller.refreshRecordings,
    child: ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.all(20),
      children: [
        Text('夜间片段', style: Theme.of(context).textTheme.headlineMedium),
        const SizedBox(height: 8),
        Text('已保存 ${controller.recordings.length} 段 · 下拉刷新'),
        const SizedBox(height: 24),
        if (controller.recordings.isEmpty)
          const Card(
            child: Padding(
              padding: EdgeInsets.symmetric(horizontal: 24, vertical: 48),
              child: Column(
                children: [
                  Icon(Icons.video_library_outlined, size: 48),
                  SizedBox(height: 20),
                  Text('还没有录制片段'),
                  SizedBox(height: 8),
                  Text('开始监控后，完成保存的视频会出现在这里。', textAlign: TextAlign.center),
                ],
              ),
            ),
          ),
        for (final clip in controller.recordings)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      formatDateTime(clip.startedAt),
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      '${clip.mode.label} · '
                      '${formatDuration(clip.durationMilliseconds ~/ 1000)} · '
                      '${(clip.sizeBytes / (1024 * 1024)).toStringAsFixed(1)} MB',
                    ),
                    const SizedBox(height: 12),
                    Wrap(
                      spacing: 8,
                      children: [
                        TextButton.icon(
                          onPressed: controller.busy
                              ? null
                              : () => controller.openRecording(clip.id),
                          icon: const Icon(Icons.play_circle_outline),
                          label: const Text('播放'),
                        ),
                        TextButton.icon(
                          onPressed: controller.busy
                              ? null
                              : () => controller.shareRecording(clip.id),
                          icon: const Icon(Icons.ios_share),
                          label: const Text('导出 / 分享'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
      ],
    ),
  );
}
