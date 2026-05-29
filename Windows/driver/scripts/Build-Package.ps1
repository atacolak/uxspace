<#
.SYNOPSIS
    Post-build packaging for UxSpaceDriver: stamps the INF, builds the .cat,
    and test-signs both the .dll and .cat with a self-signed cert.

.DESCRIPTION
    The VSIX-supplied WindowsDriver.Common.targets normally chains StampInf +
    Inf2Cat + SignTool automatically. We skip that targets file (needs the
    VSIX-only Microsoft.DriverKit.Build.Tasks DLL) so this script does the
    same work explicitly.

    Output layout under $OutDir (default driver\x64\Debug\package\):
        UxSpaceDriver.dll        ← signed
        UxSpaceDriver.inf        ← DriverVer-stamped
        UxSpaceDriver.cat        ← signed catalog

    The driver/dir from this script's parent path is the package input; only
    the .dll and the .inf are needed alongside.

.PARAMETER Configuration
    Debug or Release. Default Debug.

.PARAMETER CertSubject
    Subject CN of the signing cert. Default "CN=UxSpaceDriver Test".
    Created on first run; reused on subsequent runs.

.PARAMETER WdkVersion
    WDK build folder version. Default 10.0.26100.0.

.EXAMPLE
    .\Build-Package.ps1
    .\Build-Package.ps1 -Configuration Release
#>
[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Debug',
    [string]$CertSubject   = 'CN=UxSpaceDriver Test',
    [string]$WdkVersion    = '10.0.26100.0'
)

$ErrorActionPreference = 'Stop'

$driverRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$buildOut   = Join-Path $driverRoot "x64\$Configuration"
$pkgDir     = Join-Path $buildOut 'package'

# --- 1. Locate WDK tools ----------------------------------------------------
$wdkBin = "${env:ProgramFiles(x86)}\Windows Kits\10\bin\$WdkVersion\x64"
$stampinf = Join-Path $wdkBin 'stampinf.exe'
$inf2cat  = "${env:ProgramFiles(x86)}\Windows Kits\10\bin\$WdkVersion\x86\Inf2Cat.exe"
$signtool = Join-Path $wdkBin 'signtool.exe'

foreach ($t in @($stampinf, $inf2cat, $signtool)) {
    if (-not (Test-Path $t)) { throw "Missing WDK tool: $t" }
}

# --- 2. Verify built dll ---------------------------------------------------
$srcDll = Join-Path $buildOut 'UxSpaceDriver.dll'
$srcInf = Join-Path $driverRoot 'UxSpaceDriver.inf'
if (-not (Test-Path $srcDll)) { throw "Driver .dll not built: $srcDll. Run MSBuild first." }
if (-not (Test-Path $srcInf)) { throw "INF not found at $srcInf." }

# --- 3. Stage into package dir ---------------------------------------------
if (Test-Path $pkgDir) { Remove-Item $pkgDir -Recurse -Force }
New-Item -ItemType Directory -Path $pkgDir | Out-Null
Copy-Item $srcDll $pkgDir
Copy-Item $srcInf $pkgDir
$pkgInf = Join-Path $pkgDir 'UxSpaceDriver.inf'
$pkgDll = Join-Path $pkgDir 'UxSpaceDriver.dll'
$pkgCat = Join-Path $pkgDir 'UxSpaceDriver.cat'

# --- 4. Stamp INF DriverVer ------------------------------------------------
Write-Host "[stampinf] DriverVer + KMDF/UMDF version into $pkgInf" -ForegroundColor Cyan
& $stampinf -f $pkgInf -d '*' -a 'amd64' -v '*' -k '1.33' -u '2.23.0'
if ($LASTEXITCODE -ne 0) { throw "stampinf failed: $LASTEXITCODE" }

# --- 5. Generate catalog ---------------------------------------------------
Write-Host "[inf2cat] Generating UxSpaceDriver.cat" -ForegroundColor Cyan
& $inf2cat /driver:"$pkgDir" /os:10_X64,Server10_X64 /uselocaltime
if ($LASTEXITCODE -ne 0) { throw "inf2cat failed: $LASTEXITCODE" }
if (-not (Test-Path $pkgCat)) { throw "Inf2Cat did not produce $pkgCat." }

# --- 6. Find or create the test signing cert -------------------------------
$cert = Get-ChildItem 'Cert:\CurrentUser\My' |
        Where-Object { $_.Subject -eq $CertSubject -and $_.NotAfter -gt (Get-Date) } |
        Select-Object -First 1
if (-not $cert) {
    Write-Host "[cert] No cert with subject '$CertSubject'; creating one (5y validity)" -ForegroundColor Yellow
    $cert = New-SelfSignedCertificate `
        -Subject $CertSubject `
        -Type CodeSigningCert `
        -KeyUsage DigitalSignature `
        -KeyAlgorithm RSA `
        -KeyLength 2048 `
        -HashAlgorithm SHA256 `
        -CertStoreLocation 'Cert:\CurrentUser\My' `
        -NotAfter (Get-Date).AddYears(5)

    # Trust the cert: put a copy in LocalMachine\Root and \TrustedPublisher so
    # the OS validates the signed driver without prompting. Needs admin.
    $tmpCer = Join-Path $env:TEMP "$($cert.Thumbprint).cer"
    Export-Certificate -Cert $cert -FilePath $tmpCer | Out-Null
    Write-Host "[cert] Importing into LocalMachine\Root + TrustedPublisher (UAC)" -ForegroundColor Yellow
    $importScript = @"
Import-Certificate -FilePath '$tmpCer' -CertStoreLocation 'Cert:\LocalMachine\Root' | Out-Null
Import-Certificate -FilePath '$tmpCer' -CertStoreLocation 'Cert:\LocalMachine\TrustedPublisher' | Out-Null
"@
    $importPs1 = Join-Path $env:TEMP "uxspace-cert-import.ps1"
    Set-Content -Path $importPs1 -Value $importScript -Encoding ASCII
    Start-Process powershell.exe -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $importPs1 -Verb RunAs -Wait
}
Write-Host "[cert] Using $($cert.Subject) thumbprint=$($cert.Thumbprint)" -ForegroundColor Green

# --- 7. Sign .cat and .dll -------------------------------------------------
$tsa = 'http://timestamp.digicert.com'
foreach ($f in @($pkgCat, $pkgDll)) {
    Write-Host "[signtool] $f" -ForegroundColor Cyan
    & $signtool sign /sha1 $cert.Thumbprint /fd SHA256 /tr $tsa /td SHA256 $f
    if ($LASTEXITCODE -ne 0) { throw "signtool failed for $f" }
}

# --- 8. Verify ------------------------------------------------------------
& $signtool verify /pa /v $pkgCat
& $signtool verify /pa /v $pkgDll

Write-Host ""
Write-Host "Package ready at: $pkgDir" -ForegroundColor Green
Get-ChildItem $pkgDir | Format-Table Name, Length -AutoSize
