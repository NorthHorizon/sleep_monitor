"""Summarize an exported Sleep Monitor ZIP or a single JSONL log, offline.

Usage: python tools/summarize_diagnostics.py sleep-diagnostics.zip -o night.md
Only Python's standard library is required (Python 3.9+).
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import statistics
import zipfile


@contextmanager
def log_streams(path: Path):
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            # The app stores entries oldest first. Wall-clock sorting would break
            # the event order if the phone's clock was corrected during a session.
            names = [name for name in archive.namelist() if name.endswith('.jsonl')]
            if not names:
                raise ValueError('ZIP 中没有 JSONL 运行日志')
            yield archive, names
    else:
        yield None, [path]


def read_events(path: Path) -> tuple[list[dict], int]:
    events: list[dict] = []
    skipped = 0
    with log_streams(path) as (archive, names):
        for name in names:
            raw = archive.open(name) if archive else Path(name).open('rb')
            with raw, io.TextIOWrapper(raw, encoding='utf-8-sig', errors='replace') as stream:
                for line in stream:
                    if not line.strip():
                        continue
                    try:
                        item = json.loads(line)
                        if not isinstance(item, dict) or not isinstance(item.get('event'), str):
                            raise ValueError('Invalid event')
                        if item.get('schemaVersion') != 1:
                            raise ValueError('Unsupported schema')
                        if not isinstance(item.get('details'), dict):
                            raise ValueError('Invalid details')
                        events.append(item)
                    except (ValueError, TypeError):
                        skipped += 1
    return events, skipped


def number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def timestamp(event: dict | None, field: str = 'timestampMs') -> str:
    value = event.get(field) if event else None
    if not number(value):
        return '未知'
    try:
        return datetime.fromtimestamp(value / 1000, timezone.utc).isoformat(timespec='seconds')
    except (ValueError, OverflowError, OSError):
        return '未知'


def latency(values: list) -> str:
    samples = [value for value in values if number(value) and value >= 0]
    if not samples:
        return '无数据'
    return f'中位 {statistics.median(samples):.0f} ms / 最大 {max(samples):.0f} ms（{len(samples)} 次）'


def render_report(events: list[dict], skipped: int) -> str:
    sessions: dict[str, list[dict]] = {}
    for event in events:
        sid = event.get('sessionId')
        if isinstance(sid, str) and sid:
            sessions.setdefault(sid, []).append(event)
    discoveries = sum(event['event'] == 'previous_session_interrupted' for event in events)
    lines = [
        '# 夜间运行诊断', '',
        f'有效事件 {len(events)} 条；忽略损坏或不支持的记录 {skipped} 条；会话 {len(sessions)} 次。', '',
        '时间统一为 UTC（香港／北京时间加 8 小时）。日志只反映应用观察到的事件，不判断睡眠质量。', '',
    ]
    if discoveries:
        lines += [f'重开应用时发现上次未正常结束：{discoveries} 次；发现时间不等于实际中断时间。', '']
    if not sessions:
        lines += ['没有可汇总的监控会话。', '']
    for sid, items in sessions.items():
        starts = [item for item in items if item['event'] == 'session_start']
        ends = [item for item in items if item['event'] == 'session_end']
        start = starts[0] if starts else None
        end = ends[-1] if ends else None
        clips = {item['details'].get('clipId'): item for item in items
                 if item['event'] == 'clip_saved' and item['details'].get('clipId')}
        started_ids = {item['details'].get('clipId') for item in items
                       if item['event'] == 'clip_started' and item['details'].get('clipId')}
        states = [item['deviceState'] for item in items if isinstance(item.get('deviceState'), dict)]
        temperatures = [state['batteryTemperatureC'] for state in states
                        if number(state.get('batteryTemperatureC'))]
        free_storage = [state['freeStorageBytes'] for state in states
                        if number(state.get('freeStorageBytes'))]
        total_ms = sum(item['details'].get('durationMs', 0) for item in clips.values()
                       if number(item['details'].get('durationMs', 0)))
        total_bytes = sum(item['details'].get('sizeBytes', 0) for item in clips.values()
                          if number(item['details'].get('sizeBytes', 0)))
        reasons = {'auto_end': '到时自动结束', 'user': '手动结束', 'error': '异常结束'}
        outcome = reasons.get(end['details'].get('reason'), '已结束') if end else '缺少结束记录'
        if end is None and any(item['event'] == 'service_destroyed' for item in items):
            outcome = '服务被销毁，未正常结束'
        lines += [
            f'## 会话 {sid}', '',
            f'- 状态：{outcome}',
            f'- 开始：{timestamp(start)}；最后记录：{timestamp(items[-1])}',
            f'- 已保存：{len(clips)} 段，合计 {total_ms / 60000:.2f} 分钟 / {total_bytes / 1024**2:.1f} MiB',
            f'- 已开始但未见保存成功的片段：{len(started_ids - clips.keys())} 段',
            '- 相机启动耗时：' + latency([item['details'].get('startupMs') for item in items
                                         if item['event'] == 'clip_started']),
            '- 封装及索引保存耗时：' + latency([item['details'].get('finalizeMs') for item in clips.values()]),
        ]
        if start:
            details = start['details']
            lines.append(f"- 计划结束：{timestamp(details, 'endsAt')}；配置：`{json.dumps(details.get('settings', {}), ensure_ascii=False)}`")
        else:
            lines.append('- 缺少会话开始记录，日志可能已轮换，以下统计可能不完整。')
        if start and end:
            first, last = start.get('elapsedRealtimeMs'), end.get('elapsedRealtimeMs')
            if number(first) and number(last) and last >= first:
                lines.append(f'- 会话经过：{(last - first) / 3600000:.2f} 小时（单调时钟）')
        if states:
            first_battery, last_battery = states[0].get('batteryPercent'), states[-1].get('batteryPercent')
            if number(first_battery) and number(last_battery):
                lines.append(f'- 电量采样首末：{first_battery:.0f}% → {last_battery:.0f}%（是否充电见原始日志，不据此直接计算耗电率）')
        if temperatures:
            lines.append(f'- 电池温度采样最高：{max(temperatures):.1f} °C（非 CPU 温度）')
        if free_storage:
            lines.append(f'- 剩余空间采样最低：{min(free_storage) / 1024**3:.2f} GiB')
        if states:
            sleeping = sum(state.get('screenInteractive') is False for state in states)
            lines.append(f'- 设备非交互状态采样：{sleeping} / {len(states)} 次（只证明采样时的状态）')
        gaps = [item['details'].get('gapMs') for item in items if item['event'] == 'scheduler_gap']
        lines.append(f'- 超过 15 秒的调度空档：{len(gaps)} 次；' + latency(gaps))
        wait = None
        overruns = []
        for item in items:
            if item['event'] == 'waiting':
                wait = item
            elif item['event'] == 'clip_prepare' and wait:
                first, last = wait.get('elapsedRealtimeMs'), item.get('elapsedRealtimeMs')
                interval = wait['details'].get('intervalSeconds')
                if number(first) and number(last) and number(interval) and last >= first:
                    overruns.append(max(0, last - first - interval * 1000))
                wait = None
        lines.append('- 等待结束后启动下一段的额外延迟：' + latency(overruns))
        if end is None:
            lines.append('- 缺少结束记录不能单独证明被系统杀死，也可能是导出时仍在运行或日志不完整。')
        errors = [item for item in items if item['details'].get('error')]
        for item in errors:
            text = str(item['details']['error']).replace('\n', ' ').replace('\r', ' ')[:600]
            lines.append(f"- 错误 {timestamp(item)} [{item['event']}]：{text}")
        lines.append('')
    return '\n'.join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('log', type=Path, help='导出的 ZIP 或单个 JSONL')
    parser.add_argument('-o', '--output', type=Path, help='写入 Markdown 文件')
    args = parser.parse_args()
    try:
        events, skipped = read_events(args.log)
        report = render_report(events, skipped)
        if args.output:
            args.output.write_text(report, encoding='utf-8')
        else:
            print(report)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        parser.exit(1, f'无法汇总运行日志：{error}\n')


if __name__ == '__main__':
    main()
