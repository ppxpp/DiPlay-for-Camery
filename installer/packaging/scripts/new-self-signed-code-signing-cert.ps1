param(
    [string]$OutDir = "",
    [string]$Subject = "CN=DiPlay Installer (Self-Signed), O=DiPlay",
    [int]$Years = 3
)

$ErrorActionPreference = "Stop"
$packaging = Split-Path $PSScriptRoot -Parent
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $packaging ".signing"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$password = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
$passwordFile = Join-Path $OutDir "password.txt"
Set-Content -NoNewline -Path $passwordFile -Value $password

$cert = New-SelfSignedCertificate `
    -Type CodeSigningCert `
    -Subject $Subject `
    -KeyAlgorithm RSA `
    -KeyLength 3072 `
    -HashAlgorithm SHA256 `
    -KeyExportPolicy Exportable `
    -KeyUsage DigitalSignature `
    -NotAfter (Get-Date).AddYears($Years) `
    -CertStoreLocation "Cert:\CurrentUser\My" `
    -FriendlyName "DiPlay Installer Self-Signed Code Signing"

$secure = ConvertTo-SecureString -String $password -AsPlainText -Force
$pfx = Join-Path $OutDir "DiPlayInstaller-SelfSigned.pfx"
$cer = Join-Path $OutDir "DiPlayInstaller-SelfSigned.cer"
Export-PfxCertificate -Cert $cert -FilePath $pfx -Password $secure | Out-Null
Export-Certificate -Cert $cert -FilePath $cer | Out-Null
Remove-Item "Cert:\CurrentUser\My\$($cert.Thumbprint)" -ErrorAction SilentlyContinue

Write-Host "Created self-signed code signing certificate"
Write-Host "  Subject:    $($cert.Subject)"
Write-Host "  Thumbprint: $($cert.Thumbprint)"
Write-Host "  PFX:        $pfx"
Write-Host "  CER:        $cer"
Write-Host "  Password:   $passwordFile"
Write-Host ""
Write-Host "Keep .signing private and out of Git. Back it up if you need a stable publisher identity."
