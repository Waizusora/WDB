# Implementation roadmap

## Implemented

- [x] Android application identity and Gradle project
- [x] Minimal dark Material 3 interface and launcher icon
- [x] Developer Options hand-off and optional floating pairing shortcut
- [x] Foreground session lifecycle, notification, and partial wake lock
- [x] User-controlled overlay and battery-optimization settings
- [x] ADB SPAKE2 pairing using the displayed six-digit code
- [x] mDNS discovery of separate pairing and ADB connection endpoints
- [x] Authenticated ADB TLS transport and shell handshake validation
- [x] AES-GCM encrypted host private-key persistence using Android Keystore
- [x] Checkable shell-command catalog
- [x] PID lookup using validated package/process names and `pidof`
- [x] Logcat snapshot capture with text filtering and bounded display output
- [x] Apache License 2.0 and English technical documentation

## Remaining work

- [ ] Add live Logcat streaming with cancellation and bounded backpressure
- [ ] Expand the command catalog to cover additional ADB host and device command families
- [ ] Add command-specific argument forms and explicit confirmation for destructive operations
- [ ] Add pairing, TLS reconnect, shell, and mDNS unit/instrumentation tests
- [ ] Add UI tests for pairing, permission denial, reconnect, and Stop behavior
- [ ] Verify API 26–35 behavior on physical devices and OEM variants
- [ ] Add a reproducible Gradle wrapper and release build/signing documentation
- [ ] Add dependency license and vulnerability checks to CI

## Acceptance criteria

WDB must never show a successful pairing or connected status without protocol confirmation. Start must be disabled until an authenticated ADB transport is ready. Stop must close active sockets and streams, cancel command work, release wake locks, and stop the foreground service.
