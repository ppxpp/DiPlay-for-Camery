import importlib.util
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).resolve().parents[1]


def _load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PlaylistPaginationTests(unittest.TestCase):
    def setUp(self):
        self.playback = _load('playback_hooks')
        self.pagination = _load('playlist_pagination')
        self.root = HERE.parent / 'apktool'

    def _patches(self, root):
        return self.pagination.patch_pagination(root, self.playback.patch_playback(root))

    def test_total_refresh_after_callback_gate(self):
        patches = self._patches(self.root)
        for name, response, total_getter in (
            ('x0/m$l.smali', 'Lcom/tencent/wecarflow/net/network/bean/PlayListResponseBean;', 'getTotal'),
            ('x0/m$h.smali', 'Lcom/tencent/wecarflow/net/network/bean/like/LikeSongResponse;', 'getTotalCount')):
            text = patches[self.playback.PREFIX + name]
            body = self.playback._method(text, f'public a({response}I)V').group()
            self.assertIn(self.pagination.MARKER, body)
            self.assertLess(body.index('shouldBlockPrepared'), body.index(self.pagination.MARKER))
            self.assertIn('->carplayguardGetAlbum()', body)
            self.assertIn(f'{response}->{total_getter}()I', body)
            self.assertIn('Lcom/tencent/wecarflow/x0/m;->e()Ljava/util/List;', body)
            self.assertIn(self.pagination.ALBUM + '->setTotal(I)V', body)
            self.assertEqual(body.count(':carplayguard_pagination_done'), 2)
            self.assertNotIn(';;', body)
            self.assertIn('\n    .locals 2\n', body)
            # The original queue hand-off stays untouched below the inserted block.
            self.assertIn('Lcom/tencent/wecarflow/x0/m;->b(Lcom/tencent/wecarflow/x0/m;Ljava/util/List;Lcom/tencent/wecarflow/x0/m$j;)V', body)

    def test_album_getter_added(self):
        patches = self._patches(self.root)
        album = patches[self.playback.PREFIX + 'x0/e.smali']
        self.assertNotIn(';;', album)
        self.assertIn('carplayguardGetAlbum()Lcom/tencent/wecarflow/net/network/bean/BaseAlbumBean;', album)
        self.assertIn('iget-object v0, p0, Lcom/tencent/wecarflow/x0/e;->a:Landroid/arch/lifecycle/MutableLiveData;', album)

    def test_idempotent(self):
        patches = self._patches(self.root)
        with tempfile.TemporaryDirectory() as directory:
            copy = Path(directory)
            for name, data in patches.items():
                target = copy / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(data)
            self.assertEqual(patches, self._patches(copy))

    def test_baseline_matches_patched_tree(self):
        baseline = HERE.parent / 'build' / 'baseline'
        if not baseline.exists():
            self.skipTest('clean baseline tree not generated')
        self.assertEqual(self._patches(self.root), self._patches(baseline))

    def test_drift_rejected(self):
        baseline = HERE.parent / 'build' / 'baseline'
        # Drift must be caught against unpaginated text; prefer the clean baseline tree.
        source_root = baseline if baseline.exists() else self.root
        base = self.playback.patch_playback(source_root)
        for name, mutation in (
            ('x0/e.smali', lambda text: text.replace(self.pagination.ALBUM_FIELD, '.field private final z:Landroid/arch/lifecycle/MutableLiveData;')),
            ('x0/m$l.smali', lambda text: text.replace('# carplayguard callback origin', '# gate removed')),
            ('x0/m$h.smali', lambda text: text.replace('    .locals 1\n', '    .locals 1\n    const/4 v1, 0x0\n', 1))):
            mutated = dict(base)
            path = self.playback.PREFIX + name
            source = mutated.get(path)
            if source is None:
                source = (source_root / path).read_text()
            mutated[path] = mutation(source)
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.pagination.patch_pagination(source_root, mutated)


if __name__ == '__main__':
    unittest.main()
