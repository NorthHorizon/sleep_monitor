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

String formatTimeOfDay(int hour, int minute) =>
    '${hour.toString().padLeft(2, '0')}:${minute.toString().padLeft(2, '0')}';

String formatEndTime(DateTime end, DateTime now) {
  final today = DateTime(now.year, now.month, now.day);
  final tomorrow = DateTime(now.year, now.month, now.day + 1);
  final date = DateTime(end.year, end.month, end.day);
  final label = date == today
      ? '今天'
      : date == tomorrow
      ? '明天'
      : '${end.month}月${end.day}日';
  return '$label ${formatTimeOfDay(end.hour, end.minute)}';
}

String formatPlannedDuration(DateTime now, DateTime end) {
  final remaining = end.difference(now).inMilliseconds;
  if (remaining <= 0) return '即将结束';
  if (remaining < 60000) return '不到 1 分钟';
  return formatDuration(((remaining + 59999) ~/ 60000) * 60);
}
