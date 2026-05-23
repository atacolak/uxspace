<#
.SYNOPSIS
    Removes UxSpaceDriver: the root devnode + the driver package in the store.

.DESCRIPTION
    Run elevated. Safe to invoke even if the driver is partially installed.
#>
#Requires -RunAsAdministrator

$ErrorActionPreference = 'Continue'

$devcon = "${env:ProgramFiles(x86)}\Windows Kits\10\Tools\10.0.26100.0\x64\devcon.exe"

# 1. Remove the root devnode (if present).
if (Test-Path $devcon) {
    Write-Host "[devcon] Removing Root\UxSpaceDriver devnode..." -ForegroundColor Cyan
    & $devcon remove 'Root\UxSpaceDriver' | Out-Host
} else {
    Write-Host "[devcon] devcon.exe not found; skipping devnode removal." -ForegroundColor Yellow
}

# 2. Find and remove the driver package from the store.
Write-Host "[pnputil] Searching for UxSpaceDriver.inf in driver store..." -ForegroundColor Cyan
$drivers = pnputil /enum-drivers
$current = $null
$pkgs = @()
foreach ($line in $drivers) {
    if ($line -match '^Published Name:\s*(oem\d+\.inf)') { $current = $matches[1] }
    elseif ($line -match '^Original Name:\s*UxSpaceDriver\.inf' -and $current) { $pkgs += $current; $current = $null }
}

if ($pkgs.Count -eq 0) {
    Write-Host "[pnputil] No UxSpaceDriver.inf in driver store; nothing to remove." -ForegroundColor Yellow
} else {
    foreach ($pkg in $pkgs) {
        Write-Host "[pnputil] Removing $pkg..." -ForegroundColor Cyan
        pnputil /delete-driver $pkg /uninstall /force
    }
}

Write-Host ""
Write-Host "Uninstall complete." -ForegroundColor Green
