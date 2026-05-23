# Driver MSI deferred custom action for uninstall — runs as SYSTEM with
# the install dir as CWD. Removes the IddCx root devnode and the staged
# driver package; leaves the certificate trusts in place (cheap to
# re-import on the next install, removing them risks invalidating other
# things the user has signed).

$ErrorActionPreference = 'Continue'  # best-effort: keep going on errors
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

$log = Join-Path $env:TEMP 'UxSpaceDriver-uninstall.log'
function Log($msg) {
    $stamp = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    "$stamp $msg" | Out-File -FilePath $log -Append -Encoding utf8
}

Log "Uninstall begin. CWD=$here"

# Remove the root devnode.
$devcon = Join-Path $here 'devcon.exe'
if (Test-Path $devcon) {
    & $devcon remove 'Root\UxSpaceDriver' 2>&1 | ForEach-Object { Log "[devcon] $_" }
}

# Find the OEM-renamed inf in the driver store and delete it. pnputil
# renames added drivers to oem<N>.inf; we look for the one whose
# Original Name is UxSpaceDriver.inf.
$enum = pnputil /enum-drivers
$publishedName = $null
$collecting = $false
$current = $null
foreach ($line in $enum) {
    if ($line -match '^Published Name:\s+(\S+)') {
        $current = $matches[1]
    } elseif ($line -match '^Original Name:\s+(\S+)') {
        if ($matches[1] -ieq 'UxSpaceDriver.inf' -and $current) {
            $publishedName = $current
            break
        }
    }
}
if ($publishedName) {
    Log "Deleting driver $publishedName from store."
    pnputil /delete-driver $publishedName /uninstall /force 2>&1 | ForEach-Object { Log "[pnputil] $_" }
} else {
    Log "No UxSpaceDriver.inf entry found in store."
}

Log "Uninstall complete."
exit 0
