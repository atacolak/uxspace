<#
.SYNOPSIS
    Installs UxSpaceDriver into the driver store and creates the root devnode.

.DESCRIPTION
    Requires test-signing enabled (bcdedit /set testsigning on + reboot). The
    package must already be signed via Build-Package.ps1.

    On success a "UxSpace Virtual Display" entry appears in Device Manager
    under "Display adapters", and a new monitor shows up in Display Settings.

.PARAMETER Configuration
    Debug or Release. Default Debug.
#>
#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Debug'
)

$ErrorActionPreference = 'Stop'

# Sanity: test-signing must be on, otherwise the OS will silently refuse the driver.
$bcd = bcdedit /enum '{current}' | Out-String
if ($bcd -notmatch 'testsigning\s+Yes') {
    throw "Test-signing is not enabled. Run: bcdedit /set testsigning on  ; reboot; retry."
}

$driverRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$pkgDir     = Join-Path $driverRoot "x64\$Configuration\package"
$pkgInf     = Join-Path $pkgDir 'UxSpaceDriver.inf'
$pkgCat     = Join-Path $pkgDir 'UxSpaceDriver.cat'
$pkgDll     = Join-Path $pkgDir 'UxSpaceDriver.dll'
foreach ($f in @($pkgInf, $pkgCat, $pkgDll)) {
    if (-not (Test-Path $f)) { throw "Missing $f. Run Build-Package.ps1 first." }
}

$devcon = "${env:ProgramFiles(x86)}\Windows Kits\10\Tools\10.0.26100.0\x64\devcon.exe"
if (-not (Test-Path $devcon)) { throw "devcon.exe not found at $devcon." }

Write-Host "[pnputil] Adding driver package to store..." -ForegroundColor Cyan
pnputil /add-driver $pkgInf /install
if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 259) {  # 259 = ERROR_NO_MORE_ITEMS = "no matching device"
    throw "pnputil /add-driver failed: $LASTEXITCODE"
}

Write-Host "[devcon] Creating root devnode Root\UxSpaceDriver..." -ForegroundColor Cyan
& $devcon install $pkgInf 'Root\UxSpaceDriver'
if ($LASTEXITCODE -ne 0) {
    throw "devcon install failed: $LASTEXITCODE. Check Event Viewer -> Windows Logs -> System for details."
}

Write-Host ""
Write-Host "Driver installed." -ForegroundColor Green
Write-Host "Check: Display Settings should now show an extra 'UxSpace Virtual Display' monitor." -ForegroundColor Green
Write-Host "Check: Device Manager -> Display adapters should list 'UxSpace Virtual Display'." -ForegroundColor Green
