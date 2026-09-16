# Fix HyperOS IME

## Purpose

An LSPosed module built with **libxposed API 102** that enables the HyperOS keyboard bottom bar for third-party input methods, including Gboard and WeChat Keyboard. It provides keyboard switching and access to the system clipboard, removes duplicate bottom spacing, and matches the bar color to the keyboard.

The module requires Android 14 or later, a Chinese HyperOS ROM with Xiaomi's keyboard bottom bar implementation, and an LSPosed framework supporting API 102. It operates in the selected keyboard processes and uses `com.miui.phrase` for clipboard access. It has no standalone UI.

## Local Build

Install JDK 17 or later, Android SDK Platform 35, and Android SDK Build Tools 35.0.0. Set `JAVA_HOME` to your JDK and configure the SDK path through `ANDROID_HOME` or `sdk.dir` in `local.properties`.

From the repository root, build a debug APK on Windows:

```powershell
./gradlew.bat :app:assembleDebug
```

On Linux or macOS:

```sh
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

To run unit tests and Android Lint:

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

To build a release APK:

```powershell
./gradlew.bat :app:assembleRelease
```

Use `./gradlew` instead of `./gradlew.bat` on Linux or macOS. The release APK is written to `app/build/outputs/apk/release/app-release-unsigned.apk`. Sign it with your own key using the Android SDK's `apksigner` before installation. Reuse the same signing key for subsequent updates.

## How It Works

- **Bottom bar support:** Hooks `InputMethodServiceInjector` in selected third-party keyboard processes to allow HyperOS keyboard support checks. After `InputMethodModuleManager.loadDex()`, it also hooks the dynamically loaded `InputMethodBottomManager`. System checks for gesture navigation, the optimization setting, orientation, floating mode, and device posture remain in effect.
- **Window insets:** Removes only the bottom navigation bar inset from the keyboard content while the system bottom bar is visible. Other insets and the bottom bar's own inset reads are preserved. Normal behavior resumes when the bar is hidden.
- **Keyboard switching and buttons:** Uses Android's enabled input method list instead of Xiaomi's filtered list. Unsupported vendor-specific button actions fall back to keyboard switching on the left and the clipboard on the right, without changing global settings. Stock Xiaomi keyboards only receive the switching-list adjustment.
- **Color matching:** Samples a one-pixel-high strip above the keyboard/bar boundary, reduces it to 24 pixels in memory, and applies the median color through the system bar's theme API. Button and gesture indicator contrast follows the sampled brightness. Sampling is limited to once every 750 ms; no images are saved.
- **Clipboard access:** Registers each selected keyboard process with `com.miui.phrase` using a Binder token. Read checks for `query` and `getType` require a registered caller whose UID, package, and input method service match the current default keyboard. Registrations expire when the process dies. Write and signature checks remain unchanged, and the module does not log typed text or clipboard contents.
- **Hot reload:** Uses API 102 lifecycle callbacks to remove old listeners, replace hooks, and transfer active keyboard sessions and clipboard registrations when the framework supports module updates without restarting target processes. Unsafe state handoffs are rejected.
