#!/usr/bin/env python3
"""Standalone PAL SDK preparation tests; all archives and class bytes are synthetic."""
import contextlib
import io
import re
import runpy
import struct
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'prepare-pioneer-pal-sdk.py'
PREPARE = runpy.run_path(str(SCRIPT))['prepare']
PAL = 'com/tencent/tai/pal/'
REQUIRED = (
    'client/PALServiceManager', 'client/PALServiceManager$d', 'client/c',
    'client/c$a', 'client/a', 'audio/b', 'input/KeyFilter',
)


def class_bytes(name, *references):
    """Minimal JVM class with UTF8 constants for descriptors/reflection names.

    It has a normal class header, this/super Class constants, and no members.
    The preparer reads the constant pool, so no executable methods are needed.
    """
    def utf8(value):
        encoded = value.encode('utf-8')
        return b'\x01' + struct.pack('>H', len(encoded)) + encoded

    pool = [utf8(name), b'\x07\x00\x01', utf8('java/lang/Object'), b'\x07\x00\x03']
    pool.extend(utf8(reference) for reference in references)
    header = b'\xca\xfe\xba\xbe' + struct.pack('>HHH', 0, 52, len(pool) + 1)
    # access_flags, this_class, super_class, interfaces/fields/methods/attributes counts
    return header + b''.join(pool) + struct.pack('>7H', 0x21, 2, 4, 0, 0, 0, 0)


class PreparePioneerPalSdkTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='pal-sdk-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'sdk.jar'
        self.output = self.root / 'output' / 'pal-client.jar'
        self.classes = {PAL + name: class_bytes(PAL + name) for name in REQUIRED}

    def archive(self, classes=None, *, reverse=False, timestamp=(2024, 3, 2, 1, 2, 4)):
        classes = self.classes if classes is None else classes
        with zipfile.ZipFile(self.source, 'w') as archive:
            names = sorted(classes, reverse=reverse)
            for name in names:
                entry = zipfile.ZipInfo(name + '.class', timestamp)
                archive.writestr(entry, classes[name])
            archive.writestr('META-INF/MANIFEST.MF', b'not part of the PAL output')
            archive.writestr('vendor-config.json', b'{"unrelated": true}')

    def prepare(self):
        with contextlib.redirect_stdout(io.StringIO()):
            PREPARE(self.source, self.output)

    def seed_output(self):
        self.output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(self.output, 'w') as archive:
            archive.writestr('previous-release.txt', b'known good output')
        return self.output.read_bytes()

    def assert_rejected(self, message):
        previous = self.seed_output()
        with self.assertRaisesRegex(ValueError, message):
            self.prepare()
        self.assertEqual(previous, self.output.read_bytes())
        self.assertEqual([self.output], list(self.output.parent.iterdir()))

    def cli(self, *arguments):
        return subprocess.run(
            [sys.executable, '-B', str(SCRIPT), *map(str, arguments), '--output', str(self.output)],
            cwd=self.root, capture_output=True, text=True, timeout=15,
        )

    def test_required_pal_entries_are_retained_and_unrelated_vendor_classes_excluded(self):
        # Unreachable malformed bytes must not even be parsed as dependencies.
        self.classes['vendor/unrelated/Unusable'] = b'not JVM bytecode'
        self.classes['vendor/unrelated/FailedConversion'] = class_bytes(
            'vendor/unrelated/FailedConversion', 'd2j fail translate')
        self.archive()
        self.prepare()
        with zipfile.ZipFile(self.output) as archive:
            expected = [name + '.class' for name in sorted(PAL + name for name in REQUIRED)]
            self.assertEqual(expected, archive.namelist())
            for name in expected:
                self.assertEqual(self.classes[name[:-6]], archive.read(name))

    def test_each_required_entry_is_mandatory_and_rejection_preserves_existing_output(self):
        for missing in REQUIRED:
            with self.subTest(missing=missing):
                classes = dict(self.classes)
                del classes[PAL + missing]
                self.archive(classes)
                self.assert_rejected(re.escape('Unsupported SDK: missing ' + PAL + missing))

    def test_sdk_without_any_pal_classes_is_rejected(self):
        self.archive({'vendor/Only': class_bytes('vendor/Only')})
        self.assert_rejected('PAL classes missing')

    def test_transitive_dependency_closure_handles_descriptors_dotted_names_and_cycles(self):
        root = PAL + REQUIRED[0]
        self.classes[root] = class_bytes(
            root, '([Lvendor/model/Item;)Lvendor/api/Bridge;', 'vendor.reflect.Factory',
            'vendor/Direct', 'Ljava/util/List<Lvendor/model/Generic;>;')
        self.classes['vendor/api/Bridge'] = class_bytes('vendor/api/Bridge', 'Lvendor/model/Leaf;')
        self.classes['vendor/model/Leaf'] = class_bytes('vendor/model/Leaf', 'L' + root + ';')
        for name in ('vendor/model/Item', 'vendor/reflect/Factory', 'vendor/Direct', 'vendor/model/Generic'):
            self.classes[name] = class_bytes(name)
        self.classes['vendor/Unrelated'] = class_bytes('vendor/Unrelated', 'Lvendor/MissingButUnreachable;')
        self.archive()
        self.prepare()
        with zipfile.ZipFile(self.output) as archive:
            expected = {name + '.class' for name in self.classes if name != 'vendor/Unrelated'}
            self.assertEqual(expected, set(archive.namelist()))

    def test_platform_dependencies_do_not_require_vendor_definitions(self):
        name = PAL + REQUIRED[0]
        self.classes[name] = class_bytes(
            name, '(Ljava/lang/String;Ljavax/crypto/Cipher;Landroid/os/IBinder;'
            'Lorg/json/JSONObject;Lorg/xml/sax/ContentHandler;Lorg/w3c/dom/Node;'
            'Lcom/google/gson/Gson;)V')
        self.archive()
        self.prepare()
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(len(REQUIRED), len(archive.namelist()))

    def test_missing_transitive_dependency_is_rejected_preserving_output(self):
        root = PAL + REQUIRED[0]
        self.classes[root] = class_bytes(root, 'Lvendor/Bridge;')
        self.classes['vendor/Bridge'] = class_bytes('vendor/Bridge', 'Lvendor/Missing;')
        self.archive()
        self.assert_rejected('Unresolved PAL dependencies: vendor/Missing')

    def test_conversion_failure_markers_in_pal_or_reachable_dependencies_are_rejected(self):
        for target in (PAL + REQUIRED[0], 'vendor/Bridge'):
            for marker in ('d2j fail translate', 'Failed to translate'):
                with self.subTest(target=target, marker=marker):
                    classes = dict(self.classes)
                    root = PAL + REQUIRED[0]
                    classes[root] = class_bytes(root, 'Lvendor/Bridge;')
                    classes['vendor/Bridge'] = class_bytes('vendor/Bridge')
                    classes[target] = class_bytes(target, 'prefix ' + marker + ' suffix')
                    self.archive(classes)
                    self.assert_rejected('Bytecode conversion failed')

    def test_non_jvm_pal_class_is_rejected_preserving_output(self):
        self.classes[PAL + REQUIRED[0]] = b'dex\n035\x00not a JVM class'
        self.archive()
        self.assert_rejected('Expected JVM class bytecode')

    def test_duplicate_input_classes_are_rejected_preserving_output(self):
        self.archive()
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.source, 'a') as archive:
                name = PAL + REQUIRED[0]
                archive.writestr(name + '.class', self.classes[name])
        self.assert_rejected('Duplicate input classes')

    def test_output_content_and_timestamps_are_deterministic_across_input_order_and_metadata(self):
        self.archive()
        self.prepare()
        first = self.output.read_bytes()
        self.archive(reverse=True, timestamp=(2001, 12, 30, 20, 10, 8))
        self.prepare()
        self.assertEqual(first, self.output.read_bytes())
        self.prepare()
        self.assertEqual(first, self.output.read_bytes())
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(sorted(self.classes), [name[:-6] for name in archive.namelist()])
            for entry in archive.infolist():
                self.assertEqual((1980, 1, 1, 0, 0, 0), entry.date_time)
                self.assertEqual(zipfile.ZIP_DEFLATED, entry.compress_type)
                self.assertEqual(self.classes[entry.filename[:-6]], archive.read(entry))

    def test_input_jar_cli_works_without_private_assets(self):
        self.archive()
        result = self.cli('--input-jar', self.source)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('Prepared 7 PAL/dependency classes', result.stdout)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(7, len(archive.namelist()))

    def test_failed_converter_and_invalid_converted_archive_preserve_existing_output(self):
        tools = self.root / 'fake-dex-tools'
        tools.mkdir()
        launcher = tools / 'd2j-dex2jar.sh'
        dex = self.root / 'synthetic.dex'
        dex.write_bytes(b'not a real dex; the fake converter does not parse it')
        for exit_code in (7, 0):
            with self.subTest(converter_exit=exit_code):
                # The converter receives: -f -o OUTPUT DEX. Even partial output must never publish.
                launcher.write_text('#!/bin/sh\nprintf "partial conversion" > "$3"\nexit ' + str(exit_code) + '\n')
                previous = self.seed_output()
                result = self.cli('--dex', dex, '--dex2jar-home', tools)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('CalledProcessError' if exit_code else 'BadZipFile', result.stderr)
                self.assertEqual(previous, self.output.read_bytes())
                self.assertEqual([self.output], list(self.output.parent.iterdir()))


if __name__ == '__main__':
    unittest.main()
