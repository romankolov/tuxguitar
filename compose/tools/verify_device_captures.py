#!/usr/bin/env python3
"""Fail closed if connected-test cleanup lost any required hardware screenshot."""
import hashlib
import json
from pathlib import Path
from PIL import Image


def required_scenes():
    result={f'capsule-{z}' for z in ('1.0','1.5')}
    for style in ('Score','Tablature','Mixed'):
        for zoom in ('1.0','1.5'):
            result.update(f'{prefix}-{style}-{zoom}' for prefix in ('annotations','after','annotation-origins'))
    for style in ('Score','Mixed'):
        for mode in ('TuxGuitar','Adaptive','Spring'):
            for zoom in ('1.0','1.5'):
                result.add(f'engraving-{style}-{mode}-{zoom}')
    result.update(f'pdf-{style}-{page}' for style in ('Score','Tablature','Mixed') for page in ('first','last'))
    result.update(('pdf-range-first','pdf-sheet','pdf-result'))
    return result


def verify(folder, required=None):
    required=required_scenes() if required is None else required
    records={}
    for path in sorted(folder.rglob('*.png')):
        assert path.stem not in records, f'Duplicate screenshot: {path.stem}'
        with Image.open(path) as image:
            assert image.format=='PNG' and min(image.size)>0, f'Invalid PNG: {path}'
            size=list(image.size)
            image.verify()
        records[path.stem]=dict(sha256=hashlib.sha256(path.read_bytes()).hexdigest(),size=size,
                                path=str(path.relative_to(folder)))
    missing=required-records.keys()
    assert not missing, f'Hardware screenshots lost before export: {sorted(missing)}'
    return dict(required=len(required),preserved=len(records),images=records)


def main():
    folder=Path(__file__).resolve().parents[1]/'sample-android/build/device-screenshots'
    report=verify(folder)
    (folder/'capture-manifest.json').write_text(json.dumps(report,indent=2)+'\n')
    print('::notice title=Score and PDF captures preserved::'+json.dumps({k:v for k,v in report.items() if k!='images'}))


if __name__=='__main__':
    main()
