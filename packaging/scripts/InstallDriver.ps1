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

# Test signing must be on. If not, fail cleanly with instructions.
$bcd = bcdedit /enum '{current}' | Out-String
if ($bcd -notmatch 'testsigning\s+Yes') {
    Log "ERROR: Test signing is not enabled."
    Write-Host @"

  Test signing is not enabled on this machine.
  Run an Administrator PowerShell:
      bcdedit /set testsigning on
  Reboot, then re-run this installer.

"@ -ForegroundColor Red
    exit 1
}
Log "Test signing: ON"

# Trust the self-signed cert in LocalMachine\Root and TrustedPublisher
# so the OS validates the driver's catalogue without prompting.
$cer = Join-Path $here 'UxSpaceDriver.cer'
Import-Certificate -FilePath $cer -CertStoreLocation 'Cert:\LocalMachine\Root' | Out-Null
Import-Certificate -FilePath $cer -CertStoreLocation 'Cert:\LocalMachine\TrustedPublisher' | Out-Null
Log "Cert imported into Root + TrustedPublisher."

# Stage driver into the driver store.
$inf = Join-Path $here 'UxSpaceDriver.inf'
pnputil /add-driver $inf /install
# pnputil returns 259 (ERROR_NO_MORE_ITEMS) when there's no matching PnP
# device for the driver — fine here because we install a root devnode
# explicitly in the next step.
if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 259) {
    Log "ERROR: pnputil /add-driver failed: $LASTEXITCODE"
    throw "pnputil /add-driver failed with $LASTEXITCODE"
}
Log "pnputil add-driver done."

# Create the root devnode that materialises the IddCx adapter. Without
# this the driver is staged but no virtual monitor appears.
$devcon = Join-Path $here 'devcon.exe'
& $devcon install $inf 'Root\UxSpaceDriver'
if ($LASTEXITCODE -ne 0) {
    Log "ERROR: devcon install returned $LASTEXITCODE"
    throw "devcon install failed: $LASTEXITCODE"
}
Log "devcon install done."

Log "Install complete."
exit 0
