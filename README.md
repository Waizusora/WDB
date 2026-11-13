# WDB

WDB is an Android application project for managing Android Wireless Debugging workflows through a focused graphical interface.

## Project status

The repository is being initialized. The first implementation establishes the Android application structure, permission model, foreground session lifecycle, pairing hand-off to Android Developer Options, and the user interface.

**ADB protocol support is a separate transport implementation.** A successful return from Android Settings, a saved pairing code, or a visible IP address must never be treated as proof that an ADB pairing or transport handshake succeeded. The application must report a connected state only after the transport layer confirms it.

## Requirements

- Android Studio with a recent stable Android Gradle Plugin toolchain
- JDK 17
- Android SDK platform 35 or later
- Android device with Wireless debugging support (Android 11 or later)

## Build

Open the repository in Android Studio and run the `app` configuration. Alternatively:

```bash
./gradlew assembleDebug
```

## Wireless debugging workflow

1. Enable Developer Options and Wireless debugging on the target Android device.
2. Use **Pair device** to open Android Developer Options.
3. In the system Wireless debugging screen, select **Pair device with pairing code**.
4. Enter the displayed pairing address, port, and code in WDB's pairing interface.
5. Complete the pairing protocol and verify the device identity before reporting success.
6. Start a separately established ADB transport session before issuing shell or logcat commands.

Android does not expose a stable public intent that navigates directly to the pairing-code dialog on every device. WDB therefore opens the relevant system settings screen and provides a user-controlled overlay entry point where permitted.

## Permissions and background execution

WDB requests only permissions required by enabled features. Overlay access and exemption from battery optimization are special user-granted capabilities and are never silently enabled. A foreground service and a partial wake lock improve session continuity but cannot guarantee immunity from force-stop, OEM process management, or system termination.

## Security

Pairing codes and connection credentials are sensitive. Do not log them, persist them in plaintext, or include them in crash reports. Only connect to devices explicitly selected by the user.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
