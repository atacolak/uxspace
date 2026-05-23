<#
.SYNOPSIS
    Builds Release configs of :app and :driver, signs the driver,
    authors two MSIs (driver-only + app-only), and delivers them at
    $OutputDir (default X:\Work\Programming).

.DESCRIPTION
    End-to-end pipeline:
      1. cmake --build build --target uxspace_app  --config Release
      2. cmake --build build --target UxSpaceDriver --config Release
         (the driver vcxproj is pulled into the .sln via
         include_external_msproject, so cmake's --target works.)
      3. driver/scripts/Build-Package.ps1 -Configuration Release
         (stampinf + inf2cat + signtool, reusing the existing cert)
      4. Export-Certificate to produce the public .cer the laptop will
         trust during MSI install.
      5. Copy devcon.exe + the install/uninstall PS1 scripts into the
         driver package dir alongside the signed binaries.
      6. wix build for each .wxs.
      7. Copy both .msi files to $OutputDir with a timestamp suffix.

.PARAMETER OutputDir
    Where the two MSIs are dropped. Default X:\Work\Programming.

.PARAMETER VersionLabel
    Suffix on the MSI filename, default yyyyMMdd-HHmm.

.PARAMETER SkipBuild
    Use already-built Release artifacts; only re-run the WiX step.
    Useful while iterating on the .wxs files.
#>
[CmdletBinding()]
param(
    [string]$OutputDir    = 'X:\Work\Programming',
    [string]$VersionLabel = (Get-Date -Format 'yyyyMMdd-HHmm'),
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$root        = Resolve-Path "$PSScriptRoot\.."
$cmakeBuild  = Join-Path $root 'build'
$pkgDir      = Join-Path $root 'packaging'
$wixSrcDir   = Join-Path $pkgDir 'wix'
$scriptsDir  = Join-Path $pkgDir 'scripts'
$wixOutDir   = Join-Path $pkgDir 'build'
$driverPkg   = Join-Path $root 'driver\x64\Release\package'
$appBinDir   = Join-Path $root 'build\bin\Release'

# MSI ProductVersion = major.minor.build, max 255.255.65535.
# Use year-2025 . month . (day*100 + hour) — monotonically increasing
# at hourly granularity, fits the field, gives MajorUpgrade a stable
# ordering.
$now      = Get-Date
$msiMajor = $now.Year - 2025
$msiMinor = $now.Month
$msiBuild = $now.Day * 100 + $now.Hour
$msiVer   = "$msiMajor.$msiMinor.$msiBuild"

Write-Host "[plan] MSI version : $msiVer"
Write-Host "[plan] Label       : v$VersionLabel"
Write-Host "[plan] OutputDir   : $OutputDir"

if (-not $SkipBuild) {
    Write-Host ""
    Write-Host "[1/6] cmake --build (app, Release)" -ForegroundColor Cyan
    & cmake --build $cmakeBuild --target uxspace_app --config Release
    if ($LASTEXITCODE -ne 0) { throw "App Release build failed." }

    # The driver vcxproj uses the WindowsUserModeDriver10.0 toolset,
    # which is registered ONLY with VS Community/Pro/Enterprise IDE
    # installations (a VSIX dropped by the WDK MSI). VS Build Tools
    # does not get it — see CLAUDE.md "Local dev setup landmines".
    # `cmake --build` happily finds Build Tools' MSBuild first, so we
    # invoke Community's MSBuild explicitly via vswhere.
    $vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path $vswhere)) { throw "vswhere.exe not found: $vswhere" }
    $communityRoot = & $vswhere -products Microsoft.VisualStudio.Product.Community -property installationPath -version "[17.0,)"
    if (-not $communityRoot) { throw "VS Community 2022 not found via vswhere." }
    $communityRoot = $communityRoot.Trim()
    $communityMsbuild = Join-Path $communityRoot 'MSBuild\Current\Bin\MSBuild.exe'
    if (-not (Test-Path $communityMsbuild)) { throw "Community MSBuild not at $communityMsbuild" }

    Write-Host ""
    Write-Host "[2/6] MSBuild (driver, Release) via $communityMsbuild" -ForegroundColor Cyan
    & $communityMsbuild "$root\driver\UxSpaceDriver.vcxproj" `
        /p:Configuration=Release /p:Platform=x64 /v:minimal /nologo
    if ($LASTEXITCODE -ne 0) { throw "Driver Release build failed." }

    Write-Host ""
    Write-Host "[3/6] sign driver package" -ForegroundColor Cyan
    & "$root\driver\scripts\Build-Package.ps1" -Configuration Release
    if ($LASTEXITCODE -ne 0) { throw "driver/scripts/Build-Package.ps1 failed." }
} else {
    Write-Host "[skip] Release builds reused (-SkipBuild)." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "[4/6] export .cer + stage devcon + scripts into $driverPkg" -ForegroundColor Cyan
$cert = Get-ChildItem 'Cert:\CurrentUser\My' |
        Where-Object { $_.Subject -eq 'CN=UxSpaceDriver Test' -and $_.NotAfter -gt $now } |
        Select-Object -First 1
if (-not $cert) { throw "Signing cert 'CN=UxSpaceDriver Test' not found." }
$cerOut = Join-Path $driverPkg 'UxSpaceDriver.cer'
Export-Certificate -Cert $cert -FilePath $cerOut -Force | Out-Null

$devconSrc = "${env:ProgramFiles(x86)}\Windows Kits\10\Tools\10.0.26100.0\x64\devcon.exe"
if (-not (Test-Path $devconSrc)) {
    throw "devcon.exe not found at $devconSrc (WDK 10.0.26100.0 required)."
}
Copy-Item $devconSrc $driverPkg -Force
Copy-Item "$scriptsDir\InstallDriver.ps1"   $driverPkg -Force
Copy-Item "$scriptsDir\UninstallDriver.ps1" $driverPkg -Force

Write-Host ""
Write-Host "[5/6] wix build (driver + app)" -ForegroundColor Cyan
if (Test-Path $wixOutDir) { Remove-Item $wixOutDir -Recurse -Force }
New-Item -ItemType Directory $wixOutDir | Out-Null

$driverMsi = Join-Path $wixOutDir "UxSpaceDriver-v$VersionLabel.msi"
$appMsi    = Join-Path $wixOutDir "UxSpaceApp-v$VersionLabel.msi"

# -arch x64 marks the MSI as 64-bit so ProgramFiles64Folder resolves
# to "C:\Program Files\..." instead of being redirected by WoW64 to
# "C:\Program Files (x86)\...". Driver components (Bitness="always64")
# need this. App doesn't strictly need it (it installs under
# LocalAppDataFolder) but staying consistent costs nothing.
& wix build "$wixSrcDir\UxSpaceDriver.wxs" `
    -arch x64 `
    -d "Version=$msiVer" `
    -d "DriverDir=$driverPkg" `
    -o $driverMsi
if ($LASTEXITCODE -ne 0) { throw "Driver MSI build failed." }

& wix build "$wixSrcDir\UxSpaceApp.wxs" `
    -arch x64 `
    -d "Version=$msiVer" `
    -d "AppDir=$appBinDir" `
    -o $appMsi
if ($LASTEXITCODE -ne 0) { throw "App MSI build failed." }

Write-Host ""
Write-Host "[6/6] deliver to $OutputDir" -ForegroundColor Cyan
if (-not (Test-Path $OutputDir)) { New-Item -ItemType Directory $OutputDir | Out-Null }
Copy-Item $driverMsi $OutputDir -Force
Copy-Item $appMsi    $OutputDir -Force

Write-Host ""
Write-Host "Done." -ForegroundColor Green
Get-ChildItem $OutputDir -Filter "UxSpace*-v$VersionLabel.msi" |
    Format-Table Name, Length, LastWriteTime -AutoSize
