# Release signing

Stable APKs use a dedicated RSA 4096-bit release key, separate from local and CI
debug keys. The public [X.509 certificate](signing/release-cert.pem) identifies
the signer; its SHA-256 fingerprint is:

```text
9E:FA:FE:98:5C:04:D4:72:8B:9A:9D:93:A8:26:C9:E0:C3:2E:EA:EC:77:FD:35:87:C0:28:A4:83:EB:67:7C:85
```

The certificate expires on 2054-03-06. It contains no private key. Verify a
download with the Android SDK Build Tools:

```sh
apksigner verify --verbose --print-certs castor-android.apk
sha256sum --check SHA256SUMS.txt
```

Compare the signer fingerprint above and the file checksum published with the
release. The certificate fingerprint stays the same across updates; each APK has
its own checksum. This verifies the signing identity and file integrity, without
guaranteeing that Android or Play Protect will accept an installation silently.
A paid certificate is not required for APK distribution.

## Moving from development builds

Android cannot update an app signed with a different key. Uninstall a previous
development build before installing the first stable release; uninstalling also
removes its saved app preferences. Subsequent stable updates preserve app data
when signed with this release key and a higher `versionCode`.

## Maintainer procedure

Keep the encrypted PKCS12 keystore outside the repository, with access limited to
the maintainer account. Store an encrypted backup on a separate device and its
password separately in a password manager. Losing either prevents future updates
under the existing identity. Never generate a replacement key for each release.
No private key or password belongs in a commit, source archive or release asset.
Upstream synchronization and validation CI do not receive signing credentials.

Build the native runtime using the [build guide](../README.md#build-on-windows), then run
`:app:assembleRelease :app:lintRelease` from `android/`. The release variant is
unsigned so contributors can build without access to the maintainer's key.

For example, in PowerShell 7 from `android/`, with an existing keystore and
password file in the maintainer's protected signing directory:

```powershell
$tools = Join-Path $env:ANDROID_HOME 'build-tools/37.0.0'
$signing = Join-Path $env:USERPROFILE '.android/castor-android-signing'
$release = 'app/build/outputs/apk/release'
& "$tools/zipalign.exe" -f -P 16 4 "$release/app-release-unsigned.apk" "$release/app-release-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
& "$tools/apksigner.bat" sign --ks "$signing/castor-android-release.p12" --ks-key-alias castor-android-release --ks-pass "file:$signing/release-password.txt" --v4-signing-enabled false --out "$release/castor-android.apk" "$release/app-release-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
& "$tools/apksigner.bat" verify --verbose --print-certs "$release/castor-android.apk"
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
& "$tools/zipalign.exe" -c -P 16 4 "$release/castor-android.apk"
if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment check failed' }
Get-FileHash "$release/castor-android.apk" -Algorithm SHA256
```

Set `JAVA_HOME` and `ANDROID_HOME` as in the build guide. The password file is a
local convenience protected by filesystem permissions; it is not an encrypted
password backup. Align before signing and never modify the signed APK. These
commands replace the previous generated release outputs.

Publish the verified APK with its checksum, signer fingerprint, project source
snapshot and the corresponding native-source archive required by FFmpeg's GPL
components. Tag Android releases independently, for example `android-v1.0.0`.
