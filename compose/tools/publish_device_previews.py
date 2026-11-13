#!/usr/bin/env python3
"""Export real score/PDF/release captures in a separate, neutral GitHub Check.

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
import time
from report_screenshots import preview_chunks
from verify_device_captures import required_scenes, verify


SOURCE='compose/sample-android/src/androidTest/kotlin/com/tuxcompose/sample/AnnotationsDeviceTest.kt'
PDF_SOURCE='compose/sample-android/src/androidTest/kotlin/com/tuxcompose/sample/PdfExportDeviceTest.kt'
RELEASE_SOURCE='compose/tools/release_smoke.py'
RELEASE_SCENES={'studio-release','studio-release-dark','studio-release-large-text',
    'studio-release-narrow-large-text','studio-release-wide','studio-release-settings',
    'studio-release-articulation','studio-release-rhythm','studio-release-voices','studio-release-dense',
    'studio-release-pdf-options','studio-release-pdf-saved'}


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
            pdf_page=name.startswith('pdf-') and name not in ('pdf-sheet','pdf-result')
            chunk['capture_kind']='pdf-rendered-page' if pdf_page else 'hardware-display'
            if pdf_page:
                pdf_name='pdf-range.pdf' if name=='pdf-range-first' else '-'.join(name.split('-')[:2])+'.pdf'
                pdf=folder/pdf_name
                assert pdf.is_file(), f'PDF page preview has no source document: {name}'
                chunk['original_pdf_name']=pdf_name
                chunk['original_pdf_sha256']=hashlib.sha256(pdf.read_bytes()).hexdigest()
            message=json.dumps(chunk,separators=(',',':'))
            assert len(message)<4000, 'Preview provenance exceeded safe annotation budget'
            path=RELEASE_SOURCE if name.startswith('studio-release') else PDF_SOURCE if name.startswith('pdf-') else SOURCE
            result.append(dict(path=path,start_line=1,end_line=1,annotation_level='notice',
                               title=f'UI preview {name} {chunk["part"]}/{chunk["parts"]}',
                               message=message))
    return manifest,result


def gh(endpoint, method, data):
    payload=json.dumps(data).encode()
    for attempt in range(3):
        response=subprocess.run(['gh','api','--method',method,endpoint,'--input','-'],
                                input=payload,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        error=response.stderr.decode(errors='replace')
        # GitHub may briefly return 404 when PATCH follows check creation. A 404
        # did not append annotations, so retrying THIS request cannot duplicate them.
        # Never retry POST, tests, assertion failures or ambiguous/other HTTP errors.
        if response.returncode and method=='PATCH' and '/check-runs/' in endpoint and 'HTTP 404' in error and attempt<2:
            print(f'Waiting for newly created capture Check ({attempt+1}/2): {endpoint}',flush=True)
            time.sleep(attempt+1)
            continue
        if response.returncode:
            raise RuntimeError(f'GitHub {method} {endpoint} exited {response.returncode}: '+error[:1800])
        try:
            return json.loads(response.stdout)
        except ValueError as error:
            raise RuntimeError(f'GitHub {method} {endpoint} returned invalid JSON: '+
                               response.stdout.decode(errors='replace')[:300]) from error


def workflow_error(title,error):
    message=str(error).replace('%','%25').replace('\r','%0D').replace('\n','%0A')
    print(f'::error title={title}::{message}',flush=True)


def main():
    api=int(sys.argv[1]);assert api in (24,35)
    repo=os.environ['GITHUB_REPOSITORY'];sha=os.environ['GITHUB_SHA']
    folder=Path(__file__).resolve().parents[1]/'sample-android/build/device-screenshots'
    manifest,items=annotations(folder)
    release_folder=folder.parent/'device-release-screenshots'
    release,release_items=annotations(release_folder,RELEASE_SCENES)
    items+=release_items
    manifest=dict(required=manifest['required']+release['required'],score_and_pdf=manifest,release=release)
    title=f'Score and PDF capture export · API {api}'
    summary=(f'{manifest["required"]} required score/PDF/release scenes, {len(items)} preview chunks. '
             'PNG SHA-256 and original dimensions accompany each image. '
             'Export only: this neutral check is NOT visual approval. '
             'All test and explicit publication gates remain separate.')
    check=None
    endpoint=None
    try:
        check=gh(f'repos/{repo}/check-runs','POST',dict(name=title,head_sha=sha,status='in_progress',
                  external_id=f'{os.environ.get("GITHUB_RUN_ID","local")}:{os.environ.get("GITHUB_RUN_ATTEMPT","1")}:{api}',
                  output=dict(title=title,summary=summary)))
        endpoint=f'repos/{repo}/check-runs/{check["id"]}'
        for offset in range(0,len(items),50):
            gh(endpoint,'PATCH',dict(output=dict(title=title,summary=summary,annotations=items[offset:offset+50])))
        gh(endpoint,'PATCH',dict(status='completed',conclusion='neutral',output=dict(title=title,summary=summary)))
    except Exception as error:
        # Report the ORIGINAL failure through Checks even when log/artifact download
        # is unavailable. A failed cleanup must not hide that original diagnosis.
        workflow_error('Complete capture export failed',error)
        if endpoint is not None:
            try:
                gh(endpoint,'PATCH',dict(status='completed',conclusion='failure',
                    output=dict(title=title,summary='Hardware preview export failed: '+str(error)[:1800])))
            except Exception as cleanup:
                workflow_error('Complete capture export cleanup failed',cleanup)
        raise
    digest=hashlib.sha256(json.dumps(manifest,sort_keys=True).encode()).hexdigest()
    print('::notice title=Complete score and PDF previews exported::'+json.dumps(dict(api=api,check_id=check['id'],
          scenes=manifest['required'],chunks=len(items),manifest_sha256=digest,url=check['html_url'])))


if __name__=='__main__':
    main()
