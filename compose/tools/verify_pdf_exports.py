#!/usr/bin/env python3
"""Validate actual exported PDFs pulled from the emulator, not a renderer screenshot."""
import hashlib
import json
from pathlib import Path
from pypdf import PdfReader

REQUIRED={'pdf-Score.pdf','pdf-Tablature.pdf','pdf-Mixed.pdf','pdf-range.pdf','pdf-ui-export.pdf'}

def verify(folder,required=REQUIRED):
    records={}
    for name in sorted(required):
        path=folder/name
        assert path.is_file(),f'Generated PDF lost: {name}'
        reader=PdfReader(path,strict=True)
        assert not reader.is_encrypted and len(reader.pages)>0,name
        fonts=set();sizes=[];texts=[]
        for index,page in enumerate(reader.pages):
            sizes.append([float(page.mediabox.width),float(page.mediabox.height)])
            resources=page['/Resources'].get_object()
            assert not resources.get('/XObject'),f'Raster/form score XObject found: {name}'
            for ref in resources.get('/Font',{}).get_object().values():
                fonts.add(str(ref.get_object()['/BaseFont']))
            text=page.extract_text()
            assert text.rstrip().endswith(str(index+1)),f'Native global page footer missing in {name} page {index+1}'
            texts.append(text)
        text='\n'.join(texts)
        assert 'Technique Atlas' in text,(name,'wrong score')
        assert '/Times-Roman' in fonts,(name,fonts)
        if name=='pdf-range.pdf':
            assert abs(sizes[0][0]-595.2756)<.02 and abs(sizes[0][1]-841.8898)<.02,sizes
            assert 'Track Fingered bass' in text and 'Track Acoustic guitar' not in text
        else:
            assert sizes[0]==[550.0,800.0],(name,sizes)
        if name in ('pdf-Score.pdf','pdf-Tablature.pdf','pdf-Mixed.pdf'):
            assert all('Track '+part in text for part in ('Acoustic guitar','Fingered bass','Studio drums')),name
        records[name]=dict(sha256=hashlib.sha256(path.read_bytes()).hexdigest(),bytes=path.stat().st_size,
                           pages=len(reader.pages),sizes=sizes,fonts=sorted(fonts))
    return records

if __name__=='__main__':
    folder=Path(__file__).resolve().parents[1]/'sample-android/build/device-screenshots'
    records=verify(folder)
    (folder/'pdf-manifest.json').write_text(json.dumps(records,indent=2)+'\n')
    print('::notice title=Native vector PDFs verified::'+json.dumps(dict(files=len(records),
        pages={k:v['pages'] for k,v in records.items()},raster_score_images=False)))
