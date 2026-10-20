#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
use_testkey=false
case "${1:-}" in
  '') ;;
  --testkey) use_testkey=true ;;
  *) echo 'Usage: scripts/build-usb-permission-probe.sh [--testkey]' >&2; exit 2 ;;
esac
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
apksigner="$sdk_dir/build-tools/36.0.0/apksigner"
[[ -x "$apksigner" ]] || { echo "apksigner missing: $apksigner" >&2; exit 1; }
./gradlew :usb-permission-probe:assembleDebug
output_dir=usb-permission-probe/build/outputs/apk/debug
source_apk="$output_dir/usb-permission-probe-debug.apk"
if $use_testkey; then
  key_dir=.private/usb-permission-probe-testkey
  mkdir -p "$key_dir"
  for name in testkey.x509.pem testkey.pk8; do
    if [[ ! -s "$key_dir/$name" ]]; then
      curl --fail --silent --show-error --location \
        "https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security/$name?format=TEXT" \
        | openssl base64 -d -A > "$key_dir/$name.tmp"
      mv "$key_dir/$name.tmp" "$key_dir/$name"
    fi
  done
  certificate_hash=$(openssl x509 -in "$key_dir/testkey.x509.pem" -outform DER | shasum -a 256 | awk '{print $1}')
  [[ "$certificate_hash" == a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc ]] || { echo 'Unexpected testkey certificate' >&2; exit 1; }
  result_apk="$output_dir/DiPlay-USB权限验证-testkey.apk"
  "$apksigner" sign --key "$key_dir/testkey.pk8" --cert "$key_dir/testkey.x509.pem" --out "$result_apk" "$source_apk"
else
  result_apk="$output_dir/DiPlay-USB权限验证-default.apk"
  cp "$source_apk" "$result_apk"
fi
"$apksigner" verify --print-certs "$result_apk"
printf '\nAPK: %s\n' "$PWD/$result_apk"
