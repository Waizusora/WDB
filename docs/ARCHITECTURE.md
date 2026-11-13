# Architecture

## Application identity

- Application ID: `com.waizusora.wdb`
- Display name: WDB
- UI: Kotlin, Jetpack Compose, Material 3
- Minimum Android version: API 26
- Target SDK: API 35

## Components

- `MainActivity` owns the user interface and explicit navigation to Android system settings.
- `AdbSessionService` owns the foreground session lifetime and a bounded partial wake lock.
- A future ADB transport module must own pairing, authenticated transport, command execution, and stream lifecycle. It must not be conflated with the foreground service.

## Required transport boundary

Implement the Android ADB pairing protocol and ADB TLS transport as separate, testable components. Pairing uses the endpoint and short-lived code displayed by the target device. A successful pairing handshake does not itself imply that a command transport is connected.

The transport layer must expose explicit states such as `Idle`, `Pairing`, `Paired`, `Connecting`, `Connected`, and `Failed`. Only a verified transport may enable shell, logcat, and PID operations. Never emulate successful ADB output or silently fall back to local Android shell execution.

## Background execution

The foreground service is started only after an explicit user action. The partial wake lock is acquired while the service is active and released on stop or destruction. Android may still terminate the process, and users may force-stop it. Battery-optimization exemption and overlay access are optional user-granted capabilities.

## Security requirements

- Never write pairing codes, private keys, or authentication tokens to logs.
- Validate IP addresses and port ranges before initiating network operations.
- Use authenticated TLS and verify the peer identity according to the ADB protocol.
- Keep command execution scoped to the selected device.
- Treat shell commands as privileged user input and display the exact command before execution where practical.
