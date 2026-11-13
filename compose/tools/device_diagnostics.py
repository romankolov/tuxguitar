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
    raw=log.read_text(errors='replace').splitlines() if log.exists() else []
    hits=[i for i,line in enumerate(raw) if any(key in line.lower() for key in keys)]
    # Preserve exception/message context, not just matching stack-frame names.
    indexes=sorted({j for i in hits[-5:] for j in range(max(0,i-2),min(len(raw),i+5))})
    lines=[raw[i][-260:] for i in indexes][-20:]
    details = dict(data=command('adb', 'shell', 'df', '-k', '/data'), installer_tail=lines,apk_sizes={p.name:p.stat().st_size for p in (root/'sample-android/build/outputs/apk').rglob('*.apk')})
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
