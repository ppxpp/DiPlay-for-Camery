#!/usr/bin/env python3
"""Read-only assertions against extracted, user-supplied ROM ELFs; no binaries bundled.
Usage: python3 bluetooth-probe/tools/verify_pioneer_firmware.py /path/to/extracted/libs
Assertions are reproducible anchors, not a substitute for reading the disassembly.
"""
import argparse
import hashlib
import json
from pathlib import Path
from elf32_evidence import Elf

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('samples', type=Path)
args = parser.parse_args()
checks = []
def check(name, condition):
    if not condition:
        raise AssertionError(name)
    checks.append(name)
def load(name):
    return Elf(str(args.samples/name))
def pointer(elf, address):
    info = elf.relocations().get(address, 0)
    return elf.word(address) + (elf.symbols[info >> 8][1] if info >> 8 else 0)

bf = load('blueforcemanage'); client = load('libblueforce.so')
bp = load('libbluep.so'); ios = load('libiosmgr.so'); stack = load('libbt_stack.so')
ui = load('libiPodiUI_dio.so')
check('client library matches JNI gate', hashlib.sha256(client.d).hexdigest() ==
      '7143ccf70ebb575cbec561bc25964fddc806ed3cbe913e98a04206bf48c6658e')
for offset, symbol in [(8, '4initEi'), (16, '14registerNotify'), (20, '16unregisterNotify'),
                       (0x21c, '19serialportWriteData'), (0x230, '26serialportGetDeviceAddress')]:
    resolved = pointer(client, 0x33234+offset)
    name = client.sym.get(resolved & ~1, '')
    # Exported spelling includes decimal name length; assert semantic suffix too.
    check(f'client vtable +{offset:#x}', symbol in name)
check('profile selector vtable', bf.word(0x226284+0x40) == 0x19ef2d)
check('profile masks index0..4', bf.read(0x216420, 20) == bytes.fromhex(
    'f0000000 10000000 20000000 40000000 80000000'))
check('real serial connect vtable', bf.word(0x225ba0+0x30) == 0x18fd85)
check('serial write vtable', bf.word(0x225ba0+0x14) == 0x18f4d9)
check('UUID initialization array', bf.word(0x10b60)+0x10a2c+4 == 0x22b168)
check('UUID used by connect', bf.word(0x190120)+0x190022+4 == 0x22b168)
check('UUID first element', bf.string(bf.word(0x10b64)+0x10a2a+4) ==
      '00000000DECAFADEDECADEAFDECACAFE')
got=bp.word(0x6de64)+0x6de2e+4; vt=pointer(bp,got)
check('serial object startConnection slot', 'startConnection' in bp.sym.get(pointer(bp,vt+16)&~1,''))
check('IOSMgr RX function is 44 bytes', any(n.endswith('15onBTReceiveDataEPhj') and v==0x3b91d and size==44
      for n,v,size,_ in ios.symbols))
check('stack extended UUID request implemented', any(n=='BtCmSppExtendUuidConReq' and v==0x7d544
      for n,v,_,_ in stack.symbols))
check('stack accepts UUID length16', stack.read(0x278094,4)==bytes.fromhex('100053e3'))
check('RFCOMM security literal', stack.word(0x20f510)==0x2030)
check('UI SetConnectType type2 comparison', ui.read(0x66ae,2)==bytes.fromhex('022d'))
check('UI type2 returns0', ui.read(0x6708,10)==bytes.fromhex('14bf4ff0ff300020b3e1'))
print(json.dumps({'checks_passed': len(checks), 'checks': checks,
 'sha256': {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in args.samples.iterdir() if p.is_file()},
 'scope': 'Static code anchors only; does not claim on-device success.'}, ensure_ascii=False, indent=2))
