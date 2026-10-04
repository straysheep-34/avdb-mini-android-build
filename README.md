# AVDB Mini Android — GeckoView Edition

This is a clean Android rebuild of AVDB Mini based on a standard Android Activity and Mozilla GeckoView.
It intentionally does **not** use NativeActivity, Android System WebView, Chrome, Edge, or the user's browser profile.

## Architecture

- Standard `android.app.Activity`
- Mozilla GeckoView 156 stable, bundled into the APK by Gradle
- Local loopback HTTP server (`127.0.0.1`) serves the existing AVDB Mini UI and proxies AVDB API requests, avoiding CORS
- `NavigationDelegate.onNewSession()` returns null, so popup/new-window requests fail before another browser window exists
- Top-level navigation is locked to the local app origin; playback URLs are allowed only as subframes
- GeckoView `ContentDelegate.onFullScreen()` drives Android immersive mode; Back exits HTML fullscreen via `GeckoSession.exitFullScreen()`
- GeckoView storage belongs to the app's private Android sandbox

## Versions

- Android Gradle Plugin: 9.3.0
- Compile SDK: 36
- Java: 17+
- GeckoView stable: `org.mozilla.geckoview:geckoview:156.0.20260921121718`
- minSdk: 26 (Android 8.0)

## Build

### Easiest on Windows

Double-click `build-apk.bat`. It downloads a local Gradle 9.5 + Android SDK 36 toolchain into `.android-build`, then builds a signed debug APK. It does not install Chrome/Edge or use System WebView.

Output: `AVDB-Mini-Android-Gecko-v2.0.apk`

### Android Studio

Open this folder in Android Studio and let Gradle sync, then Build > Build APK(s).
The first build downloads GeckoView (~230 MB) and its dependencies from Mozilla/Google/Maven Central.

Command line (with Gradle 9.5 and Android SDK 36 installed):

    gradle :app:assembleDebug

APK output:

    app/build/outputs/apk/debug/app-debug.apk

## Important

GeckoView is deliberately large because the browser engine is bundled with the app. This is what makes the runtime independent of Chrome/System WebView.

## v2.0.3 build-script fix

You no longer need to install Java manually. `build-apk.bat` now:

1. Looks for Java 17+ in `JAVA_HOME`, PATH, Microsoft JDK, Eclipse Temurin, Oracle/OpenJDK, Amazon Corretto, Zulu, and Android Studio JBR.
2. If none is found, downloads a private portable Eclipse Temurin JDK 21 into `.android-build/jdk21`.
3. Sets `JAVA_HOME` only for the current build process. It does not modify Windows permanently.
4. Continues downloading Gradle, Android SDK and GeckoView, then creates `AVDB-Mini-Android-Gecko-v2.0.3.apk`.

If a previous Java download was interrupted, delete `.android-build/temurin-jdk21.zip` and run `build-apk.bat` again.


## v2.0.3 builder fix
If a previous build leaves an incomplete `.android-build/jdk21` directory or a corrupted JDK ZIP, the builder now detects and cleans it automatically. JDK extraction recursively finds the actual `bin\java.exe` instead of assuming a fixed archive folder name.


## v2.0.3 Java detection fix

Windows PowerShell 5.1 can treat the normal STDERR output from `java -version` as an exception when `$ErrorActionPreference = Stop`. This release probes Java through `.NET ProcessStartInfo`, so a valid JDK 17/21 on `JAVA_HOME` or `PATH` is detected correctly. Run `check-java.bat` if you want to see exactly which Java executable and major version the builder sees.
