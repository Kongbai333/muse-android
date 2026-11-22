param([string]$JavaHome = $env:JAVA_HOME, [string]$ForeignEnvelope = '')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).ProviderPath
$suffix = if ([Environment]::OSVersion.Platform -eq 'Win32NT') { '.exe' } else { '' }
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome ('bin/javac' + $suffix)))) { throw 'Set JavaHome to JDK 21 (not a JRE).' }
$out = Join-Path $repo 'app/build/transferChecks'
$null = New-Item -ItemType Directory -Path $out -Force
$base = Join-Path $repo 'app/src/main/java/com/muse/workspace/mobile'
$sources = @((Join-Path $base 'AccountTransferCrypto.java'), (Join-Path $repo 'checks/AccountTransferCryptoCheck.java'))
if (Test-Path -LiteralPath (Join-Path $repo 'checks/AccountTransferBundleCheck.java')) {
    $sources += @('AccountTransferJson.java','AccountTransferBundle.java','AccountTransferCookieText.java','ProxySettings.java') | ForEach-Object { Join-Path $base $_ }
    $sources += Join-Path $repo 'checks/AccountTransferBundleCheck.java'
}
if (Test-Path -LiteralPath (Join-Path $repo 'checks/AccountTransferFlowCheck.java')) {
    $sources += @((Join-Path $base 'AccountTransferFlow.java'),(Join-Path $repo 'checks/AccountTransferFlowCheck.java'))
}
& (Join-Path $JavaHome ('bin/javac' + $suffix)) -encoding UTF-8 -d $out @sources
if ($LASTEXITCODE -ne 0) { throw 'Transfer JCA check compilation failed.' }
Push-Location -LiteralPath $repo
try {
    $options = @('-cp', $out, 'com.muse.workspace.mobile.AccountTransferCryptoCheck', $out)
    if ($ForeignEnvelope) { $options += $ForeignEnvelope }
    & (Join-Path $JavaHome ('bin/java' + $suffix)) @options
    if ($LASTEXITCODE -ne 0) { throw 'Transfer JCA checks failed.' }
    if (Test-Path -LiteralPath (Join-Path $repo 'checks/AccountTransferBundleCheck.java')) {
        & (Join-Path $JavaHome ('bin/java' + $suffix)) -cp $out com.muse.workspace.mobile.AccountTransferBundleCheck
        if ($LASTEXITCODE -ne 0) { throw 'Transfer schema/Cookie checks failed.' }
    }
    if (Test-Path -LiteralPath (Join-Path $repo 'checks/AccountTransferFlowCheck.java')) {
        & (Join-Path $JavaHome ('bin/java' + $suffix)) -cp $out com.muse.workspace.mobile.AccountTransferFlowCheck
        if ($LASTEXITCODE -ne 0) { throw 'Transfer activation checks failed.' }
    }
} finally { Pop-Location }
