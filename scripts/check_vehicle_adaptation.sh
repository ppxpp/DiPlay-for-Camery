#!/usr/bin/env bash
# Run the same source-only Camry regression suite locally and in CI.
set -euo pipefail
TASK_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$TASK_ROOT"

with_baseline=false
case "${1:-}" in
    "") ;;
    --with-wecarflow-baseline) with_baseline=true ;;
    -h|--help)
        printf '%s\n' \
            'Usage: scripts/check_vehicle_adaptation.sh [--with-wecarflow-baseline]' \
            'Default: DiPlay/Pioneer regressions, source-only APK build, portable WeCarFlow tests.' \
            'Optional: also check private WeCarFlow baseline patches; requires build/baseline and apktool.'
        exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
esac
if [[ $# -gt 1 ]]; then
    printf '%s\n' 'Only one option is supported; use --help.' >&2
    exit 2
fi
if $with_baseline && [[ ! -f wecarflow/build/baseline/AndroidManifest.xml || ! -d wecarflow/apktool ]]; then
    printf '%s\n' 'Private WeCarFlow baseline is missing. Prepare it using wecarflow/README.md; no baseline tests were skipped.' >&2
    exit 2
fi

python3 scripts/check_public_tree.py

# Filters belong to the preceding Gradle test task. Keep module boundaries explicit.
./gradlew -PdiplaySourceOnly=true \
    :shared:testDebugUnitTest \
    --tests '*CarPlaySizeTest' \
    --tests '*CarPlayVpn*Test' \
    --tests '*Pioneer*' \
    --tests '*BluetoothSuspendLifecycleTest' \
    --tests '*RemoteGatewayConnectionTest' \
    --tests '*TelephonyMicrophoneTest' \
    --tests '*AudioFocusAutoYieldTest' \
    --tests '*VehicleMediaFocusRecoveryTest' \
    --tests '*AndroidMediaSink*DetachTest' \
    --tests '*WiredStartWatchdogTest' \
    --tests '*FirstTcpWatchdogTest' \
    --tests '*NcmUsbBridgeFramingTest' \
    --tests '*NcmReadPipeline*Test' \
    --tests '*Ipv6NcmWriteResultTest' \
    --tests '*NcmTrafficCounterTest' \
    --tests '*SoftwareOpusEncoderTest' \
    --tests '*NavigationAudioGainTest' \
    :common:testDebugUnitTest \
    --tests '*CamrySettingsDefaultsTest' \
    --tests '*CarPlayVpnConsentTest' \
    --tests '*NativeNcmSettingsTest' \
    --tests '*CamryDisplaySettingsTest' \
    --tests '*UsbChargingCurrentTest' \
    --tests '*AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem' \
    --tests '*CarPlayNightMode*' \
    --tests '*NightModeSettingsTest' \
    --tests '*Pioneer*' \
    --tests '*CarPlayQueuedRecoveryTest' \
    --tests '*CarPlayControllerUsbServiceTest' \
    --tests '*CarPlayHostSettingsTest' \
    --tests '*SmoothVideoHostLifecycleTest' \
    --tests '*Android6CompatibilityTest' \
    :pioneer-adapter:testDebugUnitTest \
    :mobile:assembleDebug --console=plain --stacktrace

python3 - "$with_baseline" <<'PY'
from pathlib import Path
import sys
import unittest

files = [
    Path('wecarflow/tests/test_runtime.py'),
    Path('wecarflow/tests/test_guard_notice.py'),
    Path('wecarflow/tests/test_instrument_bridge.py'),
]
if sys.argv[1] == 'true':
    files.extend([
        Path('wecarflow/tests/test_instrument_hooks.py'),
        Path('wecarflow/tests/test_patch_build.py'),
        Path('wecarflow/scripts/tests/test_playback_hooks.py'),
    ])
for file in files:
    if not file.is_file():
        raise SystemExit(f'Required regression file is missing: {file}')
    suite = unittest.TestLoader().discover(str(file.parent.resolve()), pattern=file.name)
    if suite.countTestCases() == 0:
        raise SystemExit(f'No regression tests discovered in {file}')
    if not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful():
        raise SystemExit(1)
PY
if ! $with_baseline; then
    printf '%s\n' 'Private baseline patch checks are outside this portable run; use --with-wecarflow-baseline locally.'
fi
printf '%s\n' 'Vehicle adaptation regression passed. Source-only APKs are for build checks, not car installation.'
