# TouchLock Edge (Android)

Minimal Android application (`minSdk 26`, `targetSdk 35`) that locks screen touch input across all apps using a full-screen `TYPE_APPLICATION_OVERLAY` window while keeping a compact toggle button docked on the left or right edge of the screen to switch the touch lock **ON** and **OFF** at any time.

## How the Overlay Works

`TouchLockService` runs as a foreground service (`specialUse`) and attaches two separate overlay windows to Android's `WindowManager` in strict Z-order:

1. **Full-Screen Touch Blocker (`touchBlockerView`, added first / lower Z-order)**:
   - **Unlocked state**: Configured with `FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE` and `View.GONE` so 100% of screen touches pass directly to underlying apps.
   - **Locked state**: Removes `FLAG_NOT_TOUCHABLE` and spans `MATCH_PARENT x MATCH_PARENT` (`FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS`), consuming all touch events on the screen so accidental touches have no effect.
2. **Edge Toggle Button (`edgeButtonView`, added second / higher Z-order)**:
   - Sits above `touchBlockerView` in `WindowManager` Z-order on the left or right screen edge (`WRAP_CONTENT x WRAP_CONTENT`).
   - Remains interactive even when `touchBlockerView` blocks the rest of the screen, allowing you to switch the touch lock ON and OFF with a single tap (or optional double-tap to unlock).
   - Can be dragged vertically along the screen edge or dragged across the screen to snap to the opposite edge.

## Building with `gradlew`

### Local Build

Ensure you have **JDK 17+** and the **Android SDK** (`ANDROID_HOME`) installed, then run:

```bash
chmod +x ./gradlew
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The compiled APK will be generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Building on GitHub Actions

This repository includes `.github/workflows/build.yml` and the complete Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`). Pushing to `main`/`master` or triggering the workflow manually in the **Actions** tab will run `./gradlew assembleDebug` and upload `app-debug.apk` as a downloadable build artifact.

## Fixing Grayed-Out Overlay Permission (Android 13–16 Restricted Settings / Advanced Protection)

When you sideload an APK downloaded from a browser or GitHub Actions on Android 13+, Android marks it as a non-store session install and grays out sensitive permissions like **Display over other apps**.

### Method 1: "Allow restricted settings" in App Info (Android 13 / 14 / 15)
1. First, tap **Grant Overlay Permission** in the app and tap the grayed-out switch once so Android registers the attempt.
2. Go back to the app and tap **Open App Info (⋮ → Allow Restricted Settings)** (or go to **Settings → Apps → All apps → TouchLock Edge**).
3. Tap the **3-dot menu (`⋮`)** in the top-right corner of the App Info screen and select **Allow restricted settings** (verify with your PIN/fingerprint).
4. Return to **Display over other apps** and enable **TouchLock Edge**.

### Method 2: Grant Directly via ADB (Works Even When Advanced Protection Blocks the UI Toggle)
If **Android Advanced Protection**, **Google Advanced Protection**, or **Samsung Auto Blocker** hides or disables the `Allow restricted settings` menu, you can grant the overlay permission directly via ADB (from a PC or on-device Wireless Debugging with Shizuku / aShell):

```bash
# Grant SYSTEM_ALERT_WINDOW directly:
adb shell appops set com.touchlock.edge SYSTEM_ALERT_WINDOW allow

# Or install the APK using the Play Store package installer flag so Android does not mark it as restricted:
adb install -i com.android.vending app-debug.apk
```

### Method 3: Samsung Auto Blocker / Android Advanced Protection Toggle
- **Samsung One UI 6+**: Open **Settings → Security and privacy → Auto Blocker** and temporarily toggle it off while granting the permission.
- **Android 15/16 Advanced Protection / Google Play Protect**: Open **Settings → Security & privacy → Advanced Protection** (or **Google Play Store → Profile → Play Protect → Settings**) if device protection is blocking sideloaded app permissions.

