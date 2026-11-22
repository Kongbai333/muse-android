param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).ProviderPath
$suffix = if ([Environment]::OSVersion.Platform -eq 'Win32NT') { '.exe' } else { '' }
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome ('bin/javac' + $suffix)))) { throw 'Set JavaHome to JDK 21.' }
$out = Join-Path $repo 'app/build/transferNativeChecks'
$null = New-Item -ItemType Directory -Path $out -Force
$base = Join-Path $repo 'app/src/main/java/com/muse/workspace/mobile'
$sources = @('AccountTransferCrypto.java','AccountTransferJson.java','AccountTransferBundle.java','AccountTransferCookieText.java','AccountTransferCookies.java','ProxySettings.java') | ForEach-Object { Join-Path $base $_ }
$sources += @(Get-ChildItem -LiteralPath (Join-Path $repo 'checks/transfer-native') -Recurse -File -Filter '*.java' | ForEach-Object { $_.FullName })
& (Join-Path $JavaHome ('bin/javac' + $suffix)) -encoding UTF-8 -d $out @sources
if ($LASTEXITCODE -ne 0) { throw 'Native-boundary check compilation failed.' }
& (Join-Path $JavaHome ('bin/java' + $suffix)) -cp $out com.muse.workspace.mobile.AccountTransferNativeBoundaryCheck
if ($LASTEXITCODE -ne 0) { throw 'Native-boundary checks failed.' }
