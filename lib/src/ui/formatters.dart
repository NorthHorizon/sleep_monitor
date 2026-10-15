String formatDuration(int seconds) {
  final safe = seconds < 0 ? 0 : seconds;
  final hours = safe ~/ 3600;
  final minutes = (safe % 3600) ~/ 60;
  final remainder = safe % 60;
  if (hours > 0) return '$hours 小时 ${minutes > 0 ? '$minutes 分钟' : ''}'.trim();
  if (minutes > 0 && remainder == 0) return '$minutes 分钟';
  if (minutes > 0) return '$minutes 分 $remainder 秒';
  return '$remainder 秒';
}

String formatCountdown(int seconds) {
  final safe = seconds < 0 ? 0 : seconds;
  final hours = safe ~/ 3600;
  final minutes = (safe % 3600) ~/ 60;
  final rest = (safe % 60).toString().padLeft(2, '0');
  if (hours > 0) return '$hours:${minutes.toString().padLeft(2, '0')}:$rest';
  return '${minutes.toString().padLeft(2, '0')}:$rest';
}

String formatDateTime(DateTime value) {
  final date = value.toLocal();
  String two(int number) => number.toString().padLeft(2, '0');
  return '${date.month}月${date.day}日 ${two(date.hour)}:${two(date.minute)}';
}
