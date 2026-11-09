#!/usr/bin/env python3
"""Verify the 16 compared native UI/layout sources against pinned TuxGuitar 2.1.1.

They are byte-identical to this repository's Java sources, which are directly
executed by NativeLayoutParityTest. This is not a whole-application parity claim.
Uses the already-authenticated GitHub CLI; never handles or stores credentials.
"""
import base64
import hashlib
import json
from pathlib import Path
import subprocess

here=Path(__file__).resolve().parent
root=here.parents[2]
pin=json.loads((here/'tuxguitar-2.1.1.json').read_text())
for item in pin['files']:
    path=item['path']
    assert hashlib.sha256((root/path).read_bytes()).hexdigest()==item['sha256'],f'Native reference changed: {path}'
    data=json.loads(subprocess.check_output(['gh','api',f"repos/{pin['repository']}/contents/{path}?ref={pin['commit']}"]))
    assert hashlib.sha256(base64.b64decode(data['content'])).hexdigest()==item['sha256'],f'Pinned upstream source changed: {path}'
print(f"::notice title=TuxGuitar source comparison::{pin['release']} {pin['commit']}; {len(pin['files'])} native UI/layout sources byte-identical")
