# Android permissions

WDB declares permissions by feature and requests special access only through explicit user interaction.

| Permission | Purpose |
| --- | --- |
| `INTERNET` | ADB network transport |
| `ACCESS_NETWORK_STATE` | Observe network connectivity |
| `WAKE_LOCK` | Hold a partial wake lock during an active foreground session |
| `FOREGROUND_SERVICE` | Run a user-visible session service |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Declare the connected-device foreground service type |
| `POST_NOTIFICATIONS` | Show the foreground-service notification on Android 13+ |
| `SYSTEM_ALERT_WINDOW` | Optional floating pairing panel; requires user approval in Settings |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Request exemption when the user chooses it |

The application must explain why special access is needed before opening system settings. It must not assume the user granted access merely because the settings activity was launched.

A wake lock and battery-optimization exemption are best-effort lifecycle aids, not a guarantee that Android or an OEM will keep the process alive. Force-stop always remains under user and operating-system control.
