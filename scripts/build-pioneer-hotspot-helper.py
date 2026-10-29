#!/usr/bin/env python3
"""Build only the privileged hotspot entry point, never the application classpath."""
from pathlib import Path
import os, subprocess, sys, tempfile, zipfile, re
classes, output = map(Path, sys.argv[1:3])
root = Path(__file__).resolve().parent.parent
sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
if not sdk:
    sdk = re.search(r"(?m)^sdk.dir=(.*)$", (root / "local.properties").read_text())[1]
sdk = Path(sdk)
version = re.search(r'(?m)^kotlin = "([^"]+)"', (root / "gradle/libs.versions.toml").read_text())[1]
stdlib = next(p for p in (Path.home()/".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib"/version).glob("*/*.jar") if not p.name.endswith(("-sources.jar", "-javadoc.jar")))
output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
    tmp = Path(tmp)
    program = tmp / "helper.jar"
    prefix = "com/shilapi/xcertplay/vehicle/adapters/pioneer/wireless/"
    with zipfile.ZipFile(program, "w") as z:
        count = 0
        for p in classes.rglob("*.class"):
            name = p.relative_to(classes).as_posix()
            if name.startswith(prefix) and p.stem.split("$")[0] in {"HotspotProbeHelper", "HotspotProbeAction", "PioneerBinder", "PioneerVendorCalls", "PioneerLocalOnlyPolicy", "HotspotPrewarmAuthorization"}:
                z.write(p, name); count += 1
        assert count >= 3, "Missing compiled hotspot helper"
    rules = tmp / "helper.pro"
    rules.write_text("-keep class com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless.HotspotProbeHelper { public static void main(java.lang.String[]); }\n-keep enum com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless.HotspotProbeAction { *; }\n-dontwarn org.jetbrains.annotations.**\n-dontwarn org.intellij.lang.annotations.**\n")
    dex = tmp / "dex"; dex.mkdir()
    subprocess.run(["java", "-cp", str(sdk/"build-tools/36.0.0/lib/d8.jar"), "com.android.tools.r8.R8", "--release", "--min-api", "23", "--lib", str(sdk/"platforms/android-37.0/android.jar"), "--pg-conf", str(rules), "--output", str(dex), str(program), str(stdlib)], check=True)
    payload = (dex/"classes.dex").read_bytes()
    assert len(list(dex.glob("*.dex"))) == 1
    target = output/"pioneer-hotspot-helper.dex"
    if not target.exists() or target.read_bytes() != payload:
        target.write_bytes(payload)
    print("Pioneer hotspot helper bytes=" + str(len(payload)))
