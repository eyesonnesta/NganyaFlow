# NganyaFlow Android

Kotlin, Jetpack Compose and Material 3. Application ID `com.nganyaflow.app`, minimum API 26,
compile/target API 37. The dependency catalogue is `gradle/libs.versions.toml`.
AGP 9 uses built-in Kotlin, with the compiler raised to stable Kotlin 2.4.21.
Dependency injection uses a hand-written application container.

## Local tools

Use JDK 25 (the installed Android Studio JBR works), Android SDK API 37 and build-tools
36.0.0. Put your SDK location in ignored `local.properties` or set `ANDROID_HOME`.
The committed wrapper runs Gradle 9.6.0.

## Verification

The commands and their observed results are recorded after execution below.

## Server

Run the synthetic demo server from the repository's `server/` directory. Debug builds
connect to `http://10.0.2.2:8000/` from the Android emulator. Cleartext is restricted to
emulator loopback addresses in debug; release builds require HTTPS.
All arrival-time data from demo mode is synthetic. Browsing requires no account or location.

Kiswahili translations need review by a fluent speaker before field use.

### WP2 local results (17 November 2026)

Executed from `android/` with Android Studio JBR as `JAVA_HOME`:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Result: `BUILD SUCCESSFUL in 16s`. Unit-test task reported `NO-SOURCE` at this
scaffold stage. Lint errors were corrected (SDK property escaping and explicit
network domain scope); warnings remain in the HTML report under app/build/reports.
Installed the debug APK with `adb install -r`, launched MainActivity (`Status: ok`),
and visually checked real server catalogue content in both system themes.
Screenshots: `verification/wp2-light.png`, `verification/wp2-dark.png`.
Compose component previews compile; IDE preview rendering was not separately verified.

WP2 is the navigation/design foundation. Durable data, wait estimates, reporting,
research-role flows and complete Settings are implemented in subsequent packages.
