<img src="assets/icon.svg" width="64" height="64" alt="">

# TrueNAS_Dock

Created in [T3 Code](https://t3.codes).

## Build

Use JDK 17 and Android SDK platform 37.0 with build tools 36.0.0:

```sh
./gradlew :truenas-api:test :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
GitHub Actions runs this build for pull requests, pushes to `main`, and manual
runs. Download the `TrueNAS-Dock-debug` artifact from the workflow run.

## GitHub releases

Push a version tag to test the API client, build a signed release APK, and
publish the APK and `SHA256SUMS` to a GitHub release:

```sh
git tag v0.1.0
git push origin v0.1.0
```

Tags containing a hyphen (for example, `v0.2.0-beta.1`) create prereleases.
The tag supplies the APK version name, and the workflow run number supplies its
version code. Re-running a tagged workflow updates that release's assets.
Manual runs on an existing version tag also publish a release.

Release signing uses these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: the base64-encoded release keystore.
- `ANDROID_KEYSTORE_PASSWORD`: the keystore password.
- `ANDROID_KEY_ALIAS`: the signing key alias.
- `ANDROID_KEY_PASSWORD`: the signing key password.

Keep a backup of the keystore, alias, and passwords: future app updates must use
the same signing key. These files must stay outside the repository.

To build a signed release locally, set `ANDROID_KEYSTORE_PATH` to the keystore's
absolute path and export the three password/alias variables above, then run:

```sh
./gradlew :app:assembleRelease -PreleaseVersion=0.1.0 -PreleaseVersionCode=1
```

Without signing variables, local release builds produce an unsigned APK.
