<#
.SYNOPSIS
    Creates a System Restore Point before a UxSpace driver install.

.DESCRIPTION
    Wraps Checkpoint-Computer with the right restore-point type and bypasses
    Windows' default 24-hour throttle (otherwise back-to-back driver tests
    silently skip checkpointing).

    Run elevated. The restore point can be rolled back from
    System Restore (rstrui.exe) if a driver install BSODs the box.

.EXAMPLE
    .\Create-RestorePoint.ps1
    .\Create-RestorePoint.ps1 -Description "before W0 install"
#>
#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string]$Description = "UxSpace driver test"
)

$ErrorActionPreference = 'Stop'

# Make sure System Restore is on for the system drive.
Enable-ComputerRestore -Drive $env:SystemDrive

# Disable the 24h frequency throttle for this session only — we want every
# install attempt to checkpoint. Reverts after reboot.
$regKey = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\SystemRestore'
$prev   = (Get-ItemProperty -Path $regKey -Name 'SystemRestorePointCreationFrequency' -ErrorAction SilentlyContinue).SystemRestorePointCreationFrequency
New-ItemProperty -Path $regKey -Name 'SystemRestorePointCreationFrequency' -PropertyType DWord -Value 0 -Force | Out-Null

try {
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm'
    $full  = "UxSpace: $Description ($stamp)"
    Write-Host "Creating restore point: $full"
    Checkpoint-Computer -Description $full -RestorePointType 'APPLICATION_INSTALL'
    Write-Host ""
    Write-Host "Most recent restore points:" -ForegroundColor Green
    Get-ComputerRestorePoint | Sort-Object CreationTime -Descending | Select-Object -First 3 | Format-Table SequenceNumber, CreationTime, Description -AutoSize
}
finally {
    if ($null -ne $prev) {
        Set-ItemProperty -Path $regKey -Name 'SystemRestorePointCreationFrequency' -Value $prev
    } else {
        Remove-ItemProperty -Path $regKey -Name 'SystemRestorePointCreationFrequency' -ErrorAction SilentlyContinue
    }
}
