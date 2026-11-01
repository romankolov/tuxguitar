#!/usr/bin/env python3
"""Expose concise failures through Checks API as well as the normal Actions log/artifact."""
import subprocess
import sys
from pathlib import Path

command = sys.argv[1:] or ['./gradlew', ':tux-compose-core:desktopTest', ':tux-compose-core:assembleRelease', ':demo-tuxguitar:test', ':sample-android:lintDebug', ':sample-android:assembleDebug', ':sample-android:assembleDebugAndroidTest', '--stacktrace', '--console=plain']
result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
text = result.stdout
print(text)
Path('build').mkdir(exist_ok=True)
Path('build/ci.log').write_text(text)
if result.returncode:
    # GitHub annotations truncate at 4096 characters. Do not include normal '> Task' lines.
    filtered = '\n'.join(line for line in text.splitlines() if line.startswith(('e:', 'error:', 'FAILURE:', '* What went wrong:', 'Execution failed', 'Could not', 'Caused by:')) or (' FAILED' in line) or ('failed' in line.lower() and not line.startswith('> Task')))
    details = [('Compiler and task errors', filtered[-18000:]), ('Gradle log tail', text[-22000:])]
    for title, detail in details:
        for offset in range(0, len(detail), 3500):
            escaped = detail[offset:offset+3500].replace('%','%25').replace('\r','%0D').replace('\n','%0A')
            print(f'::error title={title} {offset//3500+1}::{escaped}')
sys.exit(result.returncode)
