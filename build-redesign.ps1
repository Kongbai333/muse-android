# Build using a standard environment, or reuse this workspace's local toolchain.
param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$build = Join-Path $PSScriptRoot '.build'
$null = New-Item -ItemType Directory -Path $build -Force
$log = Join-Path $build 'assemble-redesign.log'
$status = Join-Path $build 'redesign-build-status.txt'
Set-Content -LiteralPath $status -Value "RUNNING $(Get-Date -Format s)" -Encoding UTF8
try {
    if (Test-Path -LiteralPath (Join-Path $build 'jdk21/bin/java.exe')) { $env:JAVA_HOME = Join-Path $build 'jdk21' }
    if (Test-Path -LiteralPath (Join-Path $build 'sdk')) { $env:ANDROID_HOME = Join-Path $build 'sdk' }
    if (Test-Path -LiteralPath (Join-Path $build 'gradle-home')) { $env:GRADLE_USER_HOME = Join-Path $build 'gradle-home' }
    $options = @{ Offline = $Offline }
    $cachedGradle = Join-Path $build 'gradle-dist/gradle-8.9/bin/gradle.bat'
    if (Test-Path -LiteralPath $cachedGradle) { $options.Gradle = $cachedGradle }
    if ($env:ANDROID_HOME) {
        $aapt = Join-Path $env:ANDROID_HOME 'build-tools/35.0.0/aapt2.exe'
        if (Test-Path -LiteralPath $aapt) { $options.Aapt2Override = $aapt }
    }
    & (Join-Path $PSScriptRoot 'build-preview.ps1') @options 2>&1 | Tee-Object -FilePath $log
    & (Join-Path $PSScriptRoot 'scripts/package-android.ps1') -Variant preview
    $gradleText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'app/build.gradle') -Raw
    $version = [regex]::Match($gradleText, "versionName\s+'([^']+)'").Groups[1].Value
    $apk = Join-Path $PSScriptRoot "dist/Muse-Android-$version-preview.apk"
    $localRelease = Join-Path (Split-Path $PSScriptRoot -Parent) 'release-android'
    if (Test-Path -LiteralPath $localRelease -PathType Container) {
        Copy-Item -LiteralPath $apk -Destination $localRelease -Force
        Copy-Item -LiteralPath ($apk + '.sha256') -Destination $localRelease -Force
    }
    Set-Content -LiteralPath $status -Value "OK $(Get-Date -Format s)`napk=$apk`nbytes=$((Get-Item -LiteralPath $apk).Length)" -Encoding UTF8
    Write-Host "Build complete: $apk" -ForegroundColor Green
} catch {
    Set-Content -LiteralPath $status -Value "FAIL $(Get-Date -Format s)`n$($_.Exception.Message)" -Encoding UTF8
    throw
}