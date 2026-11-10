#!/usr/bin/env python3
"""Offline architectural checks also used by Actions; fails closed on forbidden core dependencies."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
subprocess.run(['python3', str(root / 'tools/port_glyphs.py'), '--check'], check=True)
core = root / 'tux-compose-core'
for file in (core / 'src/commonMain').rglob('*.kt'):
    text = file.read_text()
    if re.search(r'^import (?:android\.|java\.|javax\.|app\.tuxguitar\.|.*(?:midi|player|decoder|synth|audio)\.)', text, re.M | re.I):
        raise SystemExit(f'Forbidden platform/parser/playback import in core: {file}')
build = (core / 'build.gradle.kts').read_text()
if re.search(r'project\(|gervill|fluidsynth|externalNativeBuild|commons-compress|tuxguitar-lib|compose\.material|material3', build):
    raise SystemExit('Core must not depend on demo/upstream runtime')
print('Core platform/parser/playback boundary verified.')

# Keep the independent APK/library version and fail-closed manifest verifier in step.
version=re.search(r'version = "([^" ]+)"',(root/'build.gradle.kts').read_text()).group(1)
app=(root/'sample-android/build.gradle.kts').read_text()
code=re.search(r'versionCode = (\d+)',app).group(1)
assert f'versionName = "{version}"' in app, 'Library/APK versions disagree'
release=(root/'tools/verify_release.py').read_text()
assert f"versionName='{version}'" in release and f"versionCode='{code}'" in release, 'Release verifier version disagrees'
print(f'Library/APK/release-verifier versions agree: {version} (code {code}).')
