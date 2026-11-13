# Architecture

## Application identity

- Application ID: `com.waizusora.wdb`
- Display name: WDB
- UI: Kotlin, Jetpack Compose, Material 3
- Minimum Android version: API 26
- Compile SDK: API 36
- Target SDK: API 35

## Components

- `WdbApplication` creates the encrypted ADB key provider and owns the process-wide `WirelessAdbManager`.
- `MainActivity` renders the user interface and explicitly navigates to Android system settings.
- `FloatingPairService` owns the optional, user-dismissable overlay shortcut. It does not collect credentials; tapping it opens the in-app pairing form.
- `AdbSessionService` owns the foreground session lifetime and a partial wake lock. Its process-wide `AdbSessionState` exposes service status to the UI across Activity recreation.
- `WirelessAdbDiscovery` uses Android `NsdManager` to discover the ADB pairing and TLS connection service types.
- adb-kt implements ADB protocol framing, SPAKE2 pairing, TLS transport, Shell V2, and coroutine-based command streams.

## Pairing and connection

The application uses adb-kt 1.0.13 and spake2-kt v1.0.2. Both are Apache-2.0 licensed.

1. `AdbClient.pairTls(host, port, pairingCode, keyProvider)` performs the Wireless Debugging SPAKE2 pairing exchange over TLS 1.3.
2. `WirelessAdbDiscovery` resolves `_adb-tls-pairing._tcp.` and `_adb-tls-connect._tcp.` using Android `NsdManager`.
3. `AdbClient.connectTls(host, port, keyProvider)` establishes the authenticated ADB TLS transport.
4. WDB executes a Shell V2 handshake before retaining the client or showing Connected.

Pairing success and transport connection are distinct states. A pairing handshake does not imply that a usable shell transport exists.

## Host identity

`EncryptedAdbKeyProvider` implements adb-kt's `AdbKeyProvider`. It generates a 2048-bit RSA identity once and stores its PKCS#8 private-key bytes encrypted with AES-GCM. The AES key is generated in Android Keystore and is non-exportable. Only ciphertext is stored in app-private files. Pairing codes are not persisted.

## Command execution

The current command catalog executes a bounded set of shell commands through the authenticated ADB transport. PID lookup validates package/process names before invoking `pidof`. Logcat capture uses `logcat -d -t 500 -v threadtime` for a bounded snapshot and adb-kt's Shell V2 Flow API for cancellable live streaming. Both paths retain a bounded output buffer and apply a local text filter.

## Background execution

The foreground service is started only after an explicit user action and a verified transport. A partial wake lock is acquired while the service is active and released on stop or destruction. The floating pairing shortcut uses its own foreground service while visible. Android may still terminate the process, and users may force-stop it. Battery-optimization exemption and overlay access are optional user-granted capabilities.

## Security requirements

- Never write pairing codes, private keys, or authentication tokens to logs.
- Validate IP addresses and port ranges before initiating network operations.
- Use SPAKE2 pairing and authenticated TLS transport; do not downgrade to unauthenticated plaintext.
- Keep command execution scoped to the selected device.
- Treat shell commands as privileged user input and display the exact command before execution where practical.
- Keep host identity material inside app-private storage and encrypt it with Android Keystore.
- Re-evaluate the ADB protocol dependency before release because third-party code has not been independently audited by WDB maintainers.
