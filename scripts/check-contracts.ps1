param([string]$ClasspathFile = (Join-Path $PSScriptRoot '../app/build/contract-check-classpath.txt'))

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).ProviderPath
if (!(Test-Path -LiteralPath $ClasspathFile)) {
    throw 'Run ./gradlew :app:writeContractCheckClasspath before the Java contract checks.'
}
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java' } else { 'java' }
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac' } else { 'javac' }
if ([Environment]::OSVersion.Platform -eq 'Win32NT' -and $env:JAVA_HOME) {
    $java += '.exe'
    $javac += '.exe'
}
$classpath = (Get-Content -LiteralPath $ClasspathFile -Raw).Trim()
$out = Join-Path $repo 'app/build/contractChecks'
$null = New-Item -ItemType Directory -Path $out -Force
$checks = @(Get-ChildItem -LiteralPath (Join-Path $repo 'checks') -Filter '*Check.java' -File)
if (!$checks.Count) { throw 'No Java contract checks found.' }
& $javac -encoding UTF-8 -cp $classpath -d $out @($checks.FullName)
if ($LASTEXITCODE -ne 0) { throw 'Java contract compilation failed.' }
$runtimeClasspath = $out + [IO.Path]::PathSeparator + $classpath
foreach ($check in $checks) {
    & $java -cp $runtimeClasspath "com.muse.workspace.mobile.$($check.BaseName)" $out
    if ($LASTEXITCODE -ne 0) { throw "Contract failed: $($check.BaseName)" }
}
& (Join-Path $repo 'checks/QuickSwitchCheck.ps1') -Java $java
& (Join-Path $PSScriptRoot 'check-account-transfer-native.ps1') -JavaHome $env:JAVA_HOME
Write-Output "All $($checks.Count) Java contract programs and the header-switch checks passed."
