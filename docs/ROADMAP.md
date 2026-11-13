# Implementation roadmap

## Implemented

- [x] Android application identity and Gradle project
- [x] Minimal dark Material 3 interface and launcher icon
- [x] Developer Options hand-off and optional floating pairing shortcut
- [x] Foreground session lifecycle, notification, and full wake lock
- [x] User-controlled overlay and battery-optimization settings
- [x] ADB SPAKE2 pairing using the displayed six-digit code
- [x] Android NSD/mDNS discovery of separate pairing and ADB connection endpoints
- [x] Authenticated ADB TLS transport and Shell V2 handshake validation
- [x] AES-GCM encrypted host private-key persistence using Android Keystore
- [x] Checkable shell-command catalog and custom shell command runner
- [x] PID lookup using validated package/process names and `pidof`
- [x] Logcat snapshot, live Shell V2 streaming, filtering, and Clear Logcat
- [x] In-app Open Source Licenses information
- [x] Optional environment-based release signing and GitHub Release workflow
- [x] Signing-key and secret-file exclusions in `.gitignore`
- [x] Apache License 2.0 and English technical documentation
- [x] Stable Android API 36 Gradle toolchain and CI build workflow

## Remaining work

- [x] Add initial JVM tests for numeric ADB endpoint validation
- [ ] Add pairing, TLS reconnect, shell streaming, and mDNS instrumentation tests
- [ ] Add UI tests for pairing, permission denial, reconnect, Logcat cancellation, and Stop behavior
- [ ] Verify API 26–35 behavior on physical devices and OEM variants
- [ ] Add ADB host-side package install/uninstall workflows
- [ ] Add ADB sync-based file push and pull workflows
- [ ] Add ADB port forwarding and reverse forwarding
- [ ] Add command-specific argument forms and explicit confirmation for destructive operations
- [ ] Add a reproducible Gradle wrapper and release build/signing validation
- [ ] Add dependency license and vulnerability checks to CI

## Device validation matrix

Before a production release, validate pairing, connection, shell execution, continuous Logcat, clear-buffer behavior, full wake-lock acquisition/release, and service lifecycle on Android 11, 12, 13, 14, 15, and 16 devices. Record OEM, Android build, and observed results without including pairing codes or private device credentials.

## Acceptance criteria

WDB must never show successful pairing or connected status without protocol confirmation. Start must be disabled until an authenticated ADB transport is ready. Clear Logcat must not claim success without a connected ADB transport. Stop must cancel active streams, close transport sockets, release the full wake lock, and stop the foreground service.
