# NganyaFlow Android

Kotlin + Jetpack Compose + Material 3; `com.nganyaflow.app`; minSdk26, compile/target37.
Versions: `gradle/libs.versions.toml`. JDK25 (Android Studio JBR), SDK37.0,
build-tools36.0.0 and committed Gradle9.6 wrapper. Set ANDROID_HOME or ignored
local.properties: `sdk.dir=C\:/Users/mwend/AppData/Local/Android/Sdk` on this machine.

## Build and run

Executed from android/ in PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug --console=plain
```

Linux/macOS equivalent: `./gradlew assembleDebug testDebugUnitTest lintDebug`.
APK: `app/build/outputs/apk/debug/app-debug.apk`.
Executed from the repository root:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell am start -W -n com.nganyaflow.app/.MainActivity
```

Android Studio can instead Run the app on a selected emulator/device.

## Demo backend

Create .venv with Python3.12+, then execute from the repository root:

```powershell
.\.venv\Scripts\python.exe -m pip install -e 'server[dev]'
Set-Location server
..\.venv\Scripts\nganyaflow.exe seed-demo
$env:NGANYAFLOW_DEMO_MODE = '1'
..\.venv\Scripts\python.exe -m uvicorn nganyaflow.main:app --host 127.0.0.1 --port 8000 --log-level error
```

The emulator uses `http://10.0.2.2:8000/`. For a physical phone use a reachable HTTPS
URL via Settings > Server address; it must pass /health. Debug cleartext is restricted
to local host addresses; release requires HTTPS. Local-debug workers can reach the
emulator host without Android validating public Internet; other workers require a
connected network. Failed calls retry with backoff.

Random role codes are in ignored server/runtime/demo-access-codes.txt. Read privately;
never commit or log credentials. The error log level suppresses the existing server
admin-password startup warning. All demo arrival times are SYNTHETIC.

## Features

Account-free browsing, local stage search/favourites/coarse location, expiring ranges,
route diagrams/evidence; explicit research consent, reviewed reports, durable outbox,
Activity/Undo, identity-rotating deletion; encrypted role access, multi-vehicle recording,
pause/resume/unobserved departures/void/history; qualified crew bars; English/Kiswahili,
light/dark/system/dynamic themes, validated server changes and one two-hour wait alert.
Room is the source of truth; WorkManager retries immutable UUID/payloads.

## Verification and limits

See [demo steps](DEMO.md) and [observed results](verification/RESULTS.md).
Final screenshots: verification/final/. CI builds Android, tests/lints backend and uploads APK.
Full TalkBack,200% font/width/landscape and offline/process-death matrices, physical-phone
and minified-release runtime checks were skipped under the agreed reduced scope.
Kiswahili needs fluent review. Background checks may be delayed by Android. Expiration
uses a stored two-hour deadline; no two-hour wall-clock endurance test was run.
Synthetic-model metrics do not establish Nairobi field accuracy. No public tunnel or
Docker instance was started. Shared-element transitions were intentionally skipped.
