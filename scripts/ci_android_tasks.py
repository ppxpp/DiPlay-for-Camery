#!/usr/bin/env python3
"""Select Android verification tasks; dependency changes include downstream tests."""
import argparse
from pathlib import Path
import subprocess

FULL_TASKS = (
    ':pioneer-adapter:testDebugUnitTest :shared:testDebugUnitTest '
    ':common:testDebugUnitTest :home:testDebugUnitTest '
    ':common:lintDebug :shared:lintDebug :pioneer-adapter:lintDebug '
    ':mobile:lintDebug :home:lintDebug :maphost:lintDebug '
    ':mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug'
).split()
# These paths cannot affect the Android build. Unclassified paths get full validation.
NON_ANDROID = ('docs/', 'agent_docs/', 'site/', 'update-service/', 'installer/',
               '.github/ISSUE_TEMPLATE/')
MODULES = {
    'pioneer-adapter/': ('pioneer-adapter', ('pioneer-adapter', 'shared', 'common')),
    'shared/': ('shared', ('shared', 'common')),
    'common/': ('common', ('common',)),
    'mobile/': ('mobile', ()),
    'samples/home/': ('home', ('home',)),
    'samples/maphost/': ('maphost', ()),
}


def select_tasks(paths, full=False):
    if full:
        return FULL_TASKS.copy()
    tests, lint, assemble = set(), set(), set()
    android = False
    for path in paths:
        if ('/' not in path and path.endswith('.md')) or path.startswith(NON_ANDROID):
            continue
        if path.startswith('.github/workflows/') and path not in (
                '.github/workflows/android.yml', '.github/workflows/release.yml',
                '.github/workflows/build-android-tv.yml'):
            continue
        # Gradle, JNI, workflow and tool changes require the complete packaging check.
        if (path.endswith(('.gradle', '.gradle.kts')) or '/src/main/jni/' in path or
                '/src/main/cpp/' in path):
            return FULL_TASKS.copy()
        for prefix, (module, affected_tests) in MODULES.items():
            if path.startswith(prefix):
                android = True
                tests.update(affected_tests)
                lint.add(module)
                if module in ('home', 'maphost'):
                    assemble.add(module)
                    if '/res/' in path:
                        # common's translation contract also validates both sample apps.
                        tests.add('common')
                elif any(part in path for part in ('/res/', '/assets/', 'AndroidManifest.xml')):
                    assemble.add('mobile')
                break
        else:
            return FULL_TASKS.copy()
    if not android:
        return []
    tasks = [f':{module}:testDebugUnitTest' for module in sorted(tests)]
    tasks += [f':{module}:lintDebug' for module in sorted(lint)]
    # Check the real app's Kotlin/Java/resource integration without DEX/native/APK packaging.
    tasks += [':mobile:compileDebugKotlin', ':mobile:compileDebugJavaWithJavac']
    tasks += [f':{module}:assembleDebug' for module in sorted(assemble)]
    return tasks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base')
    parser.add_argument('--full', action='store_true')
    parser.add_argument('--github-output', type=Path, required=True)
    args = parser.parse_args()
    if not args.full and not args.base:
        parser.error('--base is required unless --full is used')
    paths = [] if args.full else [path for path in subprocess.check_output([
        'git', 'diff', '--name-only', '--no-renames', '-z', args.base, 'HEAD', '--',
    ]).decode().split('\0') if path]
    tasks = select_tasks(paths, args.full)
    with args.github_output.open('a') as output:
        output.write(f'android={str(bool(tasks)).lower()}\n')
        output.write('tasks=' + ' '.join(tasks) + '\n')
    print('Android tasks: ' + (' '.join(tasks) or 'none (non-Android changes)'))


if __name__ == '__main__':
    main()
