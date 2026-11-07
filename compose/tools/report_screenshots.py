#!/usr/bin/env python3
"""Small QA previews via Checks API; full-resolution images remain in device artifacts.

This lets source-only environments review the actual emulator UI without downloading
an APK or depending on the Actions log/artifact CDN. No user files appear in test scenes.
"""
import base64
from io import BytesIO
import json
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1] / 'sample-android/build'
scenes = ['studio-release', 'studio-release-dark', 'studio-release-wide']
for name in scenes:
    paths = list(root.glob(f'device-*/**/{name}.png'))
    if not paths:
        continue
    image = Image.open(paths[0]).convert('RGB')
    image.thumbnail((480, 640))
    buffer = BytesIO()
    image.save(buffer, format='WEBP', quality=70, method=6)
    message = json.dumps(dict(name=name, mime='image/webp', base64=base64.b64encode(buffer.getvalue()).decode()))
    if len(message) < 60000:
        print('::notice title=UI preview '+name+'::'+message)
