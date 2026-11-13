# Android permissions

WDB declares permissions by feature and requests special access only through explicit user interaction.

| Permission | Purpose |
| --- | --- |
| `INTERNET` | ADB network transport and pairing |
| `ACCESS_NETWORK_STATE` | Observe network connectivity |
| `CHANGE_NETWORK_STATE` | Satisfy the connected-device foreground-service prerequisite on supported Android versions |
| `WAKE_LOCK` | Hold a partial wake lock during an active foreground session |
| `FOREGROUND_SERVICE` | Run a user-visible session service |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Declare the connected-device foreground service type |
| `POST_NOTIFICATIONS` | Show the foreground-service notification on Android 13+; requested at runtime |
| `SYSTEM_ALERT_WINDOW` | Optional floating pairing shortcut; requires user approval in Settings |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Request exemption when the user chooses it |

The application must explain why special access is needed before opening system settings. It must not assume the user granted access merely because the settings activity was launched. Overlay UI must remain dismissible and must not obscure system security prompts.

The app uses Android `NsdManager` for mDNS discovery. Network discovery is limited to ADB service types and is used only to populate pairing and connection endpoint lists.

A wake lock and battery-optimization exemption are best-effort lifecycle aids, not a guarantee that Android or an OEM will keep the process alive. Force-stop always remains under user and operating-system control.
