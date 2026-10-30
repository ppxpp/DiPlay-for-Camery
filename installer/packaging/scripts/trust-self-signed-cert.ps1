param(
    [string]$CerPath = "",
    [switch]$LocalMachine
)

$ErrorActionPreference = "Stop"
$packaging = Split-Path $PSScriptRoot -Parent
if ([string]::IsNullOrWhiteSpace($CerPath)) {
    $CerPath = Join-Path $packaging ".signing\DiPlayInstaller-SelfSigned.cer"
}
if (!(Test-Path $CerPath)) { throw "Certificate not found: $CerPath" }

$store = if ($LocalMachine) { "Cert:\LocalMachine" } else { "Cert:\CurrentUser" }
Import-Certificate -FilePath $CerPath -CertStoreLocation "$store\Root" | Out-Null
Import-Certificate -FilePath $CerPath -CertStoreLocation "$store\TrustedPublisher" | Out-Null

Write-Host "Installed the self-signed certificate into $store (Root + TrustedPublisher)."
Write-Host "This trusts code signed by this certificate on this machine only."
Write-Host "Do not use a self-signed certificate for public distribution."
