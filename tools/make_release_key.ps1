# make_release_key.ps1 - create Wayfinder's RELEASE signing key, once, OUTSIDE the repo.
# You type the passwords yourself (keytool asks); they're stored only in
# %USERPROFILE%\.wayfinder\release.properties, which the Gradle build reads.
#
# LOSING THIS KEY = NO UPDATES FOR EXISTING INSTALLS, EVER. After running it, back up the
# whole %USERPROFILE%\.wayfinder folder in TWO places (e.g. an encrypted USB key + your
# password manager's file vault). Never commit it, never put it in cloud storage unencrypted.
$ErrorActionPreference = "Stop"
$dir = if ($env:WAYFINDER_KEY_DIR) { $env:WAYFINDER_KEY_DIR } else { Join-Path $env:USERPROFILE ".wayfinder" }
$ks = Join-Path $dir "wayfinder-release.jks"
if (Test-Path $ks) { Write-Host "A release key already exists: $ks - not overwriting it."; exit 1 }
New-Item -ItemType Directory -Force $dir | Out-Null
$keytool = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
$sp = Read-Host "Choose the keystore password (min 8 characters)" -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sp))
if ($plain.Length -lt 8) { Write-Host "Too short."; exit 1 }
& $keytool -genkeypair -v -keystore $ks -alias wayfinder -keyalg RSA -keysize 4096 -validity 36500 `
    -storepass $plain -keypass $plain -dname "CN=Wayfinder, O=Wayfinder"
@"
storeFile=$($ks -replace '\\','/')
storePassword=$plain
keyAlias=wayfinder
keyPassword=$plain
"@ | Set-Content -Encoding ascii (Join-Path $dir "release.properties")
Write-Host ""
Write-Host "Done: $ks"
Write-Host "Certificate fingerprint (publish this with every release):"
& $keytool -list -v -keystore $ks -storepass $plain | Select-String "SHA256:"
Write-Host ""
Write-Host "NOW back up $dir in two places."
