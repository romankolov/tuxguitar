#!/usr/bin/env python3
"""Small, reassemblable QA previews via Checks API; full PNGs stay in artifacts.

Usage: report_screenshots.py preferred-scene [fallback-scene ...]
One scene is emitted per step. Workflow annotations truncate at 4096 characters, so
split the WebP into independently valid JSON chunks and keep each step below ten
notices. This permits visual review even when the artifact CDN is unavailable.
"""
import base64
import hashlib
from io import BytesIO
import json
from pathlib import Path
import sys
from PIL import Image

CHUNK_SIZE = 3000
MAX_CHUNKS = 8


def preview_chunks(path, name):
    image = Image.open(path).convert('RGB')
    image.thumbnail((480, 640))
    while True:
        buffer = BytesIO()
        image.save(buffer, format='WEBP', quality=70, method=6)
        data = buffer.getvalue()
        encoded = base64.b64encode(data).decode('ascii')
        if len(encoded) <= CHUNK_SIZE * MAX_CHUNKS:
            break
        image.thumbnail((max(1, int(image.width * .8)), max(1, int(image.height * .8))))
    parts = (len(encoded) + CHUNK_SIZE - 1) // CHUNK_SIZE
    for index in range(parts):
        yield dict(name=name, mime='image/webp', part=index + 1, parts=parts,
                   sha256=hashlib.sha256(data).hexdigest(),
                   base64=encoded[index * CHUNK_SIZE:(index + 1) * CHUNK_SIZE])


def main():
    root = Path(__file__).resolve().parents[1] / 'sample-android/build'
    candidates = sys.argv[1:] or ['studio-release', 'studio-home']
    for name in candidates:
        paths = sorted(root.glob(f'device-*/**/{name}.png'))
        if not paths:
            continue
        for chunk in preview_chunks(paths[0], name):
            message = json.dumps(chunk)
            assert len(message) < 4000, 'Preview exceeds the Checks annotation limit'
            print(f'::notice title=UI preview {name} {chunk["part"]}/{chunk["parts"]}::{message}')
        break


if __name__ == '__main__':
    main()
