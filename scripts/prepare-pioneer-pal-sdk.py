#!/usr/bin/env python3
"""Prepare a private, PAL-only JVM JAR for normal Android installation-time dex optimization."""
import argparse
import re
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAL = 'com/tencent/tai/pal/'
PLATFORM = ('java/', 'javax/', 'android/', 'org/json/', 'org/xml/', 'org/w3c/', 'com/google/gson/')


def strings(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Expected JVM class bytecode')
    offset, index = 10, 1
    count = struct.unpack_from('>H', data, 8)[0]
    values = []
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            size = struct.unpack_from('>H', data, offset)[0]
            offset += 2
            values.append(data[offset:offset + size].decode('utf-8', errors='replace'))
            offset += size
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            offset += 2
        elif tag == 15:
            offset += 3
        else:
            raise ValueError(f'Unsupported class constant tag: {tag}')
        index += 1
    return values


def prepare(source, output):
    with zipfile.ZipFile(source) as archive:
        names = [n for n in archive.namelist() if n.endswith('.class')]
        if len(names) != len(set(names)):
            raise ValueError('Duplicate input classes')
        classes = {n[:-6]: archive.read(n) for n in names}
    selected = {n for n in classes if n.startswith(PAL)}
    if not selected:
        raise ValueError('PAL classes missing from supplied SDK')
    pending = list(selected)
    missing = set()
    while pending:
        name = pending.pop()
        refs = set()
        for value in strings(classes[name]):
            if 'd2j fail translate' in value or 'Failed to translate' in value:
                raise ValueError(f'Bytecode conversion failed for {name}')
            refs.update(re.findall(r'L([\w$/]+)(?=[;<])', value))
            if value in classes:
                refs.add(value)
            if value.replace('.', '/') in classes:
                refs.add(value.replace('.', '/'))
        for ref in refs:
            if ref in classes:
                if ref not in selected:
                    selected.add(ref)
                    pending.append(ref)
            elif not ref.startswith(PLATFORM):
                missing.add(ref)
    if missing:
        raise ValueError('Unresolved PAL dependencies: ' + ', '.join(sorted(missing)))
    required = ('client/PALServiceManager', 'client/PALServiceManager$d', 'client/c',
                'client/c$a', 'client/a', 'audio/b', 'input/KeyFilter')
    for name in required:
        if PAL + name not in selected:
            raise ValueError('Unsupported SDK: missing ' + PAL + name)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, suffix='.jar', delete=False) as temporary:
        stage = Path(temporary.name)
    try:
        with zipfile.ZipFile(stage, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name in sorted(selected):
                entry = zipfile.ZipInfo(name + '.class', date_time=(1980, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, classes[name])
        stage.replace(output)
    finally:
        stage.unlink(missing_ok=True)
    print(f'Prepared {len(selected)} PAL/dependency classes ({output.stat().st_size} bytes): {output}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-jar', type=Path, help='Locally supplied SDK JVM JAR (original or dex2jar-converted)')
    parser.add_argument('--dex', type=Path, default=ROOT / '.private/vehicle-runtime-assets/vehicle/pal-client.dex')
    parser.add_argument('--dex2jar-home', type=Path, default=ROOT / '.private/tools/dex-tools-v2.4')
    parser.add_argument('--output', type=Path, default=ROOT / '.private/vehicle-runtime-libs/pal-client.jar')
    args = parser.parse_args()
    if args.input_jar:
        prepare(args.input_jar, args.output)
    else:
        launcher = args.dex2jar_home / 'd2j-dex2jar.sh'
        if not launcher.is_file():
            parser.error('Provision dex-tools v2.4 locally using --dex2jar-home, or supply --input-jar')
        with tempfile.TemporaryDirectory(prefix='diplay-pal-') as directory:
            converted = Path(directory) / 'sdk.jar'
            subprocess.run(['sh', str(launcher), '-f', '-o', str(converted), str(args.dex)], check=True)
            prepare(converted, args.output)


if __name__ == '__main__':
    main()
