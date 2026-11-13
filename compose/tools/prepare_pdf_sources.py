#!/usr/bin/env python3
"""Build-only namespace relocation of checksum-pinned, ORIGINAL TuxGuitar print code.

No algorithm rewriting, parser/player implementation or generated files in Git.
The two Midi* types are name/value descriptors referenced by TGMessagesManager.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[2]
MODULE=ROOT/'compose/tux-compose-pdf'
PREFIX='com.tuxcompose.pdf.internal.tuxguitar'
SOURCE=ROOT/'common/TuxGuitar-lib/src/main/java'

def generate(output,remote=False):
    manifest=json.loads((MODULE/'upstream.json').read_text())
    tree=None
    if remote:
        response=json.loads(subprocess.check_output(['gh','api',f"repos/{manifest['repository']}/git/trees/{manifest['commit']}?recursive=1"]))
        assert not response['truncated'],'Incomplete upstream tree'
        tree={item['path']:item['sha'] for item in response['tree'] if item['type']=='blob'}
    generated=[]
    for item in manifest['files']:
        source=ROOT/item['path'];data=source.read_bytes()
        assert hashlib.sha256(data).hexdigest()==item['sha256'],f'Native print source changed: {source}'
        if tree is not None:
            blob=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
            assert tree[item['path']]==blob,f'Native print differs from pinned upstream: {source}'
        if item['runtime']:
            rel=source.relative_to(SOURCE)
            assert not str(rel).startswith(('app/tuxguitar/io/','app/tuxguitar/thread/'))
            if 'app/tuxguitar/player/' in str(rel):
                assert rel.name in ('MidiInstrument.java','MidiPercussionKey.java')
            target=output/Path(str(rel).replace('app/tuxguitar',PREFIX.replace('.','/'),1))
            target.parent.mkdir(parents=True,exist_ok=True)
            text=data.decode('utf-8').replace('app.tuxguitar',PREFIX)
            assert not any(x in text for x in ('import java.awt.','import android.','app.tuxguitar.io.'))
            if not target.exists() or target.read_text()!=text:target.write_text(text)
            generated.append(target.resolve())
    # Prune only this task's generated Java output, never the repository sources.
    for path in output.rglob('*.java'):
        if path.resolve() not in generated:path.unlink()
    print(f"Verified {len(manifest['files'])} pinned print/backend sources; generated {len(generated)} namespace-only relocated runtime sources.")

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--remote',action='store_true');parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    generate(args.output or MODULE/'build/generated/tuxguitar',args.remote)
