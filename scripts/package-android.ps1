param(
    [ValidateSet('debug', 'preview')][string]$Variant = 'preview',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../dist'),
    [string]$Tag = ''
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).ProviderPath
$variantName = $Variant.ToLowerInvariant()
$apkDirectory = Join-Path $repo "app/build/outputs/apk/$variantName"
$metadata = Get-Content -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.elements.Count -ne 1) { throw 'Expected one universal APK.' }
$artifact = $metadata.elements[0]
$source = Get-Content -LiteralPath (Join-Path $repo 'app/build.gradle') -Raw
$sourceVersion = [regex]::Match($source, "versionName\s+'([^']+)'").Groups[1].Value
$sourceCode = [int][regex]::Match($source, 'versionCode\s+(\d+)').Groups[1].Value
if ($artifact.versionName -ne $sourceVersion -or $artifact.versionCode -ne $sourceCode) {
    throw 'Built APK version does not match the current source.'
}
if ($Tag -and $Tag -cne "v$sourceVersion") { throw "Release tag $Tag does not match source version v$sourceVersion." }
if ([IO.Path]::GetFileName($artifact.outputFile) -ne $artifact.outputFile) { throw 'Unexpected APK filename in metadata.' }
$apk = Join-Path $apkDirectory $artifact.outputFile
if (!(Test-Path -LiteralPath $apk -PathType Leaf)) { throw 'The built APK is missing.' }
$null = New-Item -ItemType Directory -Path $OutputDirectory -Force
$destination = Join-Path $OutputDirectory "Muse-Android-$sourceVersion-$variantName.apk"
Copy-Item -LiteralPath $apk -Destination $destination -Force
$hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($destination + '.sha256'), "$hash  $([IO.Path]::GetFileName($destination))`n", [Text.UTF8Encoding]::new($false))
foreach ($name in @('LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md')) {
    Copy-Item -LiteralPath (Join-Path $repo $name) -Destination (Join-Path $OutputDirectory $name) -Force
}
Write-Output $destination
Write-Output ($destination + '.sha256')
