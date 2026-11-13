#!/usr/bin/env python3
"""API-24 non-streaming install + the SAME AndroidJUnitRunner, no test filtering/retries.

Android N's install-write pipe intermittently returns EIO for the large bank APK.
Stage/install once with adb --no-streaming, then consume the complete instrumentation
protocol. Missing cases, crashes, errors, failures and skips fail closed.
"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from xml.etree import ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
REPORT=ROOT/'sample-android/build/outputs/androidTest-results/manual'


def parse(output):
    bundle={};key=None;started=set();finished={};expected=None;final=None
    for line in output.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            field=line[len('INSTRUMENTATION_STATUS: '):]
            key,_,value=field.partition('=');bundle[key]=value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code=int(line.rsplit(':',1)[1].strip())
            if 'numtests' in bundle:
                count=int(bundle['numtests'])
                assert expected in (None,count),'Runner changed its declared test count'
                expected=count
            if 'class' in bundle and 'test' in bundle:
                identity=(bundle['class'],bundle['test'])
                if code==1:
                    assert identity not in started,('Duplicate start',identity)
                    started.add(identity)
                elif code<=0:
                    assert identity in started,('Finish without start',identity)
                    assert identity not in finished,('Duplicate finish',identity)
                    finished[identity]=dict(code=code,stack=bundle.get('stack',''))
            bundle={};key=None
        elif line.startswith('INSTRUMENTATION_CODE: '):
            final=int(line.rsplit(':',1)[1].strip())
        elif not line.startswith('INSTRUMENTATION_') and key is not None:
            bundle[key]+='\n'+line
    assert expected is not None and expected>0,'Runner never declared a nonempty test suite'
    assert len(started)==len(finished)==expected, f'Incomplete runner: {len(started)} started / {len(finished)} finished / {expected} declared'
    assert final==-1, f'Instrumentation did not finish normally: {final}'
    return finished


def xml_results(cases):
    root=ET.Element('testsuite',name='AndroidJUnitRunner-API24',tests=str(len(cases)),
        failures=str(sum(v['code']==-2 for v in cases.values())),errors=str(sum(v['code'] not in (0,-2,-3,-4) for v in cases.values())),
        skipped=str(sum(v['code'] in (-3,-4) for v in cases.values())))
    for (cls,name),result in cases.items():
        node=ET.SubElement(root,'testcase',classname=cls,name=name)
        code=result['code']
        if code:
            tag='failure' if code==-2 else 'skipped' if code in (-3,-4) else 'error'
            ET.SubElement(node,tag,message=result['stack'].splitlines()[0] if result['stack'] else f'Runner status {code}').text=result['stack']
    return root


def adb(*args,timeout=60):
    result=subprocess.run(['adb',*args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
    if result.returncode:raise RuntimeError(f'adb {args} failed: '+result.stdout.decode(errors='replace'))
    return result.stdout


def install(path):
    assert path.is_file(), f'Missing APK: {path}'
    output=adb('install','--no-streaming','-r',str(path),timeout=180)
    assert b'Success' in output.splitlines(),output
    return dict(path=path.name,bytes=path.stat().st_size,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                installed=True,transport='non-streaming',deadline_seconds=180)


def main():
    REPORT.mkdir(parents=True,exist_ok=True)
    proofs=[install(ROOT/'sample-android/build/outputs/apk/debug/sample-android-debug.apk'),
            install(ROOT/'sample-android/build/outputs/apk/androidTest/debug/sample-android-debug-androidTest.apk')]
    (REPORT/'install-proof.json').write_text(json.dumps(proofs,indent=2)+'\n')
    output=adb('shell','am','instrument','-w','-r',
        'com.tuxcompose.sample.test/androidx.test.runner.AndroidJUnitRunner',timeout=1200).decode(errors='replace')
    (REPORT/'instrumentation.txt').write_text(output)
    try:
        cases=parse(output)
        source_tests=ROOT/'sample-android/src/androidTest'
        declared=sum(len(re.findall(r'@Test\b',p.read_text())) for p in source_tests.rglob('*.kt'))
        assert len(cases)==declared, f'Runner executed {len(cases)} cases but the source declares {declared}; no filtering is allowed'
        ET.ElementTree(xml_results(cases)).write(REPORT/'TEST-AndroidJUnitRunner.xml',encoding='utf-8',xml_declaration=True)
        assert all(v['code']==0 for v in cases.values()),'Instrumentation has failures/errors/skips; see JUnit report'
        print(f'All {len(cases)} declared AndroidJUnitRunner cases finished successfully; no test filtering or retries.')
    except Exception as error:
        print('::error title=API24 instrumentation protocol::'+str(error).replace('%','%25').replace('\n','%0A'))
        print(output[-12000:])
        raise


if __name__=='__main__':main()
