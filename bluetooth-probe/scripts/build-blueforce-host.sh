#!/bin/bash
set -euo pipefail
probe_root="$(cd "$(dirname "$0")/../.." && pwd)"
probe_sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$probe_sdk_dir" ]; then probe_sdk_dir="$(sed -n 's/^sdk.dir=//p' "$probe_root/local.properties")"; fi
case "$(uname -s)" in Darwin) probe_host=darwin-x86_64;; Linux) probe_host=linux-x86_64;; *) exit 1;; esac
probe_output="$probe_root/bluetooth-probe/build/generated/blueforce-host-assets"
mkdir -p "$probe_output"
"$probe_sdk_dir/ndk/28.2.13676358/toolchains/llvm/prebuilt/$probe_host/bin/armv7a-linux-androideabi23-clang" \
  -fPIE -pie -O2 -Wall -Wextra -Werror "$probe_root/bluetooth-probe/src/main/cpp/pioneer_blueforce_host.c" \
  -ldl -llog -o "$probe_output/blueforce-host"

"$probe_sdk_dir/ndk/28.2.13676358/toolchains/llvm/prebuilt/$probe_host/bin/armv7a-linux-androideabi23-clang" \
  -fPIE -pie -O2 -Wall -Wextra -Werror "$probe_root/bluetooth-probe/src/main/cpp/pioneer_callback_repair.c" \
  -o "$probe_output/blueforce-callback-repair"
