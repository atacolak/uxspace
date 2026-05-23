# Driver MSI deferred custom action — runs as SYSTEM with the install
# directory as CWD. The MSI guarantees all package files are present in
# the same directory by the time this runs (After="InstallFiles").

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

$log = Join-Path $env:TEMP 'UxSpaceDriver-install.log'
function Log($msg) {
    $stamp = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    "$stamp $msg" | Out-File -FilePath $log -Append -Encoding utf8
    Write-Host $msg
}

Log "Install begin. CWD=$here"

# Best-effort test-signing diagnostic — log it but don't gate the
# install on it. The PowerShell-vs-bcdedit `{current}` tokenisation is
# fragile across shells/contexts; if testsigning is actually off,
# pnputil below will reject the package with a meaningful error and we
# get a cleaner failure than misreporting here.
try {
    $bcd = & 'C:\Windows\System32\cmd.exe' /c 'bcdedit /enum {current}' 2>&1 | Out-String
    if ($bcd -match 'testsigning\s+Yes') {
        Log "Test signing: ON (per bcdedit)."
    } elseif ($bcd -match 'testsigning\s+No') {
        Log "Test signing: OFF (per bcdedit). Driver install will likely fail; bcdedit /set testsigning on + reboot."
    } else {
        Log ("Test signing: could not determine from bcdedit. Raw output: " +
             ($bcd -replace '\r?\n', ' | ').Substring(0, [Math]::Min(200, $bcd.Length)))
    }
} catch {
    Log "Test signing: bcdedit invocation threw: $_"
}

# Trust the self-signed cert in LocalMachine\Root and TrustedPublisher
# so the OS validates the driver's catalogue without prompting.
$cer = Join-Path $here 'UxSpaceDriver.cer'
Import-Certificate -FilePath $cer -CertStoreLocation 'Cert:\LocalMachine\Root' | Out-Null
Import-Certificate -FilePath $cer -CertStoreLocation 'Cert:\LocalMachine\TrustedPublisher' | Out-Null
Log "Cert imported into Root + TrustedPublisher."

# Self-heal: scrub any prior install before staging the new one. This
# matters because MajorUpgrade between two driver MSIs leaves the old
# OEM-published .inf in the driver store and the running .dll loaded
# in WUDFHost — installing the new MSI's files alone doesn't force a
# rebind to the new binary. Removing the devnode + deleting the prior
# OEM-published .inf forces Windows to load the freshly staged driver
# when we re-add it below.
$devcon = Join-Path $here 'devcon.exe'
if (Test-Path $devcon) {
    & $devcon remove 'Root\UxSpaceDriver' 2>&1 | ForEach-Object { Log "[scrub-devcon] $_" }
}
$enum = pnputil /enum-drivers 2>&1
$currentPub = $null
foreach ($line in $enum) {
    if ($line -match '^Published Name:\s+(\S+)') {
        $currentPub = $matches[1]
    } elseif ($line -match '^Original Name:\s+(\S+)') {
        if ($matches[1] -ieq 'UxSpaceDriver.inf' -and $currentPub) {
            Log "Scrubbing prior OEM-published $currentPub from driver store."
            pnputil /delete-driver $currentPub /uninstall /force 2>&1 | ForEach-Object { Log "[scrub-pnputil] $_" }
        }
    }
}
Start-Sleep -Milliseconds 500

# Stage driver into the driver store. pnputil's stdout names the
# OEM-renamed inf and reports success/failure; capture it in the log so
# we can see what the OS thinks happened.
$inf = Join-Path $here 'UxSpaceDriver.inf'
$pnpOut = & pnputil /add-driver $inf /install 2>&1 | Out-String
Log "pnputil output:"
$pnpOut -split "`r?`n" | ForEach-Object { Log "  $_" }
# pnputil returns 259 (ERROR_NO_MORE_ITEMS) when there's no matching PnP
# device for the driver — fine here because we install a root devnode
# explicitly in the next step.
if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 259) {
    Log "ERROR: pnputil /add-driver failed: exit=$LASTEXITCODE"
    throw "pnputil /add-driver failed with $LASTEXITCODE"
}
Log "pnputil add-driver done (exit=$LASTEXITCODE)."

# Create the root devnode that materialises the IddCx adapter. Without
# this the driver is staged but no virtual monitor appears.
# ($devcon was set above during the scrub block.)
$devOut = & $devcon install $inf 'Root\UxSpaceDriver' 2>&1 | Out-String
Log "devcon output:"
$devOut -split "`r?`n" | ForEach-Object { Log "  $_" }
if ($LASTEXITCODE -ne 0) {
    Log "ERROR: devcon install returned $LASTEXITCODE"
    throw "devcon install failed: $LASTEXITCODE"
}
Log "devcon install done."

Log "Install complete."
exit 0
