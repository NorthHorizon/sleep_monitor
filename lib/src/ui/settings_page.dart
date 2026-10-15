import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../domain/monitor_models.dart';
import 'formatters.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({
    super.key,
    required this.settings,
    required this.enabled,
    required this.onSave,
  });

  final MonitorSettings settings;
  final bool enabled;
  final Future<bool> Function(MonitorSettings) onSave;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  final _form = GlobalKey<FormState>();
  late final TextEditingController _recording;
  late final TextEditingController _interval;
  late final TextEditingController _session;
  late TriggerMode _mode;
  late CameraFacing _camera;

  @override
  void initState() {
    super.initState();
    _recording = TextEditingController();
    _interval = TextEditingController();
    _session = TextEditingController();
    _loadSettings();
  }

  void _loadSettings() {
    _recording.text = '${widget.settings.recordingSeconds}';
    _interval.text = '${widget.settings.intervalSeconds}';
    _session.text = '${widget.settings.sessionSeconds ~/ 60}';
    _mode = widget.settings.mode;
    _camera = widget.settings.camera;
  }

  @override
  void didUpdateWidget(covariant SettingsPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.settings != widget.settings) _loadSettings();
  }

  @override
  void dispose() {
    _recording.dispose();
    _interval.dispose();
    _session.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.currentState!.validate()) return;
    final result = await widget.onSave(
      MonitorSettings(
        mode: _mode,
        recordingSeconds: int.parse(_recording.text),
        intervalSeconds: int.parse(_interval.text),
        sessionSeconds: int.parse(_session.text) * 60,
        camera: _camera,
      ),
    );
    if (result && mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('设置已保存，下次开始监控时使用')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final interval = int.tryParse(_interval.text) ?? 0;
    final recording = int.tryParse(_recording.text) ?? 0;
    return Form(
      key: _form,
      child: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          Text('录制设置', style: theme.textTheme.headlineMedium),
          const SizedBox(height: 8),
          Text(widget.enabled ? '每晚选择一种模式，参数保存于手机。' : '监控进行时不能修改本次设置。'),
          const SizedBox(height: 24),
          DropdownButtonFormField<TriggerMode>(
            key: ValueKey('mode-$_mode'),
            initialValue: _mode,
            isExpanded: true,
            decoration: const InputDecoration(labelText: '触发方式'),
            items: TriggerMode.values
                .map(
                  (mode) =>
                      DropdownMenuItem(value: mode, child: Text(mode.label)),
                )
                .toList(),
            onChanged: widget.enabled
                ? (mode) => setState(() => _mode = mode!)
                : null,
          ),
          if (_mode == TriggerMode.snore) ...[
            const SizedBox(height: 12),
            const Text('鼾声触发尚未启用。可以先保存参数，当前请使用定时模式录制。'),
          ],
          const SizedBox(height: 20),
          _numberField(_recording, '单段录制时长（秒）', 1, 300),
          const SizedBox(height: 20),
          _numberField(
            _interval,
            _mode == TriggerMode.snore ? '录完后冷却（秒）' : '录完后等待（秒）',
            1,
            86400,
          ),
          const SizedBox(height: 12),
          Text(
            _mode == TriggerMode.timed
                ? '录 ${formatDuration(recording)}，等待 ${formatDuration(interval)} 后再录。'
                : '录完后至少间隔 ${formatDuration(interval)}，再根据新声音判断。',
            style: theme.textTheme.bodySmall,
          ),
          const SizedBox(height: 20),
          _numberField(_session, '最长监控时间（分钟）', 1, 1440),
          const SizedBox(height: 20),
          DropdownButtonFormField<CameraFacing>(
            key: ValueKey('camera-$_camera'),
            initialValue: _camera,
            decoration: const InputDecoration(labelText: '摄像头'),
            items: CameraFacing.values
                .map(
                  (camera) => DropdownMenuItem(
                    value: camera,
                    child: Text(camera.label),
                  ),
                )
                .toList(),
            onChanged: widget.enabled
                ? (camera) => setState(() => _camera = camera!)
                : null,
          ),
          const SizedBox(height: 28),
          FilledButton.icon(
            key: const Key('save-settings'),
            onPressed: widget.enabled ? _save : null,
            icon: const Icon(Icons.check),
            label: const Padding(
              padding: EdgeInsets.symmetric(vertical: 14),
              child: Text('保存设置'),
            ),
          ),
          const SizedBox(height: 16),
          const Text('视频保存在本机应用目录，可在片段页播放或通过系统分享导出。卸载应用会删除这些文件。'),
        ],
      ),
    );
  }

  Widget _numberField(
    TextEditingController controller,
    String label,
    int minimum,
    int maximum,
  ) => TextFormField(
    controller: controller,
    enabled: widget.enabled,
    keyboardType: TextInputType.number,
    inputFormatters: [FilteringTextInputFormatter.digitsOnly],
    decoration: InputDecoration(
      labelText: label,
      helperText: '$minimum–$maximum',
    ),
    onChanged: (_) => setState(() {}),
    validator: (text) {
      final number = int.tryParse(text ?? '');
      return number == null || number < minimum || number > maximum
          ? '请输入 $minimum–$maximum 之间的整数'
          : null;
    },
  );
}
