# release.ps1 - build, sign, verify and fingerprint the Wayfinder release APK.
# Needs the release key (tools/make_release_key.ps1 -> %USERPROFILE%\.wayfinder\release.properties).
# Output: dist\wayfinder-<version>.apk + dist\wayfinder-<version>.txt (SHA-256 of the APK and of the
# signing certificate - publish both with the release).
# A release must be built from a clean, tagged commit: a dirty tree is refused unless -Test
# (a test build for your own device, never published).
param([switch]$Test)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot)
$keyDir = if ($env:WAYFINDER_KEY_DIR) { $env:WAYFINDER_KEY_DIR } else { Join-Path $env:USERPROFILE ".wayfinder" }
if (-not (Test-Path (Join-Path $keyDir "release.properties"))) {
    Write-Host "No release key yet: run tools\make_release_key.ps1 first."; exit 1
}
if (git status --porcelain) {
    if (-not $Test) { Write-Host "The tree has uncommitted changes - a release must be built from a clean, tagged commit (or use -Test for a private test build)."; exit 1 }
    Write-Host "WARNING: uncommitted changes - TEST build, not publishable."
}
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
# a fresh clone has no local.properties (it is gitignored): use the default SDK folder
if (-not $env:ANDROID_HOME -and -not (Test-Path local.properties)) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
# a leftover Gradle process can hold build files open (clean fails) or carry an old environment
try { & .\gradlew.bat --stop -q 2>$null } catch {}
& .\gradlew.bat clean :app:assembleRelease --max-workers=2 -q
if ($LASTEXITCODE -ne 0) { exit 1 }
$bt = (Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" | Sort-Object Name | Select-Object -Last 1).FullName
$apk = "app\build\outputs\apk\release\app-release.apk"
$version = (Select-String -Path app\build.gradle.kts -Pattern 'versionName = "(.+)"').Matches[0].Groups[1].Value
New-Item -ItemType Directory -Force dist | Out-Null
$out = "dist\wayfinder-$version.apk"
Copy-Item $apk $out -Force
$certs = & "$bt\apksigner.bat" verify --verbose --print-certs $out
if ($LASTEXITCODE -ne 0) { Write-Host "Signature check FAILED"; exit 1 }
$sha = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
$cert = ($certs | Select-String "certificate SHA-256 digest: (.+)").Matches[0].Groups[1].Value
$info = @"
Wayfinder $version
APK SHA-256:                 $sha
Signing certificate SHA-256: $cert
Commit:                      $(git rev-parse HEAD)$(if (git status --porcelain) { " + uncommitted changes (not a publishable build)" })
"@
$info | Out-File -Encoding ascii "dist\wayfinder-$version.txt"
$info
Write-Host ""
Write-Host "Install on the Thor: adb install $out   (or copy the APK to the Thor and open it)"
