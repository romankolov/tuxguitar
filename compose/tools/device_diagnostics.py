#!/usr/bin/env python3
"""Bounded, Checks-visible install diagnostics; never retry or suppress test failures."""
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
out = root / 'sample-android/build'
out.mkdir(parents=True, exist_ok=True)


def command(*args):
    try:
        result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=45)
        return dict(exit_code=result.returncode, output=result.stdout.strip()[-650:])
    except subprocess.TimeoutExpired:
        return dict(exit_code=124, output='Timed out after 45 seconds')


if sys.argv[1:] == ['failure']:
    log = out / 'device-logcat.txt'
    keys = ('packageinstaller', 'packagemanager', 'installd', 'install_failed',
            'install-write', 'insufficient', 'no space left')
    lines = [line[-200:] for line in log.read_text(errors='replace').splitlines()
             if any(key in line.lower() for key in keys)][-12:] if log.exists() else []
    details = dict(data=command('adb', 'shell', 'df', '-k', '/data'), installer_tail=lines)
    (out / 'device-install-diagnostics.json').write_text(json.dumps(details, indent=2))
    print('::notice title=Android install diagnostics::' + json.dumps(details).replace('%', '%25'))
else:
    details = dict(sdk=command('adb', 'shell', 'getprop', 'ro.build.version.sdk'),
                   boot=command('adb', 'shell', 'getprop', 'sys.boot_completed'),
                   package_manager=command('adb', 'shell', 'pm', 'path', 'android'),
                   data=command('adb', 'shell', 'df', '-k', '/data'),
                   host=command('df', '-h', str(root)))
    (out / 'device-environment.json').write_text(json.dumps(details, indent=2))
    print('::notice title=Android device environment::' + json.dumps(details).replace('%', '%25'))
    if details['boot']['output'] != '1' or details['package_manager']['exit_code']:
        raise SystemExit('Android boot/package manager is not ready; no tests started')
