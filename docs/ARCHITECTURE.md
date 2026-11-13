# Architecture

## Application identity

- Application ID: `com.waizusora.wdb`
- Display name: WDB
- UI: Kotlin, Jetpack Compose, Material 3
- Minimum Android version: API 26
- Compile SDK: API 36
- Target SDK: API 35

## Components

- `WdbApplication` configures the encrypted ADB identity store and owns the process-wide `WirelessAdbManager`.
- `MainActivity` renders the user interface and explicitly navigates to Android system settings.
- `FloatingPairService` owns the optional, user-dismissable overlay shortcut. It does not collect credentials; tapping it opens the in-app pairing form.
- `AdbSessionService` owns the foreground session lifetime and a partial wake lock.
- Kadb implements the ADB pairing protocol, TLS transport negotiation, ADB stream multiplexing, and shell execution.
- Kadb mDNS discovery reports pairing and connection endpoints separately.

## Pairing and connection

The application uses Kadb 2.1.4, an Apache-2.0 Kotlin ADB client.

1. `Kadb.pair(host, port, pairingCode)` performs the Wireless Debugging SPAKE2 pairing exchange over TLS.
2. `KadbMdnsAndroid` discovers `_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp` service endpoints using Android `NsdManager`.
3. `Kadb.create(host, port)` establishes the ADB transport. The library negotiates the TLS upgrade when the peer requests it.
4. WDB executes a protocol-level shell handshake before retaining the client or showing Connected.

Pairing success and transport connection are distinct states. A pairing handshake does not imply that a usable shell transport exists.

## Host identity

`EncryptedAdbPrivateKeyStore` implements Kadb's `KadbPrivateKeyStore`. The private key is encrypted with AES-GCM; the AES key is generated in Android Keystore and is not exportable. Only ciphertext is stored in app-private files. Pairing codes are not persisted.

## Command execution

The current command catalog executes a bounded set of shell commands through the authenticated ADB transport. PID lookup validates package/process names before invoking `pidof`. Logcat capture uses `logcat -d -v threadtime`, retains a bounded output snapshot, and applies a local text filter. Live logcat streaming is not yet implemented.

## Background execution

The foreground service is started only after an explicit user action and a verified transport. A partial wake lock is acquired while the service is active and released on stop or destruction. Android may still terminate the process, and users may force-stop it. Battery-optimization exemption and overlay access are optional user-granted capabilities.

## Security requirements

- Never write pairing codes, private keys, or authentication tokens to logs.
- Validate IP addresses and port ranges before initiating network operations.
- Use the ADB pairing protocol and TLS transport supplied by Kadb; do not downgrade to unauthenticated plaintext.
- Keep command execution scoped to the selected device.
- Treat shell commands as privileged user input and display the exact command before execution where practical.
- Keep host identity material inside app-private storage and encrypt it with Android Keystore.
