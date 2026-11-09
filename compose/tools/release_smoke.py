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
captures = {}


def adb(*args, check=True, timeout=60):
    return subprocess.run(['adb', *args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          timeout=timeout, check=check).stdout


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


def wait_node(predicate=None, **kwargs):
    deadline = time.monotonic()+65
    last = None
    while time.monotonic() < deadline:
        try:
            node = find(tree(), **kwargs)
            if node is not None and (predicate is None or predicate(node)):
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


def wait_gone(**kwargs):
    deadline = time.monotonic()+65
    while time.monotonic() < deadline:
        try:
            if find(tree(), **kwargs) is None:
                return
        except (ET.ParseError, subprocess.CalledProcessError):
            pass
        time.sleep(.15)
    raise AssertionError(f'Release overlay did not close: {kwargs}')


def wait_workspace():
    deadline = time.monotonic()+65
    while time.monotonic() < deadline:
        try:
            doc = tree()
            if find(doc, tag='score-canvas') is not None and all(find(doc, tag=tag) is None
                    for tag in ('cursor-tick', 'settings-close', 'sections-close')):
                return
        except (ET.ParseError, subprocess.CalledProcessError):
            pass
        time.sleep(.15)
    raise AssertionError('Workspace is still covered by an overlay')


def screenshot(name, settled=True):
    if settled and name != 'studio-release-settings':
        wait_workspace()
    # A successful adb tap is not proof that Compose has committed the new frame.
    # Require two identical ACTUAL display PNGs, also waiting out scroll flings and
    # shape/theme transitions. There is no synthetic redraw or skipped visual case.
    deadline = time.monotonic()+20
    previous = None
    attempts = 0
    while True:
        data = adb('exec-out', 'screencap', '-p')
        assert data.startswith(b'\x89PNG\r\n\x1a\n'), 'Invalid display screenshot'
        digest = hashlib.sha256(data).hexdigest()
        attempts += 1
        if not settled or digest == previous:
            (out / (name+'.png')).write_bytes(data)
            captures[name] = dict(sha256=digest, captures=attempts, settled=settled)
            return
        if time.monotonic() >= deadline:
            (out / (name+'-unstable.png')).write_bytes(data)
            raise AssertionError('Display did not settle for screenshot: '+name)
        previous = digest
        time.sleep(.15)


def tick():
    click(tag='more'); click(text='Score details')
    doc = tree()
    values = [int(n.get('text', '')[5:]) for n in doc.iter('node') if re.fullmatch(r'Tick \d+', n.get('text', ''))]
    assert len(values) == 1, ET.tostring(doc).decode()[:500]
    click(text='Done')
    wait_gone(tag='cursor-tick')
    return values[0]


def launch_and_controls():
    adb('shell', 'am', 'start', '-W', '-n', 'com.tuxcompose.sample/.MainActivity')
    wait_node(tag='next')
    # Main content is not ready until the real lossless TG decoder completes.
    deadline = time.monotonic()+65
    while wait_node(tag='next').get('enabled') != 'true':
        assert time.monotonic() < deadline, 'Release TG import did not finish'
        time.sleep(.3)
    assert adb('shell', 'run-as', 'com.tuxcompose.sample', 'id', check=False).find(b'not debuggable') >= 0
    assert tick() == 960
    click(tag='next'); assert tick() == 1440
    click(tag='previous'); assert tick() == 960
    click(tag='mode-Tablature')
    assert wait_node(tag='mode-Tablature').get('checked') == 'true'
    click(tag='mode-Mixed')
    wait_node(tag='mode-Mixed', predicate=lambda node: node.get('checked') == 'true')
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


def section(tag):
    click(tag='section-picker')
    # Lazy section rows are reachable by ordinary touch, including on API 24.
    for _ in range(8):
        doc=tree()
        node=find(doc,tag=tag)
        if node is not None:
            tap(node)
            wait_gone(tag='sections-close')
            return
        listing=find(doc,tag='sections-list')
        assert listing is not None, 'Missing section list'
        x1,y1,x2,y2=map(int,re.findall(r'\d+',listing.get('bounds','')))
        adb('shell','input','swipe',str((x1+x2)//2),str(y2-20),str((x1+x2)//2),str(y1+20),'300')
    raise AssertionError('Section could not be reached: '+tag)


def showcase_sections():
    section('section-4'); assert tick()==16320
    screenshot('studio-release-articulation')
    section('section-12'); assert tick()==47040
    screenshot('studio-release-rhythm')
    section('section-16'); assert tick()==62400
    screenshot('studio-release-voices')
    section('section-28'); assert tick()==107520
    canvas=wait_node(tag='score-canvas')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',canvas.get('bounds','')))
    for _ in range(2):
        adb('shell','input','swipe',str((x1+x2)//2),str(y2-20),str((x1+x2)//2),str(y1+20),'450')
    screenshot('studio-release-dense')
    click(tag='stop'); assert tick()==960


def theme_and_cursor():
    click(tag='settings')
    click(tag='settings-Cursor')
    assert wait_node(tag='cursor-Capsule').get('checked') == 'true'
    assert any('Inner margin' in n.get('text', '') for n in tree().iter('node'))
    click(tag='settings-Appearance'); click(tag='theme-Dark')
    wait_node(tag='theme-Dark', predicate=lambda node: node.get('checked') == 'true')
    screenshot('studio-release-settings')
    click(tag='settings-close')
    screenshot('studio-release-dark')


def adaptive_windows():
    adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0')
    wait_node(tag='next')
    screenshot('studio-release-large-text')
    click(tag='settings'); wait_node(tag='settings-close'); click(tag='settings-close')
    adb('shell','wm','size','960x1920')
    adb('shell','wm','density','480')  # genuine 320dp-wide / 200% text window
    wait_node(tag='studio-root', predicate=lambda node: list(map(int,re.findall(r'\d+',node.get('bounds',''))))[2] == 960)
    canvas=wait_node(tag='score-canvas')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',canvas.get('bounds','')))
    assert x2-x1>=250*3 and y2-y1>=96*3, 'Narrow large-text controls crowded out the music'
    for tag in ('open-file','settings','play','previous','next','follow-cursor'):
        target=wait_node(tag=tag)
        a,b,c,d=map(int,re.findall(r'\d+',target.get('bounds','')))
        assert c-a>=48*3-1 and d-b>=48*3-1, 'A primary target shrank: '+tag
    screenshot('studio-release-narrow-large-text')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    adb('shell', 'wm', 'size', '1280x800')
    adb('shell', 'wm', 'density', '160')
    wait_node(tag='wide-controls')
    click(tag='wide-settings'); click(tag='settings-close')
    screenshot('studio-release-wide')



def install_verified_release(apk, proof):
    assert hashlib.sha256(apk.read_bytes()).hexdigest()==proof['apk_sha256'], 'Release artifact changed after verification'
    adb('uninstall', 'com.tuxcompose.sample.test', check=False)
    adb('uninstall', 'com.tuxcompose.sample', check=False)
    started=time.monotonic()
    # A 95 MB APK on the API-24 emulator exceeded the old generic 60-second
    # command budget. Give this ONE install a bounded three minutes; no retry,
    # suppressed error or relaxed smoke assertion. UI/capture budgets stay unchanged.
    output=adb('install',str(apk),timeout=180)
    assert b'Success' in output.splitlines(), f'Installer did not confirm success: {output!r}'
    info=adb('shell','dumpsys','package','com.tuxcompose.sample').decode(errors='replace')
    assert re.search(r'\bversionName='+re.escape(proof['version'])+r'(?:\s|$)',info), 'Installed release version name differs'
    assert re.search(r'\bversionCode='+str(proof['version_code'])+r'\b',info), 'Installed release version code differs'
    installed=dict(apk_sha256=proof['apk_sha256'],version=proof['version'],version_code=proof['version_code'],
                   installed=True,install_deadline_seconds=180,elapsed_seconds=round(time.monotonic()-started,3))
    (report/'release-installation.json').write_text(json.dumps(installed,indent=2)+'\n')
    # Do not label a verified INPUT artifact as installed before installation succeeds.
    print('::notice title=Exact release APK installed::'+proof['apk_sha256'])


def main():
    failed = False
    try:
        apks = list((root / 'verified-artifacts').rglob('sample-android-release.apk'))
        assert len(apks) == 1, f'Expected the one build-job release artifact, found {apks}'
        proofs=list((root / 'verified-artifacts').rglob('release-verification.json'))
        assert len(proofs)==1, 'Missing build-job release verification'
        proof=json.loads(proofs[0].read_text())
        install_verified_release(apks[0],proof)
        for name, action in [('release_launch_import_navigation', launch_and_controls),
                             ('release_fluidsynth_playback', playback),
                             ('release_lossless_showcase_and_sections', showcase_sections),
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
            screenshot('release-final-state', settled=False)
        for command in [('shell','wm','size','reset'), ('shell','wm','density','reset'),
                        ('shell','settings','put','system','font_scale','1.0')]:
            with contextlib.suppress(Exception):
                adb(*command, check=False)
        tests = len(suite.findall('testcase'))
        failures = len(suite.findall('./testcase/failure'))
        suite.set('tests', str(tests)); suite.set('failures', str(failures)); suite.set('errors','0'); suite.set('skipped','0')
        ET.ElementTree(suite).write(report / 'TEST-ReleaseApkSmoke.xml', encoding='utf-8', xml_declaration=True)
        (report / 'scene-captures.json').write_text(json.dumps(captures, indent=2)+'\n')
        print('::notice title=Release smoke summary::'+json.dumps(dict(tests=tests, failures=failures, errors=0, skipped=0, settled_scenes=sum(c['settled'] for c in captures.values()))))
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
