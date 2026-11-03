# BB UI

A BlackBerry OS 6/7-inspired interface for Android devices with a physical keyboard and trackpad.

BB UI is being developed on the **Zinwa Q25 running LineageOS 24.0 (Android 17)**. It is experimental and closely tied to the SystemUI, Settings, and Quickstep implementations on that device. Compatibility with other devices or ROM versions has not been established.

## What it does

- **Status bar:** Keyboard/trackpad focus, with left and right sections for Notifications and Quick Settings.
- **Notification shade:** Two-column layout, app selection, native notification actions, inline reply, Favorites, and media controls.
- **Quick Settings:** BBOS6-style connection overview for mobile data, Wi-Fi, and Bluetooth. Selecting a row opens Android's existing controls.
- **Recent apps:** Two-column layout using Quickstep's existing task views.
- **Settings:** Type-to-search using the physical keyboard, plus focus styling.
- **Appearance:** Shared BBOS7-inspired selection colors and Android light/dark support where implemented.

The current release is **one APK** (`dev.q25.trackpad`) loaded through a compatible Xposed/LSPosed framework such as Vector. It **does not replace Android's SystemUI APK**. Android still owns the notifications, radio controls, tasks, and underlying services.

## Build from source

Requirements:

- JDK 17
- Gradle 8.11.1 installed on your machine
- Android SDK Platform 35 and Build Tools 35.0.0
- ADB for installation/testing

The repository does **not** contain a Gradle wrapper. Run the installed `gradle` executable.

```sh
git clone https://github.com/NeilRuffell/bb-ui.git
cd bb-ui
gradle :app:testDebugUnitTest :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Run on the development device

The device needs a working, compatible Xposed/LSPosed environment. Install the APK:

```sh
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

Enable **BB UI** in Vector/LSPosed and give it these scopes:

| Android package | BB UI functionality |
| --- | --- |
| `com.android.systemui` | Status bar, notification shade, focus styling, Quick Settings |
| `com.android.launcher3` | Quickstep recent apps |
| `com.android.settings` | Settings search and focus styling |
| `com.android.settings.intelligence` | Settings search integration |

These are the package names used by the development ROM; another ROM may differ. Do not enable obsolete, separate BB UI modules alongside this combined APK.

To enable the shade hook and two-column Recents setting on the development ROM:

```sh
adb shell setprop debug.bb7pulldown.enabled true
adb shell settings put global bb_ui_vertical_recents 1
```

Restart the affected Android processes, or reboot after changing module scopes, so the framework loads the updated hooks.

To disable the custom shade hook if it misbehaves:

```sh
adb shell setprop debug.bb7pulldown.enabled false
adb shell am crash com.android.systemui
```

## Where to start reading

| File or directory | Purpose |
| --- | --- |
| `app/src/main/assets/xposed_init` | Xposed entry-point list |
| `bb7pulldown/.../PullDownHook.java` | Current SystemUI/Xposed integration and shade window lifecycle |
| `bb7pulldown/.../ShadeUiController.java` | Shared focus and shade-page state |
| `bb7pulldown/.../ShadeStatusNavigation.java` | Trackpad and keyboard navigation rules |
| `bb7pulldown/.../UnifiedShadeHost.java` | One shade window with two pages and a shared date header |
| `bb7pulldown/.../TwoColumnPanel.java` | Notification page |
| `bb7pulldown/.../BB6ConnectionsPanel.java` | Quick Settings page |
| `app/src/main/java/dev/q25/trackpad/` | Settings, Quickstep, and shared theme code |
| `bb7focus/src/main/java/` | Settings and SystemUI focus appearance hooks |

The `bb7pulldown` and `bb7focus` directories are **source directories**, not separately built APK modules. Only `:app` is included by `settings.gradle`.

For a closer look at focus behavior, see [SHADE_NAVIGATION.md](SHADE_NAVIGATION.md). Older development notes are in [docs/DEVELOPMENT_HISTORY.md](docs/DEVELOPMENT_HISTORY.md); they are **historical, not current installation instructions**.

## For LineageOS maintainers

See [docs/ROM_PORTING.md](docs/ROM_PORTING.md) for the current design boundaries, what is reusable, and what still requires native SystemUI integration. **This is not a drop-in ROM patch or replacement SystemUI APK.**

## Current limitations

- The app uses ROM-internal Android class names and hooks, which can change between builds.
- A native SystemUI integration has not yet been implemented.
- The existing GitHub Actions workflow refers to older Gradle module targets and needs updating; use the build command above.
- There is currently **no top-level LICENSE file** in this repository. Check the applicable upstream and project licensing before copying or distributing the source in another ROM.
