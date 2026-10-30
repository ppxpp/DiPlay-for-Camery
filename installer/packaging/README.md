# Installer packaging assets

This directory holds non-code assets used to package the desktop installer.

Planned layout:

```text
packaging/
├─ platform-tools/
│  ├─ windows/        # adb.exe, AdbWinApi.dll, AdbWinUsbApi.dll
│  └─ macos/          # adb (universal x86_64 + arm64)
├─ icons/
├─ licenses/
└─ scripts/           # portable launcher / update helper
```

Do not commit the platform-tools binaries directly. Run:

```bash
installer/packaging/fetch-platform-tools.sh
```

The script downloads the pinned `platform-tools` archives, verifies SHA-256, and
populates `resources/windows/platform-tools` and `resources/macos/platform-tools`.
The desktop build copies the matching OS directory into `app/resources`; at
runtime `DesktopAdbPaths` looks for `platform-tools/adb[.exe]` in
`compose.application.resources.dir`.
The macOS `adb` binary is universal (x86_64 + arm64); the Windows binary runs on
Windows 10+ x64.

## Build

```bash
bash installer/packaging/fetch-platform-tools.sh
./gradlew --configure-on-demand :installer-desktop:packagePortableZip
```

Output: `installer/desktop/build/portable/DiPlayInstaller-0.2.0-<os>.zip`.

CI workflows:

- `.github/workflows/build-installer-windows.yml` (Windows x64)
- `.github/workflows/build-installer-macos.yml` (macOS x64 + arm64)

Windows signing is optional and activates when `WINDOWS_SIGNING_PFX_BASE64` and
`WINDOWS_SIGNING_PFX_PASSWORD` are configured (a self-signed PFX also works).
Unsigned portable executables may trigger SmartScreen. macOS builds produced by
CI are unsigned; distribution to end users still needs a Developer ID signature
and notarization.

### Self-signed signing (internal use)

```powershell
powershell -ExecutionPolicy Bypass -File installer/packaging/scripts/new-self-signed-code-signing-cert.ps1
powershell -ExecutionPolicy Bypass -File installer/packaging/scripts/sign-windows-portable.ps1
```

The certificate, PFX, and password are written to `installer/packaging/.signing`
and are ignored by Git. Back this directory up if future releases must keep the
same publisher identity.

The resulting exe is signed but its self-signed root is not trusted on other
machines; Windows may still show SmartScreen or "unknown publisher". For
internal machines, install the generated `.cer` explicitly:

```powershell
powershell -ExecutionPolicy Bypass -File installer/packaging/scripts/trust-self-signed-cert.ps1
```

Add `-LocalMachine` (elevated) to trust it for all users. Do not use a
self-signed certificate for public distribution.

To reuse the local self-signed PFX in the Windows CI workflow, set the two
repository secrets (never commit the PFX or password):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("installer/packaging/.signing/DiPlayInstaller-SelfSigned.pfx")) |
  gh secret set WINDOWS_SIGNING_PFX_BASE64
Get-Content -Raw "installer/packaging/.signing/password.txt" |
  gh secret set WINDOWS_SIGNING_PFX_PASSWORD
```

The workflow exports the signing certificate as
`DiPlayInstaller-Signing.cer` next to the ZIP for internal trust installation.
The workflow must be committed/pushed to the branch before it can be run from
GitHub Actions.

## Runtime notes

- Windows 10 21H2+ x64 or Windows 11 x64; macOS 11+. CI builds on
  `windows-latest`, so a manual Windows 10 smoke test is still recommended.
- Desktop builds do not auto-update; replace the portable directory manually.
- The default backup folder is `<Desktop>/DiPlay 备份` on Windows and macOS. It
  can be changed under Settings → 备份位置.
- The Windows build shares the system adb server/keys on port 5037. Close other
  adb-heavy tools if USB enumeration conflicts.
- A file named `portable` next to `DiPlayInstaller.exe` switches settings/cache
  to `<program directory>/data`; backups still use the configured folder.
