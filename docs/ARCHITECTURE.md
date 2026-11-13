# Architecture

## Application identity

- Application ID: `com.waizusora.wdb`
- Display name: WDB (Wireless Debug Bridge)
- UI: Kotlin, Jetpack Compose, Material 3
- Minimum Android version: API 26
- Compile SDK: API 36
- Target SDK: API 35

## Components

- `WdbApplication` creates the encrypted ADB key provider and owns the process-wide `WirelessAdbManager`.
- `MainActivity` renders the interface, pairing flow, command catalog, Logcat controls, and Open Source Licenses view.
- `FloatingPairService` owns the optional, user-dismissable overlay shortcut. It does not collect credentials.
- `AdbSessionService` owns the foreground session lifetime and a full wake lock. Its process-wide `AdbSessionState` exposes service status across Activity recreation.
- `WirelessAdbDiscovery` uses Android `NsdManager` to discover ADB pairing and TLS connection service types.
- adb-kt implements ADB protocol framing, SPAKE2 pairing, TLS transport, Shell V2, and coroutine-based command streams.

## Pairing and connection

WDB uses adb-kt for the Wireless Debugging SPAKE2 pairing exchange and authenticated ADB TLS transport.

1. `AdbClient.pairTls(host, port, pairingCode, keyProvider)` performs the pairing exchange over TLS.
2. `WirelessAdbDiscovery` resolves `_adb-tls-pairing._tcp.` and `_adb-tls-connect._tcp.` using Android `NsdManager`.
3. `AdbClient.connectTls(host, port, keyProvider)` establishes the authenticated ADB TLS transport.
4. WDB executes a Shell V2 handshake before retaining the client or showing Connected.

Pairing success and transport connection are distinct states. A pairing handshake does not imply that a usable shell transport exists.

## Host identity

`EncryptedAdbKeyProvider` implements adb-kt's `AdbKeyProvider`. It generates a 2048-bit RSA identity once and stores its PKCS#8 private-key bytes encrypted with AES-GCM. The AES key is generated in Android Keystore and is non-exportable. Only ciphertext is stored in app-private files. Pairing codes are not persisted.

## Command execution and Logcat

The checkable command catalog exposes common device diagnostics. The custom command field executes explicit user-authored shell commands through the authenticated ADB transport. PID lookup validates package/process names before invoking `pidof`.

Live Logcat uses `logcat -b all -v threadtime` through adb-kt's Shell V2 Flow API. The UI collects stdout and stderr chunks, bounds retained output, supports text filtering, and cancels the collection job when the user stops streaming or ends the ADB session. Snapshot capture uses `logcat -b all -d -t 500 -v threadtime`. Clear Logcat executes `logcat -c` only after checking the active authenticated transport and reports the command outcome in the UI.

## Background execution and power

The foreground service starts only after an explicit user action and a verified transport. It acquires `PowerManager.FULL_WAKE_LOCK` while active and releases it on Stop or service destruction. This intentionally keeps the display on and increases power consumption. The floating pairing shortcut uses its own foreground service while visible.

Android may still terminate the process, and users may force-stop it. Battery-optimization exemption and overlay access are optional user-granted capabilities.

## Release signing

Release signing is configured only when all required environment values are available. The GitHub Actions release workflow requires a Base64-encoded keystore, keystore password, key alias, and key password. The keystore is restored to the ephemeral runner temp directory and is never committed. A missing secret causes release publication to be skipped.

## Security requirements

- Never write pairing codes, private keys, or authentication tokens to logs.
- Validate IP addresses and port ranges before initiating network operations.
- Use SPAKE2 pairing and authenticated TLS transport; do not downgrade to unauthenticated plaintext.
- Keep command execution scoped to the selected device.
- Treat custom shell commands as privileged user input.
- Keep host identity material inside app-private storage and encrypt it with Android Keystore.
- Do not commit signing keys or secrets.
- Re-evaluate the ADB protocol dependency before release because third-party code has not been independently audited by WDB maintainers.
