import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('ci_android_tasks', Path(__file__).resolve().parents[1] / 'ci_android_tasks.py')
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class AndroidCiSelectionTest(unittest.TestCase):
    def test_docs_and_site_do_not_start_android(self):
        self.assertEqual([], ci.select_tasks(['README.md', 'docs/guide.md', 'site/index.html', 'update-service/src/index.ts']))

    def test_shared_changes_include_downstream_tests_without_packaging(self):
        tasks = ci.select_tasks(['shared/src/main/java/example.kt'])
        self.assertIn(':shared:testDebugUnitTest', tasks)
        self.assertIn(':common:testDebugUnitTest', tasks)
        self.assertIn(':shared:lintDebug', tasks)
        self.assertIn(':mobile:compileDebugKotlin', tasks)
        self.assertNotIn(':common:lintDebug', tasks)
        self.assertFalse(any('assemble' in task for task in tasks))

    def test_pioneer_includes_shared_and_common(self):
        tasks = ci.select_tasks(['pioneer-adapter/src/main/java/example.kt'])
        for module in ('pioneer-adapter', 'shared', 'common'):
            self.assertIn(f':{module}:testDebugUnitTest', tasks)
        self.assertIn(':pioneer-adapter:lintDebug', tasks)

    def test_common_does_not_run_upstream_tests(self):
        tasks = ci.select_tasks(['common/src/main/java/example.kt'])
        self.assertIn(':common:testDebugUnitTest', tasks)
        self.assertNotIn(':shared:testDebugUnitTest', tasks)

    def test_resources_and_manifest_build_main_apk(self):
        for path in ('common/src/main/res/values/strings.xml', 'mobile/src/main/AndroidManifest.xml',
                     'shared/src/main/assets/config.json'):
            with self.subTest(path=path):
                self.assertIn(':mobile:assembleDebug', ci.select_tasks([path]))

    def test_example_changes_build_only_that_example(self):
        tasks = ci.select_tasks(['samples/home/src/main/java/example.kt'])
        self.assertIn(':home:testDebugUnitTest', tasks)
        self.assertIn(':home:assembleDebug', tasks)
        self.assertNotIn(':maphost:assembleDebug', tasks)

    def test_sample_resources_include_common_translation_contract(self):
        for module in ('home', 'maphost'):
            with self.subTest(module=module):
                tasks = ci.select_tasks([f'samples/{module}/src/main/res/values/strings.xml'])
                self.assertIn(':common:testDebugUnitTest', tasks)
                self.assertIn(f':{module}:assembleDebug', tasks)

    def test_gradle_native_and_unknown_changes_run_full(self):
        for path in ('gradle.properties', 'mobile/build.gradle.kts', 'shared/src/main/jni/Android.mk',
                     'pioneer-adapter/src/main/cpp/wireless/test.c', 'pioneer-adapter/src/main/cpp/CMakeLists.txt',
                     'scripts/ci_android_tasks.py',
                     '.github/workflows/android.yml'):
            with self.subTest(path=path):
                self.assertEqual(ci.FULL_TASKS, ci.select_tasks([path]))

    def test_deleted_and_mixed_paths_keep_android_checks(self):
        tasks = ci.select_tasks(['README.md', 'shared/src/main/java/deleted.kt', 'common/src/test/java/test.kt'])
        self.assertIn(':shared:testDebugUnitTest', tasks)
        self.assertIn(':common:lintDebug', tasks)
        self.assertEqual(len(tasks), len(set(tasks)))

    def test_full_mode_ignores_paths(self):
        self.assertEqual(ci.FULL_TASKS, ci.select_tasks(['README.md'], full=True))


if __name__ == '__main__':
    unittest.main()
