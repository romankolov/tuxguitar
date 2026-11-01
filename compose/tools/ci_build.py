#!/usr/bin/env python3
"""Expose concise failures through Checks API as well as the normal Actions log/artifact."""
import json
import secrets
import subprocess
import sys
from pathlib import Path
from xml.etree import ElementTree

command = sys.argv[1:] or ['./gradlew', ':tux-compose-core:desktopTest', ':tux-compose-core:assembleRelease', ':demo-tuxguitar:test', ':sample-android:lintDebug', ':sample-android:assembleDebug', ':sample-android:assembleDebugAndroidTest', '--stacktrace', '--console=plain', '--continue']
result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
text = result.stdout
# AGP emits its own workflow annotations. Suppress their execution while replaying the
# raw log so one noisy javac failure cannot consume the step's ten-annotation budget.
token = secrets.token_hex(16)
print(f'::stop-commands::{token}')
print(text)
print(f'::{token}::')
Path('build').mkdir(exist_ok=True)
Path('build/ci.log').write_text(text)
reports = []
failures = []
xml_paths = sorted(set(Path('.').glob('**/build/test-results/**/TEST-*.xml')) |
                   set(Path('.').glob('**/build/outputs/androidTest-results/**/TEST-*.xml')))
for path in xml_paths:
    root = ElementTree.parse(path).getroot()
    suites = [root] if root.tag == 'testsuite' else list(root.iter('testsuite'))
    for suite in suites:
        reports.append(dict(path=str(path), name=suite.get('name', ''),
                            **{k: int(suite.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')}))
        for case in suite.findall('testcase'):
            for failure in list(case.findall('failure')) + list(case.findall('error')):
                failures.append(f"{case.get('classname')}.{case.get('name')}: {failure.get('message', '')}\n{(failure.text or '')[:2500]}")
summary = dict(exit_code=result.returncode, reports=reports)
Path('build/ci-summary.json').write_text(json.dumps(summary, indent=2))
if reports:
    totals = {key: sum(r[key] for r in reports) for key in ('tests', 'failures', 'errors', 'skipped')}
    print('::notice title=JUnit test summary::' + json.dumps(totals) + ' — ' + '; '.join(f"{r['name']}: {r['tests']} tests" for r in reports))
if result.returncode:
    # GitHub annotations truncate at 4096 characters. Keep early compiler errors AND
    # the final task failures; --continue lets independent modules report together.
    lines = text.splitlines()
    selected = set()
    for index, line in enumerate(lines):
        if line.startswith(('e:', 'error:', 'FAILURE:', '* What went wrong:', 'Execution failed', 'Could not', 'Caused by:')) or ' FAILED' in line or ': error:' in line or '::error' in line:
            selected.update(range(index, min(index + 3, len(lines))))
    filtered = '\n'.join(failures) + '\n' + '\n'.join(lines[index] for index in sorted(selected))
    details = [('Compiler and task errors', filtered[:17500]), ('Gradle log tail', text[-7000:])]
    for title, detail in details:
        for offset in range(0, len(detail), 3500):
            escaped = detail[offset:offset+3500].replace('%','%25').replace('\r','%0D').replace('\n','%0A')
            print(f'::error title={title} {offset//3500+1}::{escaped}')
sys.exit(result.returncode)
