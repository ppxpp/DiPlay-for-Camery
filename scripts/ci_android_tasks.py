#!/usr/bin/env python3
"""Select Android verification tasks; dependency changes include downstream tests."""
import argparse
from pathlib import Path
import subprocess

FULL_TESTS = (
    ':pioneer-adapter:testDebugUnitTest :shared:testDebugUnitTest '
    ':common:testDebugUnitTest :home:testDebugUnitTest'
).split()
FULL_LINT = (
    ':common:lintDebug :shared:lintDebug :pioneer-adapter:lintDebug '
    ':mobile:lintDebug :home:lintDebug :maphost:lintDebug'
).split()
FULL_BUILD = ':mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug'.split()
FULL_TASKS = FULL_TESTS + FULL_LINT + FULL_BUILD

COMPILE_TASKS = [':mobile:compileDebugKotlin', ':mobile:compileDebugJavaWithJavac']

# These paths cannot affect the Android build. Unclassified paths get full validation.
NON_ANDROID = ('docs/', 'agent_docs/', 'site/', 'update-service/', 'installer/',
               '.github/ISSUE_TEMPLATE/')
ANDROID_WORKFLOWS = (
    '.github/workflows/android.yml',
    '.github/workflows/release.yml',
    '.github/workflows/build-android-tv.yml',
)
ROOT_BUILD_PATHS = (
    'build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts',
    'gradle.properties', 'gradle/',
)
NATIVE_MARKERS = ('/src/main/jni/', '/src/main/cpp/', '/src/main/hud-filter/')
# Small apps that are not packaged into the mobile APK: compile the module itself for any change.
STANDALONE_MODULES = ('home', 'maphost', 'usbcheck', 'bluetooth-probe',
                      'usb-permission-probe', 'automotive')
# prefix: (module, downstream test modules, packaged into the mobile APK)
MODULES = {
    'pioneer-adapter/': ('pioneer-adapter', ('pioneer-adapter', 'shared', 'common'), True),
    'shared/': ('shared', ('shared', 'common'), True),
    'common/': ('common', ('common',), True),
    'mobile/': ('mobile', (), True),
    'samples/home/': ('home', ('home',), False),
    'samples/maphost/': ('maphost', ('maphost',), False),
    'usbcheck/': ('usbcheck', (), False),
    'bluetooth-probe/': ('bluetooth-probe', (), False),
    'usb-permission-probe/': ('usb-permission-probe', (), False),
    'automotive/': ('automotive', (), False),
}


def full_groups():
    return {
        'tests': FULL_TESTS.copy(),
        'lint': FULL_LINT.copy(),
        'build': FULL_BUILD.copy(),
    }


def module_for(path):
    for prefix, (module, affected_tests, packaged_into_mobile) in MODULES.items():
        if path.startswith(prefix):
            return prefix, module, affected_tests, packaged_into_mobile
    return None


def select_task_groups(paths, full=False):
    if full:
        return full_groups()

    tests, lint, assemble = set(), set(), set()
    android = False
    for path in paths:
        if ('/' not in path and path.endswith('.md')) or path.startswith(NON_ANDROID):
            continue
        if path.startswith('.github/workflows/'):
            if path in ANDROID_WORKFLOWS:
                return full_groups()
            continue
        if path.startswith(ROOT_BUILD_PATHS):
            return full_groups()
        selected = module_for(path)
        if selected is None:
            return full_groups()
        prefix, module, affected_tests, packaged_into_mobile = selected
        android = True
        tests.update(affected_tests)
        lint.add(module)

        build_file = path.endswith(('.gradle', '.gradle.kts'))
        native = any(marker in path for marker in NATIVE_MARKERS) or (
            module == 'pioneer-adapter' and path.startswith(prefix + 'scripts/')
        )
        if build_file or native:
            # These can change dependency or native packaging, so validate the installable APK.
            assemble.add('mobile' if packaged_into_mobile else module)
        elif module in STANDALONE_MODULES:
            assemble.add(module)
            if module in ('home', 'maphost') and '/res/' in path:
                # common's translation contract also validates both sample apps.
                tests.add('common')
        elif any(part in path for part in ('/res/', '/assets/', 'AndroidManifest.xml')):
            assemble.add('mobile' if packaged_into_mobile else module)

    if not android:
        return {'tests': [], 'lint': [], 'build': []}
    return {
        'tests': [f':{module}:testDebugUnitTest' for module in sorted(tests)],
        'lint': [f':{module}:lintDebug' for module in sorted(lint)],
        'build': COMPILE_TASKS + [f':{module}:assembleDebug' for module in sorted(assemble)],
    }


def select_tasks(paths, full=False):
    groups = select_task_groups(paths, full)
    return groups['tests'] + groups['lint'] + groups['build']


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
    groups = select_task_groups(paths, args.full)
    android = bool(groups['tests'] or groups['lint'] or groups['build'])
    with args.github_output.open('a') as output:
        output.write(f'android={str(android).lower()}\n')
        output.write('tests=' + ' '.join(groups['tests']) + '\n')
        output.write('lint=' + ' '.join(groups['lint']) + '\n')
        output.write('build=' + ' '.join(groups['build']) + '\n')
        output.write('tasks=' + ' '.join(select_tasks(paths, args.full)) + '\n')
    print('Android tasks: ' + (' '.join(select_tasks(paths, args.full)) or 'none (non-Android changes)'))


if __name__ == '__main__':
    main()
