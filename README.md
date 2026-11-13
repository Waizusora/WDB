# WDB — Wireless Debug Bridge

WDB stands for **Wireless Debug Bridge**. It is an Android application for pairing with and operating Android Debug Bridge (ADB) connections over Android Wireless Debugging.

WDB uses a minimal Material 3 interface and connects directly to `adbd` through the authenticated Wireless Debugging TLS transport.

## Application

- Application ID: `com.waizusora.wdb`
- Minimum Android version: API 26
- Target SDK: API 35
- Compile SDK: API 36
- Language and UI: Kotlin, Jetpack Compose, Material 3
- ADB implementation: adb-kt (Apache License 2.0)
- Pairing primitive: spake2-kt (Apache License 2.0)

## Features

- Six-digit ADB Wireless Debugging pairing.
- Android NSD/mDNS discovery of pairing and connection endpoints.
- Authenticated ADB TLS connection with a Shell V2 handshake.
- AES-GCM encrypted ADB host identity backed by Android Keystore.
- Foreground ADB session service with a full wake lock while the session is active.
- Optional floating pairing shortcut, gated by user-granted overlay access.
- Checkable shell-command catalog, custom shell commands, and validated `pidof` package lookup.
- Live Logcat streaming over Shell V2, bounded in-memory output, text filtering, snapshot capture, and Clear Logcat (`logcat -c`).
- In-app Open Source Licenses information.

## Build requirements

- Android Studio with Android Gradle Plugin 9.3.1 support
- Gradle 9.5.0
- JDK 17
- Android SDK Platform 36
- Android device with Wireless debugging (Android 11 or later recommended)

Build a debug APK:

```bash
gradle --no-daemon assembleDebug
```

Build a release APK locally by setting all four environment variables:

- `WDB_KEYSTORE_PATH`
- `WDB_KEYSTORE_PASSWORD`
- `WDB_KEY_ALIAS`
- `WDB_KEY_PASSWORD`

Then run:

```bash
gradle --no-daemon assembleRelease
```

## GitHub Releases

Pushing a tag matching `v*` starts the release workflow. Configure all of these repository Actions secrets to publish a signed APK:

- `ANDROID_KEYSTORE_BASE64`: Base64-encoded JKS/keystore file.
- `ANDROID_KEYSTORE_PASSWORD`: Keystore password.
- `ANDROID_KEY_ALIAS`: Signing key alias.
- `ANDROID_KEY_PASSWORD`: Signing key password.

The workflow checks all four secrets before restoring the keystore. If any value is missing, the signed release build and GitHub Release publication steps are skipped. The debug build workflow remains independent. Generate the secret value for the keystore with `base64 -w 0 release.jks` on GNU/Linux (or `base64 -i release.jks | tr -d '\\n'` on macOS), then store the output as the `ANDROID_KEYSTORE_BASE64` Actions secret. Do not add the keystore itself to the repository.

Example tag:

```bash
git tag v0.4.0
git push origin v0.4.0
```

Never commit keystore files or signing credentials. The repository `.gitignore` excludes common signing key and secret file formats.

## Wireless debugging workflow

1. Enable Developer Options and Wireless debugging on the target device.
2. Tap **Pair device** in WDB. Android opens Developer Options; select Wireless debugging and then **Pair device with pairing code**.
3. Keep the system pairing dialog visible and enter its IP address, pairing port, and six-digit code in WDB.
4. WDB performs the ADB SPAKE2 pairing exchange over TLS.
5. Wait for mDNS to discover the separate ADB connection endpoint.
6. Tap **Connect**. WDB validates the authenticated transport with a Shell V2 handshake.
7. Tap **Start** to activate the foreground session. Use **Stop** to close the transport, cancel Logcat streaming, and release the wake lock.

Android does not expose a stable public intent that opens the pairing-code dialog on every OEM build. WDB therefore opens Developer Options and provides an optional floating shortcut when overlay access is granted.

## Logcat

- **Live stream** runs `logcat -v threadtime` over the authenticated Shell V2 stream.
- **Stop stream** cancels the streaming coroutine.
- **Capture** retrieves a bounded snapshot using `logcat -b all -d -t 500 -v threadtime`.
- **Clear Logcat** runs `logcat -c` on the connected device. The UI reports success or that the ADB device is not connected.

The live UI buffer is intentionally bounded to prevent unbounded memory growth during long-running streams.

## Permissions and background execution

WDB requests network, notification, foreground-service, overlay, battery-optimization, and `WAKE_LOCK` capabilities required by its features. Overlay access and battery-optimization exemption require explicit user approval.

The session service acquires `PowerManager.FULL_WAKE_LOCK` while active. This keeps the display and CPU awake and has a higher battery cost than a partial wake lock. The lock is released on Stop and service destruction. Android may still terminate an application process or honor a user force-stop; no application can guarantee immunity from system or OEM process management.

## Security

The ADB host private key is encrypted with AES-GCM using a non-exportable Android Keystore key. Pairing codes are held only for the pairing operation and are not written to logs. Signing keys must be stored only in GitHub Actions secrets or secure local storage.

The ADB client and SPAKE2 implementation are Apache-2.0 licensed. Third-party protocol code has not been independently audited by WDB maintainers. Validate behavior on dedicated test devices before relying on it in sensitive environments.

## Third-party components

WDB provides an **Open Source Licenses** screen in Settings. Dependency names, versions, copyright notices, and license identifiers are also documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Full license texts remain available from the upstream projects.

## License

WDB is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
