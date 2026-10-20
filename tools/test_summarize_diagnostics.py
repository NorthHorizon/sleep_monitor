import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from summarize_diagnostics import read_events, render_report


def event(name, *, details=None, elapsed=1000, timestamp=2000000):
    return {
        'schemaVersion': 1, 'event': name, 'sessionId': 'night-1',
        'details': details or {}, 'elapsedRealtimeMs': elapsed,
        'timestampMs': timestamp,
    }


class DiagnosticSummaryTest(unittest.TestCase):
    def test_normal_completion_deduplicates_saved_clips_and_uses_monotonic_duration(self):
        saved = event('clip_saved', details={'clipId': 'clip-1', 'durationMs': 300000,
                                            'sizeBytes': 1048576, 'finalizeMs': 250})
        items = [event('session_start'), event('clip_started', details={'clipId': 'clip-1', 'startupMs': 150}),
                 saved, saved,
                 event('session_end', details={'reason': 'auto_end'}, elapsed=3601000, timestamp=1000000)]
        report = render_report(items, 0)
        self.assertIn('到时自动结束', report)
        self.assertIn('已保存：1 段，合计 5.00 分钟 / 1.0 MiB', report)
        self.assertIn('1.00 小时（单调时钟）', report)
        self.assertIn('已开始但未见保存成功的片段：0 段', report)

    def test_missing_end_is_not_reported_as_normal_completion(self):
        report = render_report([event('session_start'),
                                event('clip_started', details={'clipId': 'unfinished'})], 0)
        self.assertIn('状态：缺少结束记录', report)
        self.assertIn('未见保存成功的片段：1 段', report)
        self.assertIn('不能单独证明被系统杀死', report)

    def test_corrupt_partial_line_is_skipped_without_discarding_prior_events(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'events.jsonl'
            path.write_text(json.dumps(event('heartbeat')) + '\n{"schemaVer', encoding='utf-8')
            items, skipped = read_events(path)
        self.assertEqual(['heartbeat'], [item['event'] for item in items])
        self.assertEqual(1, skipped)

    def test_archive_entry_order_survives_wall_clock_correction(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'export.zip'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('events.1.jsonl', json.dumps(event('session_start', timestamp=2000000)) + '\n')
                archive.writestr('events.jsonl', json.dumps(event('session_end', timestamp=1000000)) + '\n')
            items, skipped = read_events(path)
        self.assertEqual(['session_start', 'session_end'], [item['event'] for item in items])
        self.assertEqual(0, skipped)

    def test_rotation_without_start_warns_that_statistics_are_partial(self):
        report = render_report([event('heartbeat'),
                                event('session_end', details={'reason': 'user'})], 0)
        self.assertIn('缺少会话开始记录', report)
        self.assertIn('统计可能不完整', report)

    def test_wait_overrun_excludes_the_configured_wait(self):
        report = render_report([event('waiting', details={'intervalSeconds': 10}, elapsed=1000),
                                event('clip_prepare', elapsed=12000)], 0)
        self.assertIn('额外延迟：中位 1000 ms / 最大 1000 ms', report)


if __name__ == '__main__':
    unittest.main()
