#!/usr/bin/env python3
"""Run NTB and real-lwIP/local-socket host regressions with address/UB sanitizers."""
from pathlib import Path
import os
import platform
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
CPP = ROOT / "pioneer-adapter/src/main/cpp"
TEST = ROOT / "pioneer-adapter/src/test/cpp"
VENDOR = CPP / "vendor/lwip-STABLE-2_2_1_RELEASE"

def main():
    compiler = shutil.which("clang")
    if not compiler:
        raise SystemExit("clang is required for the native sanitizer checks")
    java = os.environ.get("JAVA_HOME")
    if not java and platform.system() == "Darwin":
        java = subprocess.check_output(["/usr/libexec/java_home"], text=True).strip()
    if not java:
        javac = shutil.which("javac")
        if not javac:
            raise SystemExit("A JDK is required for JNI headers")
        java = str(Path(javac).resolve().parent.parent)
    includes = [CPP, CPP / "lwip-port", VENDOR / "src/include",
                Path(java) / "include", Path(java) / "include" / ("darwin" if platform.system() == "Darwin" else "linux")]
    core = sorted((VENDOR / "src/core").glob("*.c")) + sorted((VENDOR / "src/core/ipv6").glob("*.c"))
    targets = [([TEST / "pioneer_ntb_test.c"], "ntb"),
               ([TEST / "pioneer_lwip_stream_test.c", *core, VENDOR / "src/netif/ethernet.c"], "lwip-stream"),
               ([TEST / "pioneer_vpn_test.c"], "vpn-packets")]
    with tempfile.TemporaryDirectory(prefix="diplay-native-") as temporary:
        for sources, name in targets:
            binary = Path(temporary) / name
            command = [compiler, "-std=c11", "-O1", "-g", "-fsanitize=address,undefined",
                       *["-I" + str(path) for path in includes], *map(str, sources), "-o", str(binary)]
            subprocess.run(command, check=True)
            subprocess.run([str(binary)], check=True)

if __name__ == "__main__":
    main()
