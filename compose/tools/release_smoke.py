#!/usr/bin/env python3
"""Black-box checks on the EXACT published, R8-optimized release APK (no debug hooks).

The debug instrumentation suite checks detailed pixels/PCM. This separately replaces that
app with the release artifact and drives its public accessibility tree through adb.
"""
import contextlib
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from xml.etree import ElementTree as ET

root = Path(__file__).resolve().parents[1]
out = root / 'sample-android/build/device-release-screenshots'
out.mkdir(parents=True, exist_ok=True)
report = root / 'sample-android/build/release-smoke'
report.mkdir(parents=True, exist_ok=True)
suite = ET.Element('testsuite', name='ReleaseApkSmoke')


def adb(*args, check=True):
    return subprocess.run(['adb', *args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          timeout=60, check=check).stdout


def tree():
    adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/tux-window.xml')
    data = adb('shell', 'cat', '/sdcard/tux-window.xml')
    (report / 'last-window.xml').write_bytes(data)
    return ET.fromstring(data)


def find(doc, tag=None, text=None, description=None):
    for node in doc.iter('node'):
        rid = node.get('resource-id', '')
        if tag is not None and not (rid == tag or rid.endswith(':id/'+tag)):
            continue
        if text is not None and node.get('text') != text:
            continue
        if description is not None and node.get('content-desc') != description:
            continue
        return node
    return None


def wait_node(**kwargs):
    deadline = time.monotonic()+65
    last = None
    while time.monotonic() < deadline:
        try:
            node = find(tree(), **kwargs)
            if node is not None:
                return node
        except (ET.ParseError, subprocess.CalledProcessError) as error:
            last = error
        time.sleep(.3)
    raise AssertionError(f'Visible release node not found: {kwargs}; {last}')


def tap(node):
    values = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    assert len(values) == 4, node.attrib
    x1, y1, x2, y2 = values
    assert x2 > x1 and y2 > y1, node.attrib
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


def click(**kwargs):
    node = wait_node(**kwargs)
    assert node.get('enabled') == 'true', node.attrib
    tap(node)


def screenshot(name):
    (out / (name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p'))


def tick():
    click(tag='more'); click(text='Score details')
    doc = tree()
    values = [int(n.get('text', '')[5:]) for n in doc.iter('node') if re.fullmatch(r'Tick \d+', n.get('text', ''))]
    assert len(values) == 1, ET.tostring(doc).decode()[:500]
    click(text='Done')
    return values[0]


def launch_and_controls():
    adb('shell', 'am', 'start', '-W', '-n', 'com.tuxcompose.sample/.MainActivity')
    wait_node(tag='next')
    # Main content is not ready until GP parsing completes.
    deadline = time.monotonic()+65
    while wait_node(tag='next').get('enabled') != 'true':
        assert time.monotonic() < deadline, 'Release GP import did not finish'
        time.sleep(.3)
    assert adb('shell', 'run-as', 'com.tuxcompose.sample', 'id', check=False).find(b'not debuggable') >= 0
    assert tick() == 960
    click(tag='next'); assert tick() == 1440
    click(tag='previous'); assert tick() == 960
    click(tag='mode-Tablature')
    assert wait_node(tag='mode-Tablature').get('checked') == 'true'
    click(tag='mode-Mixed')
    screenshot('studio-release')


def playback():
    click(tag='play')
    wait_node(description='Pause')
    time.sleep(2.8)
    click(tag='play')
    wait_node(description='Play')
    assert find(tree(), tag='error') is None, 'Release audio reported an error'
    assert tick() > 960, 'R8 release playback did not move the cursor'
    click(tag='stop'); assert tick() == 960


def theme_and_cursor():
    click(tag='settings')
    click(tag='settings-Cursor')
    assert wait_node(tag='cursor-Capsule').get('checked') == 'true'
    assert any('Inner margin' in n.get('text', '') for n in tree().iter('node'))
    click(tag='settings-Appearance'); click(tag='theme-Dark')
    screenshot('studio-release-settings')
    click(tag='settings-close')
    screenshot('studio-release-dark')


def adaptive_windows():
    adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0')
    wait_node(tag='next')
    screenshot('studio-release-large-text')
    click(tag='settings'); wait_node(tag='settings-close'); click(tag='settings-close')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    adb('shell', 'wm', 'size', '1280x800')
    adb('shell', 'wm', 'density', '160')
    wait_node(tag='wide-controls')
    click(tag='wide-settings'); click(tag='settings-close')
    screenshot('studio-release-wide')


failed = False
try:
    apks = list((root / 'verified-artifacts').rglob('sample-android-release.apk'))
    assert len(apks) == 1, f'Expected the one build-job release artifact, found {apks}'
    proofs=list((root / 'verified-artifacts').rglob('release-verification.json'))
    assert len(proofs)==1, 'Missing build-job release verification'
    proof=json.loads(proofs[0].read_text())
    assert hashlib.sha256(apks[0].read_bytes()).hexdigest()==proof['apk_sha256'], 'Release artifact changed after verification'
    print('::notice title=Exact release APK installed::'+proof['apk_sha256'])
    adb('uninstall', 'com.tuxcompose.sample.test', check=False)
    adb('uninstall', 'com.tuxcompose.sample', check=False)
    adb('install', str(apks[0]))
    for name, action in [('release_launch_import_navigation', launch_and_controls),
                         ('release_fluidsynth_playback', playback),
                         ('release_expressive_theme_and_padded_capsule', theme_and_cursor),
                         ('release_large_text_and_expanded_window', adaptive_windows)]:
        case = ET.SubElement(suite, 'testcase', classname='ReleaseApkSmoke', name=name)
        started = time.monotonic()
        try:
            action()
        except Exception as error:
            ET.SubElement(case, 'failure', message=str(error)).text = repr(error)
            print(f'::error title=Release APK smoke test::{name}: {str(error)[:2500]}')
            failed = True
            break
        finally:
            case.set('time', str(round(time.monotonic()-started, 3)))
except Exception as error:
    case = ET.SubElement(suite, 'testcase', classname='ReleaseApkSmoke', name='release_install')
    ET.SubElement(case, 'failure', message=str(error)).text = repr(error)
    print(f'::error title=Release APK smoke test::{error}')
    failed = True
finally:
    with contextlib.suppress(Exception):
        screenshot('release-final-state')
    for command in [('shell','wm','size','reset'), ('shell','wm','density','reset'),
                    ('shell','settings','put','system','font_scale','1.0')]:
        with contextlib.suppress(Exception):
            adb(*command, check=False)
    tests = len(suite.findall('testcase'))
    failures = len(suite.findall('./testcase/failure'))
    suite.set('tests', str(tests)); suite.set('failures', str(failures)); suite.set('errors','0'); suite.set('skipped','0')
    ET.ElementTree(suite).write(report / 'TEST-ReleaseApkSmoke.xml', encoding='utf-8', xml_declaration=True)
    print('::notice title=Release smoke summary::'+json.dumps(dict(tests=tests, failures=failures, errors=0, skipped=0)))
sys.exit(1 if failed else 0)
