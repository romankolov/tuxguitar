#!/usr/bin/env python3
"""Check the shipped APK/AAR, not just source declarations or a successful native link."""
from pathlib import Path
from zipfile import ZipFile, ZIP_STORED
from io import BytesIO
import hashlib
import json
import struct

root = Path(__file__).resolve().parents[1]
apk = root / 'sample-android/build/outputs/apk/debug/sample-android-debug.apk'
aar = root / 'tux-compose-core/build/outputs/aar/tux-compose-core-release.aar'
expected_bank = '9575028c7a1f589f5770fccc8cff2734566af40cd26ed836944e9a5152688cfe'
abis = {'arm64-v8a': 183, 'armeabi-v7a': 40, 'x86_64': 62, 'x86': 3}
platform = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so',
            'libOpenSLES.so', 'libaaudio.so', 'libz.so', 'libEGL.so', 'libGLESv2.so',
            'libmediandk.so', 'libvulkan.so'}


def elf(data, machine):
    assert data[:4] == b'\x7fELF' and data[5] == 1, 'Expected little-endian Android ELF'
    assert struct.unpack_from('<H', data, 18)[0] == machine, 'ELF architecture disagrees with ABI'
    wide = data[4] == 2
    offset = struct.unpack_from('<Q' if wide else '<I', data, 32 if wide else 28)[0]
    size, count = struct.unpack_from('<HH', data, 54 if wide else 42)
    segments = []
    for i in range(count):
        ph = struct.unpack_from('<IIQQQQQQ' if wide else '<IIIIIIII', data, offset+i*size)
        kind, start, address, length, alignment = (ph[0], ph[2], ph[3], ph[5], ph[7]) if wide else (ph[0], ph[1], ph[2], ph[4], ph[7])
        if kind == 1:
            assert alignment >= 16384 and (start-address) % 16384 == 0, 'Library lacks 16-KiB LOAD alignment'
        segments.append((kind, start, address, length))
    dynamic = next(s for s in segments if s[0] == 2)
    pair, step = ('<qQ', 16) if wide else ('<iI', 8)
    values = [struct.unpack_from(pair, data, i) for i in range(dynamic[1], dynamic[1]+dynamic[3], step)]
    string_address = next(value for key, value in values if key == 5)
    segment = next(s for s in segments if s[0] == 1 and s[2] <= string_address < s[2]+s[3])
    strings = segment[1]+string_address-segment[2]
    needed = []
    for key, value in values:
        if key == 1:
            begin = strings+value
            needed.append(data[begin:data.index(b'\0', begin)].decode('ascii'))
    return needed


summary = {'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'abis': {}}
with ZipFile(apk) as package, apk.open('rb') as raw:
    assert hashlib.sha256(package.read('assets/soundbank/GeneralUser-GS.sf2')).hexdigest() == expected_bank
    for name in ('GeneralUser-GS.txt', 'FluidSynth-LGPL.txt', 'libsndfile-LGPL.txt',
                 'Oboe-Apache.txt', 'FLAC.txt', 'Ogg.txt', 'Vorbis.txt', 'Opus.txt', 'LLVM-runtime.txt'):
        assert len(package.read(f'assets/licenses/{name}')) > 100, f'Missing license: {name}'
    assert not any(b'Lmedia/sound/SoftSynthesizer;' in package.read(n) for n in package.namelist() if n.endswith('.dex')), 'Legacy Gervill still packaged'
    for abi, machine in abis.items():
        libs = {Path(n).name: n for n in package.namelist() if n.startswith(f'lib/{abi}/') and n.endswith('.so')}
        assert {'libtuxfluidsynth.so', 'libfluidsynth.so', 'libc++_shared.so'} <= libs.keys(), f'Missing synth ABI {abi}'
        checked = {}
        # Check SDK/JNI libraries; unrelated AndroidX native libraries are outside this verifier.
        for name, path in libs.items():
            if name.startswith('libandroidx'):
                continue
            dependencies = elf(package.read(path), machine)
            assert set(dependencies) <= libs.keys() | platform, f'{abi}/{name}: missing DT_NEEDED {set(dependencies)-libs.keys()-platform}'
            entry = package.getinfo(path)
            if entry.compress_type == ZIP_STORED:
                raw.seek(entry.header_offset+26)
                filename, extra = struct.unpack('<HH', raw.read(4))
                assert (entry.header_offset+30+filename+extra) % 16384 == 0, f'APK ZIP alignment: {path}'
            checked[name] = dependencies
        summary['abis'][abi] = checked
with ZipFile(aar) as core:
    assert not any(n.startswith(('jni/', 'assets/soundbank/')) for n in core.namelist()), 'Audio payload leaked into core AAR'
    with ZipFile(BytesIO(core.read('classes.jar'))) as classes:
        assert not any('/demo/' in n or '/synth/' in n or '/midi/' in n for n in classes.namelist()), 'Demo audio classes leaked into core'
summary['soundfont_sha256'] = expected_bank
summary['core_has_audio_payload'] = False
(root / 'build').mkdir(exist_ok=True)
(root / 'build/synth-verification.json').write_text(json.dumps(summary, indent=2)+'\n')
print('Verified full-bank checksum, four synth ABIs, native dependencies/16-KiB alignment, licenses and audio-free core AAR.')
