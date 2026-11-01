#!/usr/bin/env python3
"""Keep failure diagnostics readable through Checks API when Actions log CDN is unavailable."""
import os
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
    filtered = '\n'.join(line for line in text.splitlines() if line.startswith(('e:', 'error:', 'FAILURE:', '* What went wrong:', '>', 'Execution failed', 'Could not', 'Caused by:')) or ' FAILED' in line or 'failed' in line.lower())
    detail = (filtered + '\n\n' + text[-25000:])[-45000:]
    escaped = detail.replace('%','%25').replace('\r','%0D').replace('\n','%0A')
    print('::error title=Gradle failure diagnostics::' + escaped)
sys.exit(result.returncode)
