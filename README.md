# WDB

WDB is an Android Wireless Debugging client with a minimal Material 3 interface. It pairs with Android devices using the ADB pairing protocol and connects directly to `adbd` over the authenticated Wireless Debugging TLS transport.

## Application

- Application ID: `com.waizusora.wdb`
- Minimum Android version: API 26
- Target SDK: API 35
- Compile SDK: API 37
- Language and UI: Kotlin, Jetpack Compose, Material 3
- ADB implementation: Kadb 2.1.5 (Apache License 2.0)

## Implemented capabilities

- ADB wireless pairing with the six-digit pairing code shown by Android.
- mDNS discovery of pairing and connection endpoints.
- TLS ADB connection with a protocol-level shell handshake before showing Connected.
- AES-GCM encrypted ADB host identity using Android Keystore.
- Foreground session service and partial wake lock.
- Optional floating pairing shortcut, gated by user-granted overlay access.
- Checkable shell-command catalog, `pidof` package lookup, and Logcat snapshot capture with text filtering.

Logcat capture currently uses `logcat -d` to retrieve a bounded snapshot. Continuous streaming, additional command families, and automated protocol tests remain under development.

## Build requirements

- Android Studio with Android Gradle Plugin 9.3.1 support
- Gradle 9.5.0
- JDK 17
- Android SDK Platform 37
- Android device with Wireless debugging (Android 11 or later recommended)

Open the repository in Android Studio and run the `app` configuration. If Gradle 9.5.0 is installed locally:

```bash
gradle --no-daemon assembleDebug
```

## Wireless debugging workflow

1. Enable Developer Options and Wireless debugging on the target device.
2. Tap **Pair device** in WDB. Android opens Developer Options; select Wireless debugging and then **Pair device with pairing code**.
3. Keep the system pairing dialog visible and enter its IP address, pairing port, and six-digit code in WDB.
4. WDB performs the ADB SPAKE2 pairing exchange over TLS. Pairing success is reported only when the library completes the handshake.
5. Wait for mDNS to discover the separate ADB connection endpoint.
6. Tap **Connect** for the discovered endpoint. WDB verifies the ADB transport by executing a shell handshake.
7. Tap **Start** to keep the authenticated session visible through a foreground service. Use **Stop** to close the transport and release the wake lock.

Android does not expose a stable public intent that opens the pairing-code dialog on every OEM build. WDB therefore opens Developer Options and provides an optional floating shortcut when overlay access is granted.

## Permissions and background execution

WDB requests network, notification, foreground-service, and wake-lock permissions required by the implemented features. Overlay access and battery-optimization exemption are special user-granted capabilities. A foreground service and partial wake lock improve session continuity but cannot guarantee immunity from force-stop, OEM process management, or system termination.

## Security

The ADB host private key is encrypted with AES-GCM using a non-exportable Android Keystore key. Pairing codes are held only for the pairing operation and are not written to logs. Connect only to devices explicitly selected by the user.

## Third-party components

Kadb and its optional mDNS module are Apache-2.0 licensed. Conscrypt is included for TLS 1.3 pairing compatibility on older supported Android releases. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## License

WDB is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
