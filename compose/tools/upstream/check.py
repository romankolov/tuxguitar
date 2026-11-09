#!/usr/bin/env python3
"""Run real, pinned alphaTab helpers; check their expected results byte-for-byte.

Only build/test-time sources under an excluded cache directory. The reference JavaScript,
parsers, audio and backend are test-only. The separately attributed Kotlin geometry
adaptation in TGAlphaEffectLayout.kt is shipping MPL-2.0 code.
"""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tarfile
import urllib.request

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
SHA='9f1aa10460df1b326123d28a2222f7b20d081e61'
ARCHIVE_SHA='14e575b8fb49095a043cc5e7fec6a65f836eeb3f48459768683add539a29c6dd'
CACHE=ROOT/'.cache'/'upstream-oracles'
CACHE.mkdir(parents=True,exist_ok=True)
archive=CACHE/'alpha-1.9.0.tar.gz'
source=CACHE/f'alphaTab-{SHA}'/'packages'/'alphatab'/'src'
parser=argparse.ArgumentParser();parser.add_argument('--update',action='store_true');args=parser.parse_args()
if not archive.exists():
    with urllib.request.urlopen(f'https://codeload.github.com/CoderLine/alphaTab/tar.gz/{SHA}',timeout=100) as response:
        with archive.open('wb') as output:
            while data:=response.read(1024*1024):output.write(data)
assert hashlib.sha256(archive.read_bytes()).hexdigest()==ARCHIVE_SHA,'Upstream source archive changed'
if not source.exists():
    prefix=f'alphaTab-{SHA}/packages/alphatab/src/'
    with tarfile.open(archive,'r:gz') as tar:
        # Pinned GitHub archive; explicitly reject links and traversal anyway.
        members=[m for m in tar.getmembers() if m.name.startswith(prefix) and (m.isdir() or m.isfile())]
        for member in members:
            assert '..' not in Path(member.name).parts and not Path(member.name).is_absolute()
        for member in members:
            target=CACHE/member.name
            if member.isdir():target.mkdir(parents=True,exist_ok=True)
            else:
                target.parent.mkdir(parents=True,exist_ok=True)
                with tar.extractfile(member) as data:target.write_bytes(data.read())
bundle=CACHE/'alpha-oracle.cjs'
subprocess.run([str(HERE/'node_modules'/'esbuild'/'bin'/'esbuild'),str(HERE/'alpha_oracle.ts'),
    '--bundle','--platform=node','--target=node22','--format=cjs',f'--alias:@coderline/alphatab={source}',
    f'--alias:@coderline/alphatab/generated/VersionInfo={HERE/"VersionInfo.ts"}',
    '--external:@coderline/alphaskia',f'--outfile={bundle}'],check=True)
result=subprocess.check_output(['node',str(bundle)])
expected=HERE/'alpha-1.9.0.tsv'
if args.update:expected.write_bytes(result)
else:assert expected.read_bytes()==result,'Upstream oracle fixture changed; do not silently update comparison tolerances'
print(f'::notice title=alphaTab source oracles::Pinned {SHA}; {len(result.splitlines())-1} skyline/spring/accidental-column/ranged-paint/grouping/effect-placement/profile-order records verified')
