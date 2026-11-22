param(
    [string]$Gradle,
    [string]$Aapt2Override,
    [switch]$Offline,
    [switch]$SkipLint
)

$ErrorActionPreference = 'Stop'
if (!$env:JAVA_HOME -or !$env:ANDROID_HOME) { throw 'Set JAVA_HOME and ANDROID_HOME before building.' }
$windows = [Environment]::OSVersion.Platform -eq 'Win32NT'
$buildRoot = Join-Path $PSScriptRoot '.build'
$null = New-Item -ItemType Directory -Path $buildRoot -Force
$keystore = if ($env:MUSE_ANDROID_KEYSTORE) { $env:MUSE_ANDROID_KEYSTORE } else { Join-Path $buildRoot 'preview.keystore' }
if (!(Test-Path -LiteralPath $keystore)) {
    if ($env:MUSE_ANDROID_KEYSTORE) { throw 'The configured signing keystore is missing.' }
    $keytool = Join-Path $env:JAVA_HOME $(if ($windows) { 'bin/keytool.exe' } else { 'bin/keytool' })
    $storePass = if ($env:MUSE_ANDROID_STORE_PASSWORD) { $env:MUSE_ANDROID_STORE_PASSWORD } else { 'android' }
    $keyPass = if ($env:MUSE_ANDROID_KEY_PASSWORD) { $env:MUSE_ANDROID_KEY_PASSWORD } else { 'android' }
    $alias = if ($env:MUSE_ANDROID_KEY_ALIAS) { $env:MUSE_ANDROID_KEY_ALIAS } else { 'muse-preview' }
    & $keytool -genkeypair -keystore $keystore -alias $alias -storepass $storePass -keypass $keyPass `
        -dname 'CN=Muse Android Preview' -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the local preview signing key.' }
}
if (!$Gradle) { $Gradle = Join-Path $PSScriptRoot $(if ($windows) { 'gradlew.bat' } else { 'gradlew' }) }
$options = @('--no-daemon', '--console=plain', ':app:assemblePreview')
if ($Offline) { $options += '--offline' }
if ($SkipLint) { $options += @('-x', ':app:lintVitalPreview') }
if ($Aapt2Override) { $options += "-Pandroid.aapt2FromMavenOverride=$Aapt2Override" }
Push-Location -LiteralPath $PSScriptRoot
try {
    & $Gradle @options
    if ($LASTEXITCODE -ne 0) { throw 'Android preview build failed.' }
    Get-Item -LiteralPath (Join-Path $PSScriptRoot 'app/build/outputs/apk/preview/app-preview.apk')
} finally { Pop-Location }