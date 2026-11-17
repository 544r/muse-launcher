import unittest
import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
from unittest.mock import Mock, patch
from perf_capture import capture, summarize_frames


class FrameSummaryTests(unittest.TestCase):
    def test_invalid_flags_incomplete_frames_and_deadlines(self):
        text = '''header
---PROFILEDATA---
Flags,IntendedVsync,FrameDeadline,FrameCompleted,
0,1000000,17000000,11000000,
0,20000000,36000000,40000000,
1,40000000,56000000,100000000,
0,60000000,76000000,0,
0,80000000,96000000,9223372036854775807,
---PROFILEDATA---
'''
        result = summarize_frames(text)
        self.assertEqual(2, result['frame_samples'])
        self.assertEqual(10, result['p50_ms'])
        self.assertEqual(20, result['p95_ms'])
        self.assertEqual(1, result['missed_deadlines'])

    def test_older_android_without_deadlines(self):
        text = '---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted\n0,1000000,11000000\n---PROFILEDATA---'
        result = summarize_frames(text)
        self.assertEqual(1, result['frame_samples'])
        self.assertEqual(0, result['frames_with_deadlines'])
        self.assertIsNone(result['missed_deadlines'])

    def test_no_frames_is_not_reported_as_perfect_performance(self):
        self.assertEqual(0, summarize_frames('no frames')['frame_samples'])
        self.assertNotIn('p95_ms', summarize_frames('no frames'))


class CaptureTests(unittest.TestCase):
    def test_optional_trace_failure_still_saves_window_metrics(self):
        frames = '---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted\n0,1000000,11000000\n---PROFILEDATA---'
        def adb_result(command, **kwargs):
            if command[-1] == 'devices': output = 'List of devices attached\ntest-phone\tdevice\n'
            elif 'path' in command: output = 'package:/data/app/base.apk'
            elif command[-1] == 'framestats': output = frames
            else: output = 'test metadata'
            return subprocess.CompletedProcess(command, 0, output, '')
        trace = Mock(returncode=1)
        trace.communicate.return_value = (b'', b'Perfetto unsupported')
        trace.poll.return_value = 1
        with tempfile.TemporaryDirectory() as directory, \
                patch('perf_capture.find_adb', return_value='adb'), \
                patch('perf_capture.subprocess.run', side_effect=adb_result), \
                patch('perf_capture.subprocess.Popen', return_value=trace), \
                patch('perf_capture.time.sleep'), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            capture(SimpleNamespace(adb=None, serial=None, package='com.test.app', label='failure',
                                    output=directory, seconds=5, trace=True))
            result = next(Path(directory).iterdir())
            self.assertEqual(1, json.loads((result / 'summary.json').read_text())['frame_samples'])
            self.assertIn('perfetto_error', json.loads((result / 'metadata.json').read_text()))
            self.assertEqual(frames, (result / 'frames.txt').read_text())

    def test_no_authorized_device_stops_before_running_shell_commands(self):
        with patch('perf_capture.find_adb', return_value='adb'), \
                patch('perf_capture.subprocess.run', return_value=subprocess.CompletedProcess(
                    ['adb', 'devices'], 0, 'List of devices attached\ntest-phone\tunauthorized\n', '')) as run:
            with self.assertRaisesRegex(RuntimeError, 'authorized device'):
                capture(SimpleNamespace(adb=None, serial=None))
            self.assertEqual(1, run.call_count)
            self.assertEqual(['adb', 'devices'], run.call_args.args[0])


if __name__ == '__main__':
    unittest.main()
