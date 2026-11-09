#!/usr/bin/env python3
"""Export all detailed hardware captures in a separate, neutral GitHub Check.

Workflow job annotations have a 50-annotation budget; they used to hide debug
scenes behind release previews. Checks accept batches of 50, so this independent
check retains every required scene without weakening image quality or test gates.
This is an evidence export, NEVER a visual approval or substitute for review.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from report_screenshots import preview_chunks
from verify_device_captures import required_scenes, verify


SOURCE='compose/sample-android/src/androidTest/kotlin/com/tuxcompose/sample/AnnotationsDeviceTest.kt'


def annotations(folder, required=None):
    required=required_scenes() if required is None else required
    manifest=verify(folder,required)
    result=[]
    for name in sorted(required):
        original=manifest['images'][name]
        path=folder/original['path']
        for chunk in preview_chunks(path,name):
            chunk['original_png_sha256']=original['sha256']
            chunk['original_png_size']=original['size']
            result.append(dict(path=SOURCE,start_line=1,end_line=1,annotation_level='notice',
                               title=f'UI preview {name} {chunk["part"]}/{chunk["parts"]}',
                               message=json.dumps(chunk,separators=(',',':'))))
    return manifest,result


def gh(endpoint, method, data):
    response=subprocess.check_output(['gh','api','--method',method,endpoint,'--input','-'],
                                     input=json.dumps(data).encode())
    return json.loads(response)


def main():
    api=int(sys.argv[1]);assert api in (24,35)
    repo=os.environ['GITHUB_REPOSITORY'];sha=os.environ['GITHUB_SHA']
    folder=Path(__file__).resolve().parents[1]/'sample-android/build/device-screenshots'
    manifest,items=annotations(folder)
    title=f'Hardware capture export · API {api}'
    summary=(f'{manifest["required"]} required detailed hardware scenes, {len(items)} preview chunks. '
             'PNG SHA-256 and original dimensions accompany each image. '
             'Export only: this neutral check is NOT visual approval. '
             'All test and explicit publication gates remain separate.')
    check=gh(f'repos/{repo}/check-runs','POST',dict(name=title,head_sha=sha,status='in_progress',
              output=dict(title=title,summary=summary)))
    endpoint=f'repos/{repo}/check-runs/{check["id"]}'
    try:
        for offset in range(0,len(items),50):
            gh(endpoint,'PATCH',dict(output=dict(title=title,summary=summary,annotations=items[offset:offset+50])))
        gh(endpoint,'PATCH',dict(status='completed',conclusion='neutral',output=dict(title=title,summary=summary)))
    except Exception:
        gh(endpoint,'PATCH',dict(status='completed',conclusion='failure',output=dict(title=title,summary='Hardware preview export failed.')))
        raise
    digest=hashlib.sha256(json.dumps(manifest,sort_keys=True).encode()).hexdigest()
    print('::notice title=Detailed hardware previews exported::'+json.dumps(dict(api=api,check_id=check['id'],
          scenes=manifest['required'],chunks=len(items),manifest_sha256=digest,url=check['html_url'])))


if __name__=='__main__':
    main()
