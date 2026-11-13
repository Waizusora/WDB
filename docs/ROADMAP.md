# Implementation roadmap

## Foundation

- [x] Android application identity and Gradle project
- [x] Minimal dark Material 3 interface
- [x] Developer Options hand-off
- [x] Foreground session lifecycle and partial wake lock
- [x] Settings entry points for overlay and battery optimization
- [x] Floating overlay shortcut that reopens the in-app pairing form
- [x] Launcher vector icon
- [x] Initial Apache License 2.0 and English technical documentation

## Required before claiming functional ADB support

- [ ] Implement ADB pairing protocol with the pairing endpoint and user-provided pairing code
- [ ] Persist device pairing identity securely using Android Keystore-backed storage
- [ ] Implement authenticated ADB TLS connection and transport state machine
- [ ] Add a device discovery workflow for wireless debugging endpoints where Android APIs permit it
- [ ] Add command catalog with validated argument models and per-command selection
- [ ] Add Logcat streaming, filtering, cancellation, and bounded output buffering
- [ ] Add PID lookup using `pidof` and package-name search with safe argument handling
- [ ] Add connection, pairing, and command protocol tests
- [ ] Verify foreground-service behavior and notification permission on API 26–35
- [ ] Build and run instrumentation tests on physical Android devices

## Acceptance criteria

WDB must never show a successful pairing or connected status without protocol confirmation. Start must be disabled until an authenticated ADB transport is ready. Stop must close active sockets and streams, cancel logcat readers, release wake locks, and stop the foreground service.
