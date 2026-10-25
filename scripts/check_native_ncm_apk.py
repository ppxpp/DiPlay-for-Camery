#!/usr/bin/env python3
"""Verify optional kernel-NCM implementations/assets in a built APK, including debug DEX."""
import argparse
import json
import struct
import zipfile
from pathlib import Path

OPTIONAL = {
    "Lcom/shilapi/xcertplay/pioneer/NativeNcmModule;",
    "Lcom/shilapi/xcertplay/pioneer/PioneerNativeNcmHelper;",
    "Lcom/shilapi/xcertplay/pioneer/PioneerNativeNcmPrewarm;",
    "Lcom/shilapi/xcertplay/pioneer/PioneerNativeNcmWarmWorker;",
    "Lcom/shilapi/xcertplay/network/NativeNcmPreparationGate;",
    "Lcom/shilapi/xcertplay/network/NativeLeaseRetention;",
}
COMMON = {
    "Lcom/shilapi/xcertplay/pioneer/PioneerRootGuard;",
    "Lcom/shilapi/xcertplay/pioneer/PioneerRootSessionHelper;",
    "Lcom/shilapi/xcertplay/pioneer/PioneerUsbPermissionHelper;",
    "Lcom/shilapi/xcertplay/pioneer/PioneerVpnPermissionHelper;",
    "Lcom/shilapi/xcertplay/pioneer/PioneerVpnFirewallHelper;",
    "Lcom/shilapi/xcertplay/pioneer/PioneerRootCommand;",
    "Lcom/shilapi/xcertplay/pioneer/PioneerInterfaceFirewall;",
    "Lcom/shilapi/xcertplay/pioneer/PioneerRootTaskProtocol;",
    "Lcom/shilapi/xcertplay/pioneer/PioneerFirmwareCatalog;",
    "Lcom/shilapi/xcertplay/network/InterfaceSocketBinding;",
}
ASSETS = {
    "assets/camry_ncm/cdc_ncm_0062.ko",
    "assets/camry_ncm/cdc_ncm_0132.ko",
    "assets/camry_ncm/init_module_armv7",
    "assets/camry_ncm/COPYING",
    "assets/camry_ncm/NOTICE.txt",
}


def defined_classes(dex):
    if not dex.startswith(b"dex\n"):
        raise ValueError("Expected standard Android DEX")
    u32 = lambda offset: struct.unpack_from("<I", dex, offset)[0]
    strings, types, count, classes = u32(60), u32(68), u32(96), u32(100)
    result = set()
    for offset in range(classes, classes + count * 32, 32):
        descriptor = u32(types + u32(offset) * 4)
        start = u32(strings + descriptor * 4)
        # Skip the unsigned LEB128 UTF-16 length before the modified-UTF8 descriptor.
        while dex[start] & 128:
            start += 1
        start += 1
        result.add(dex[start:dex.index(b"\0", start)].decode("utf-8"))
    return result


def inspect(apk, enabled):
    with zipfile.ZipFile(apk) as archive:
        classes = set()
        for name in archive.namelist():
            if name.startswith("classes") and name.endswith(".dex") and "/" not in name:
                classes.update(defined_classes(archive.read(name)))
        assets = {n for n in archive.namelist() if n.startswith("assets/camry_ncm/") and not n.endswith("/")}
    if not classes:
        raise ValueError("No DEX class definitions found")
    missing_common = COMMON - classes
    if missing_common:
        raise ValueError(f"Common VPN/root/socket support missing: {sorted(missing_common)}")
    if enabled:
        if OPTIONAL - classes or ASSETS - assets:
            raise ValueError(f"Native NCM incomplete: classes={sorted(OPTIONAL - classes)}, assets={sorted(ASSETS - assets)}")
    else:
        implementations = {c for c in classes if any(c == base or c.startswith(base[:-1] + "$") for base in OPTIONAL)}
        if implementations or assets:
            raise ValueError(f"Native NCM leaked into disabled APK: classes={sorted(implementations)}, assets={sorted(assets)}")
    return {"apk": str(apk), "nativeNcmIncluded": enabled, "bytes": apk.stat().st_size,
            "classes": len(classes), "nativeAssets": sorted(assets), "commonSupportPresent": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--enabled", choices=["true", "false"], required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(inspect(args.apk, args.enabled == "true"), indent=2))
    except (OSError, ValueError, zipfile.BadZipFile, struct.error) as error:
        parser.exit(1, f"APK check failed: {error}\n")
