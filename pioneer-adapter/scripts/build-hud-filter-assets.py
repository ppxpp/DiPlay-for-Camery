#!/usr/bin/env python3
"""Package a pinned standalone injector; no frida-server, bridge, or host Python at runtime."""
import hashlib
import lzma
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
version = "17.9.1"
digest = "7569bd8f9016eba5ba30a082a5db85ef4aa93ef10dbc4d464a26582731d0b7db"
cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/diplay-hud-filter"
cache.mkdir(parents=True, exist_ok=True)
archive = cache / f"frida-inject-{version}-android-arm.xz"
if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as download:
        path = Path(download.name)
    try:
        subprocess.run(["curl", "--fail", "--location", "--silent", "--show-error", "--max-time", "180",
                        f"https://github.com/frida/frida/releases/download/{version}/{archive.name}", "--output", str(path)], check=True)
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise RuntimeError("HUD injector checksum mismatch")
        path.replace(archive)
    finally:
        path.unlink(missing_ok=True)
output = root / "build/generated/hud-filter-assets"
output.mkdir(parents=True, exist_ok=True)
payload = lzma.decompress(archive.read_bytes())
if payload[:5] != b"\x7fELF\x01":
    raise RuntimeError("Expected the verified ARM32 injector")
(output / "pioneer-hud-injector").write_bytes(payload)
source = root / "src/main/hud-filter"
(output / "pioneer-hud-filter.js").write_text((source / "lease-policy.js").read_text() + "\n" + (source / "agent.js").read_text())
(output / "pioneer-hud-filter-LICENSE").write_bytes((source / "FRIDA-LICENSE").read_bytes())
print("HUD standalone injector bytes=" + str(len(payload)))
