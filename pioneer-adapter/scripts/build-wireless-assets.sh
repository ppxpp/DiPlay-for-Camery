#!/bin/bash
set -euo pipefail
wireless_root="$(cd "$(dirname "$0")/../.." && pwd)"
wireless_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$wireless_sdk" ]; then wireless_sdk="$(sed -n 's/^sdk.dir=//p' "$wireless_root/local.properties")"; fi
case "$(uname -s)" in Darwin) wireless_host=darwin-x86_64;; Linux) wireless_host=linux-x86_64;; *) exit 1;; esac
wireless_cc="$wireless_sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/$wireless_host/bin/armv7a-linux-androideabi23-clang"
wireless_source="$wireless_root/pioneer-adapter/src/main/cpp/wireless"
wireless_output="$wireless_root/pioneer-adapter/build/generated/wireless-assets"
mkdir -p "$wireless_output"
"$wireless_cc" -fPIE -pie -O2 -Wall -Wextra -Werror "$wireless_source/pioneer_blueforce_host.c" -ldl -llog -o "$wireless_output/blueforce-host"
"$wireless_cc" -fPIE -pie -O2 -Wall -Wextra -Werror "$wireless_source/pioneer_callback_repair.c" -o "$wireless_output/blueforce-callback-repair"
"$wireless_cc" -fPIE -pie -O2 -Wall -Wextra -Werror "$wireless_source/pioneer_wifi_audit.c" -o "$wireless_output/pioneer-wifi-audit"

"$wireless_cc" -fPIE -pie -O2 -Wall -Wextra -Werror "$wireless_source/pioneer_opus_host.c" -ldl -o "$wireless_output/pioneer-opus-host"

"$wireless_cc" -fPIE -pie -O2 -Wall -Wextra -Werror "$wireless_source/pioneer_ap_frequency_gate.c" -o "$wireless_output/pioneer-ap-frequency-gate"
