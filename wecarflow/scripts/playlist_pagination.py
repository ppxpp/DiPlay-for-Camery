"""Pagination corrections that keep WeCarFlow page-loading beyond the first 20 songs.

The baseline request beans hard-code limit=0x14 and native page loading only
continues while PlayAlbumManager's BaseAlbumBean total exceeds the loaded queue
size. Entries that leave that total at 0 (or a stale first-page count) loop the
first 20 songs forever. This layer refreshes the total from each successful
page response and clamps it to the queue length on an empty page, so the
existing offset+=0x14 pagination runs to completion without depending on the
entry flow's initial total.
"""
import re
from pathlib import Path

PREFIX = 'smali_classes3/com/tencent/wecarflow/'
MARKER = '# carplayguard playlist pagination'
ALBUM = 'Lcom/tencent/wecarflow/net/network/bean/BaseAlbumBean;'

# Typed success handlers created per query; signature -> (response, list getter, total getter).
RESPONSE_HANDLERS = {
    'x0/m$l.smali': ('Lcom/tencent/wecarflow/net/network/bean/PlayListResponseBean;', 'getSongList', 'getTotal'),
    'x0/m$h.smali': ('Lcom/tencent/wecarflow/net/network/bean/like/LikeSongResponse;', 'getSong_list', 'getTotalCount'),
}

ALBUM_GETTER = f'''.method public carplayguardGetAlbum(){ALBUM}
    .locals 2

    iget-object v0, p0, Lcom/tencent/wecarflow/x0/e;->a:Landroid/arch/lifecycle/MutableLiveData;

    invoke-virtual {{v0}}, Landroid/arch/lifecycle/LiveData;->getValue()Ljava/lang/Object;

    move-result-object v0

    instance-of v1, v0, {ALBUM}

    if-eqz v1, :carplayguard_album_missing

    check-cast v0, {ALBUM}

    return-object v0

    :carplayguard_album_missing

    const/4 v0, 0x0

    return-object v0
.end method
'''

ALBUM_FIELD = '.field private final a:Landroid/arch/lifecycle/MutableLiveData;'

GATE_ANCHOR = '    :carplayguard_callback_continue\n'


def _method(text, signature):
    matches = list(re.compile(r'^\.method ' + re.escape(signature) + r'\n.*?^\.end method', re.M | re.S).finditer(text))
    if len(matches) != 1:
        raise ValueError(f'Expected exactly one method: {signature}; found {len(matches)}')
    return matches[0]


def _total_code(response, list_getter, total_getter):
    code = (f'    {MARKER}\n'
            '    invoke-static {}, Lcom/tencent/wecarflow/x0/e;->m()Lcom/tencent/wecarflow/x0/e;\n\n'
            '    move-result-object v0\n\n'
            f'    invoke-virtual {{v0}}, Lcom/tencent/wecarflow/x0/e;->carplayguardGetAlbum(){ALBUM}\n\n'
            '    move-result-object v0\n\n'
            '    if-eqz v0, :carplayguard_pagination_done\n\n'
            f'    invoke-virtual {{p1}}, {response}->{list_getter}()Ljava/util/List;\n\n'
            '    move-result-object v1\n\n'
            '    if-eqz v1, :carplayguard_pagination_total\n\n'
            '    invoke-interface {v1}, Ljava/util/List;->isEmpty()Z\n\n'
            '    move-result v1\n\n'
            '    if-eqz v1, :carplayguard_pagination_total\n\n'
            '    invoke-static {}, Lcom/tencent/wecarflow/x0/m;->l()Lcom/tencent/wecarflow/x0/m;\n\n'
            '    move-result-object v1\n\n'
            '    invoke-virtual {v1}, Lcom/tencent/wecarflow/x0/m;->e()Ljava/util/List;\n\n'
            '    move-result-object v1\n\n'
            '    invoke-interface {v1}, Ljava/util/List;->size()I\n\n'
            '    move-result v1\n\n'
            '    goto :carplayguard_pagination_set\n\n'
            '    :carplayguard_pagination_total\n\n'
            f'    invoke-virtual {{p1}}, {response}->{total_getter}()I\n\n'
            '    move-result v1\n\n'
            '    :carplayguard_pagination_set\n\n'
            f'    invoke-virtual {{v0, v1}}, {ALBUM}->setTotal(I)V\n\n'
            '    :carplayguard_pagination_done\n')
    return code


def _patch_handler(text, name, response, list_getter, total_getter):
    signature = f'public a({response}I)V'
    match = _method(text, signature)
    body = match.group()
    if '# carplayguard callback origin' not in body:
        raise ValueError(f'{name}: pagination requires the playback callback gate')
    if MARKER in body:
        return text
    if body.count(GATE_ANCHOR) != 1:
        raise ValueError(f'{name}: callback gate anchor is not unique')
    locals_match = re.search(r'^    \.locals (\d+)\n', body, re.M)
    if not locals_match:
        raise ValueError(f'{name}: handler requires a .locals directive')
    if re.search(r'\bv1\b', body):
        raise ValueError(f'{name}: cannot reserve v1 for the pagination block')
    body = body[:locals_match.start()] + '    .locals 2\n' + body[locals_match.end():]
    body = body.replace(GATE_ANCHOR, GATE_ANCHOR + '\n' + _total_code(response, list_getter, total_getter) + '\n', 1)
    return text[:match.start()] + body + text[match.end():]


def patch_pagination(root, files):
    """Apply pagination corrections onto the playback-hooked texts.

    Takes the apktool root plus the dict returned by playback_hooks.patch_playback.
    Validates every target before returning; already-patched files pass through
    unchanged.
    """
    files = dict(files)
    album_path = PREFIX + 'x0/e.smali'
    album = files.get(album_path)
    if album is None:
        source = Path(root) / album_path
        if not source.is_file():
            raise ValueError('missing x0/e.smali input')
        album = source.read_text()
    if album.count(ALBUM_FIELD) != 1:
        raise ValueError('x0/e.smali: album LiveData field anchor drifted')
    if 'carplayguardGetAlbum' not in album:
        album = album.rstrip('\n') + '\n\n' + ALBUM_GETTER
    files[album_path] = album
    for name, (response, list_getter, total_getter) in RESPONSE_HANDLERS.items():
        path = PREFIX + name
        text = files.get(path)
        if text is None:
            raise ValueError(f'missing {path} input')
        files[path] = _patch_handler(text, name, response, list_getter, total_getter)
    return files
