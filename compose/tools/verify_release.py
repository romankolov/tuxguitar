#!/usr/bin/env python3
"""Fail closed: only a genuinely signed, optimized, non-debuggable release may be published."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
apk = root / 'sample-android/build/outputs/apk/release/sample-android-release.apk'
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
tools = sorted((sdk / 'build-tools').glob('*/apksigner'))[-1].parent

try:
    assert apk.is_file(), 'Signed release APK is absent'
    cert = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    assert 'Android Debug' not in cert, 'Release must not use the Android debug certificate'
    assert 'Tux Compose Evaluation Release' in cert, 'Unexpected release signer'
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    assert 'application-debuggable' not in badging, 'Release is debuggable'
    assert "versionName='0.1.7'" in badging and "versionCode='8'" in badging, 'Unexpected app version'
    mapping = root / 'sample-android/build/outputs/mapping/release/mapping.txt'
    assert mapping.is_file() and mapping.stat().st_size > 1000, 'R8 optimization/mapping missing'
    # DEX names must actually be rewritten, rather than merely producing a keep-all mapping.
    assert any(' -> ' in line and not line.startswith((' ', '#')) and line.split(' -> ')[0] != line.split(' -> ')[1].rstrip(':')
               for line in mapping.read_text().splitlines()), 'No optimized/obfuscated release classes'
    fingerprints = re.findall(r'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})', cert)
    assert len(fingerprints) == 1, 'Expected one release signing certificate SHA-256'
    fingerprint = fingerprints[0].lower()
    summary = dict(apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), version='0.1.7', version_code=8,
                   source_sha=os.environ.get('GITHUB_SHA'), debuggable=False, r8=True, signer_sha256=fingerprint, signing='ephemeral evaluation release')
    (root / 'build').mkdir(exist_ok=True)
    (root / 'build/release-verification.json').write_text(json.dumps(summary, indent=2)+'\n')
    print('::notice title=Release APK verified::'+json.dumps(summary))
except Exception as error:
    print('::error title=Release APK verification::'+str(error).replace('\n','%0A'))
    raise
