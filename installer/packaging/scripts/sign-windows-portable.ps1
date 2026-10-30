param(
    [string]$PfxPath = "",
    [string]$PasswordFile = "",
    [string]$TimestampUrl = "http://timestamp.digicert.com",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$packaging = Split-Path $PSScriptRoot -Parent
$repo = (Resolve-Path (Join-Path $packaging "..\..")).Path
if ([string]::IsNullOrWhiteSpace($PfxPath)) {
    $PfxPath = Join-Path $packaging ".signing\DiPlayInstaller-SelfSigned.pfx"
}
if ([string]::IsNullOrWhiteSpace($PasswordFile)) {
    $PasswordFile = Join-Path $packaging ".signing\password.txt"
}
if (!(Test-Path $PfxPath)) { throw "PFX not found: $PfxPath" }
if (!(Test-Path $PasswordFile)) { throw "Password file not found: $PasswordFile" }

$password = (Get-Content -Raw -Path $PasswordFile).Trim()
$secure = ConvertTo-SecureString -String $password -AsPlainText -Force
$cert = Import-PfxCertificate -FilePath $PfxPath -CertStoreLocation "Cert:\CurrentUser\My" -Password $secure -Exportable

try {
    if (-not $cert.HasPrivateKey) { throw "Imported certificate has no private key" }
    $gradlew = Join-Path $repo "gradlew.bat"

    if (-not $SkipBuild) {
        & $gradlew --configure-on-demand :installer-desktop:createDistributable
        if ($LASTEXITCODE -ne 0) { throw "createDistributable failed" }
    }

    $exe = Join-Path $repo "installer\desktop\build\compose\binaries\main\app\DiPlayInstaller\DiPlayInstaller.exe"
    if (!(Test-Path $exe)) { throw "Launcher not found: $exe" }
    Set-ItemProperty -Path $exe -Name IsReadOnly -Value $false

    try {
        $signature = Set-AuthenticodeSignature -FilePath $exe -Certificate $cert `
            -TimestampServer $TimestampUrl -HashAlgorithm SHA256
    } catch {
        Write-Warning "Timestamp failed, signing without timestamp: $($_.Exception.Message)"
        $signature = Set-AuthenticodeSignature -FilePath $exe -Certificate $cert -HashAlgorithm SHA256
    }
    if (-not $signature.SignerCertificate) {
        throw "Signing failed: $($signature.StatusMessage)"
    }
    Write-Host "Signed launcher: $exe"
    Write-Host "  Signer:    $($signature.SignerCertificate.Subject)"
    Write-Host "  Thumbprint:$($signature.SignerCertificate.Thumbprint)"
    Write-Host "  Status:    $($signature.Status) - $($signature.StatusMessage)"

    $portableDir = Join-Path $repo "installer\desktop\build\portable"
    Get-ChildItem (Join-Path $portableDir "*.zip") -ErrorAction SilentlyContinue | Remove-Item -Force
    & $gradlew --configure-on-demand :installer-desktop:packagePortableZip -x :installer-desktop:createDistributable
    if ($LASTEXITCODE -ne 0) { throw "packagePortableZip failed" }

    $zip = Get-ChildItem (Join-Path $repo "installer\desktop\build\portable\*.zip") |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $zip) { throw "Portable ZIP not found" }

    $cer = Join-Path $packaging ".signing\DiPlayInstaller-SelfSigned.cer"
    if (Test-Path $cer) {
        Copy-Item $cer (Join-Path $repo "installer\desktop\build\portable") -Force
    }
    Write-Host "Portable ZIP: $($zip.FullName)"

    $temp = Join-Path $env:TEMP ("diplay-sign-check-" + [Guid]::NewGuid().ToString("N"))
    try {
        Expand-Archive -Path $zip.FullName -DestinationPath $temp -Force
        $inner = Get-ChildItem $temp -Recurse -Filter "DiPlayInstaller.exe" | Select-Object -First 1
        if ($inner) {
            $check = Get-AuthenticodeSignature -FilePath $inner.FullName
            Write-Host "ZIP launcher signer: $($check.SignerCertificate.Subject)"
            Write-Host "ZIP launcher status: $($check.Status) - $($check.StatusMessage)"
        }
    } finally {
        Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue
    }
} finally {
    Remove-Item "Cert:\CurrentUser\My\$($cert.Thumbprint)" -ErrorAction SilentlyContinue
}
