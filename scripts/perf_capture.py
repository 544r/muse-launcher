"""Capture a labelled, user-driven Android performance run without clearing app data.

Python 3 stdlib only. Frames are app-window metrics, not a complete WebView compositor FPS.
"""
import argparse
import csv
import datetime
import io
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


def summarize_frames(text):
    frames = []
    for chunk in text.split('---PROFILEDATA---')[1::2]:
        for row in csv.DictReader(io.StringIO(chunk.strip())):
            try:
                flags = int(row['Flags'])
                start = int(row['IntendedVsync'])
                end = int(row['FrameCompleted'])
                if flags != 0 or not 0 < start < end < 9223372036854775807:
                    continue
                duration = (end - start) / 1_000_000
                deadline = int(row.get('FrameDeadline', '0'))
                frames.append((duration, end > deadline if start < deadline < 9223372036854775807 else None))
            except (ValueError, KeyError, TypeError):
                continue
    if not frames:
        return {'frame_samples': 0, 'note': 'No valid app-window frame samples; inspect Perfetto.'}
    values = sorted(frame[0] for frame in frames)
    def percentile(p):
        return round(values[max(0, math.ceil(len(values) * p) - 1)], 3)
    deadlines = [frame[1] for frame in frames if frame[1] is not None]
    return {
        'frame_samples': len(values),
        'p50_ms': percentile(.50), 'p95_ms': percentile(.95), 'p99_ms': percentile(.99),
        'max_ms': round(values[-1], 3),
        'frames_with_deadlines': len(deadlines),
        'missed_deadlines': sum(deadlines) if deadlines else None,
        'note': 'Bounded gfxinfo sample, app window only. Compare same task/device/refresh rate; not webpage FPS.',
    }


def find_adb(explicit):
    if explicit:
        return explicit
    candidate = shutil.which('adb')
    if candidate:
        return candidate
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk and os.environ.get('LOCALAPPDATA'):
        sdk = str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk')
    candidate = Path(sdk or '') / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')
    if candidate.is_file():
        return str(candidate)
    raise RuntimeError('adb not found; supply --adb or ANDROID_HOME')


def capture(args):
    adb = [find_adb(args.adb)]
    process_options = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
    def run(parts, *, timeout=30, check=True):
        result = subprocess.run(adb + parts, capture_output=True, text=True, encoding='utf-8',
                                errors='replace', timeout=timeout, **process_options)
        if check and result.returncode:
            raise RuntimeError('ADB command failed: ' + ' '.join(parts) + '\n' + result.stderr[:500])
        return result.stdout + result.stderr
    devices = run(['devices']).splitlines()
    available = [line.split()[0] for line in devices if line.endswith('\tdevice')]
    if args.serial:
        if args.serial not in available:
            raise RuntimeError('Selected device is not connected and authorized')
        adb += ['-s', args.serial]
    elif len(available) != 1:
        raise RuntimeError('Connect one authorized device, or select one with --serial; no app was changed')
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_.]+', args.package):
        raise RuntimeError('Invalid package name')
    if 'package:' not in run(['shell', 'pm', 'path', args.package]):
        raise RuntimeError('Selected app is not installed')
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    label = re.sub(r'[^A-Za-z0-9_-]+', '-', args.label).strip('-') or 'run'
    output = Path(args.output) / (stamp + '-' + label)
    output.mkdir(parents=True, exist_ok=False)
    metadata = {'label': args.label, 'package': args.package, 'seconds': args.seconds, 'started': stamp,
                'trace_requested': args.trace}
    for key, prop in [('model', 'ro.product.model'), ('android', 'ro.build.version.release'), ('sdk', 'ro.build.version.sdk')]:
        metadata[key] = run(['shell', 'getprop', prop]).strip()
    package_info = run(['shell', 'dumpsys', 'package', args.package])
    metadata['version_lines'] = [line.strip() for line in package_info.splitlines() if re.search(r'version(Code|Name)=', line)]
    for filename, command in [
        ('webview.txt', ['shell', 'dumpsys', 'webviewupdate']),
        ('display.txt', ['shell', 'dumpsys', 'display']),
        ('memory-before.txt', ['shell', 'dumpsys', 'meminfo', args.package]),
        ('thermal-before.txt', ['shell', 'dumpsys', 'thermalservice']),
    ]:
        (output / filename).write_text(run(command, check=False), encoding='utf-8')
    # Reset statistics only; no force-stop, install, account changes, or data deletion.
    run(['shell', 'dumpsys', 'gfxinfo', args.package, 'reset'])
    trace = None
    remote = '/data/misc/perfetto-traces/muse-' + stamp + '.perfetto-trace'
    try:
        if args.trace:
            try:
                trace = subprocess.Popen(adb + ['shell', 'perfetto', '-o', remote, '-t', str(args.seconds) + 's',
                    '-b', '32mb', '-a', args.package, 'sched', 'freq', 'idle', 'am', 'wm', 'gfx', 'view', 'webview', 'input'],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, **process_options)
            except OSError as error:
                metadata['perfetto_error'] = str(error)
                print('Perfetto unavailable; continuing with window metrics.', file=sys.stderr)
        print('Recording ' + args.label + ': repeat your chosen interaction now.', flush=True)
        for elapsed in range(args.seconds):
            time.sleep(1)
            if (elapsed + 1) % 10 == 0:
                print(str(elapsed + 1) + '/' + str(args.seconds) + ' seconds', flush=True)
        frames = run(['shell', 'dumpsys', 'gfxinfo', args.package, 'framestats'])
        (output / 'frames.txt').write_text(frames, encoding='utf-8')
        (output / 'memory-after.txt').write_text(run(['shell', 'dumpsys', 'meminfo', args.package]), encoding='utf-8')
        (output / 'thermal-after.txt').write_text(run(['shell', 'dumpsys', 'thermalservice'], check=False), encoding='utf-8')
        if trace:
            try:
                stdout, stderr = trace.communicate(timeout=20)
                (output / 'perfetto-status.txt').write_bytes(stdout + stderr)
                metadata['perfetto_exit_code'] = trace.returncode
                if trace.returncode == 0:
                    run(['pull', remote, str(output / 'capture.perfetto-trace')])
                else:
                    metadata['perfetto_error'] = 'Perfetto exited unsuccessfully; see perfetto-status.txt'
            except (RuntimeError, OSError, subprocess.SubprocessError) as error:
                metadata['perfetto_error'] = str(error)
            if 'perfetto_error' in metadata:
                print('Perfetto capture failed; window metrics were still saved.', file=sys.stderr)
        (output / 'metadata.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding='utf-8')
        summary = summarize_frames(frames)
        (output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
        print(json.dumps({'directory': str(output.resolve()), **summary}, indent=2))
    finally:
        if trace and trace.poll() is None:
            trace.terminate()
            trace.communicate(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    recording = commands.add_parser('capture')
    recording.add_argument('--adb')
    recording.add_argument('--serial')
    recording.add_argument('--package', default='com.seekstars.muselauncher.benchmark')
    recording.add_argument('--label', required=True)
    recording.add_argument('--seconds', type=int, default=30, choices=range(5, 61), metavar='5..60')
    recording.add_argument('--output', default='.gradle/performance')
    recording.add_argument('--trace', action='store_true', help='Also request a Perfetto trace; requires device support')
    summary = commands.add_parser('summarize')
    summary.add_argument('frames', type=Path)
    args = parser.parse_args()
    try:
        if args.command == 'capture': capture(args)
        else: print(json.dumps(summarize_frames(args.frames.read_text(encoding='utf-8')), indent=2))
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
