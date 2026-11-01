# BB UI

LSPosed module for physical-keyboard Android improvements.

## Settings type-to-search

The existing Settings search implementation is **unchanged** from `main`.
Keep scope to `com.android.settings` and `com.android.settings.intelligence`.

## Experimental vertical native-TaskView Recents

Branch: `feat/native-vertical-recents` (from `main`, **not** from the
previous Recents `dev` branch).

This experiment uses **existing Quickstep TaskView objects** and the existing
thumbnail loader, click handling and app state. It does not draw duplicate
screenshots, build its own task list, or install a second Recents activity.

The goal is two portrait columns in vertically scrolling rows.
The adapter inserts geometry *during* native `getPageScrolls` layout, before
native `RecentsView.onLayout` finishes; it uses PagedView's own
`computeMaxScroll`/`scrollTo` methods, and lets Quickstep supply TaskView
measurement, previews and other task functions.

**Important:** LineageOS Quickstep natively provides a horizontal pager and
a horizontal two-row grid, **not** a switch for a portrait two-column vertical
overview. An LSPosed adapter must still change Quickstep paging geometry and
handle vertical input. This code is source-reviewed but has **not** been proven
on the Q25. Animation, task dismissal, keyboard focus and rotations need device
tests. Do not call it a fully native, verified Recents mode.

### Scope

Add the active Quickstep/Recents provider package to LSPosed scope in addition
to the independent Settings scopes. The module detects
`com.android.quickstep.views.RecentsView` in that process.

### Enable for testing

```sh
adb shell settings put global bb_ui_vertical_recents 1
adb reboot
```

### Restore the stock Quickstep Recents layout

```sh
adb shell settings put global bb_ui_vertical_recents 0
adb reboot
```

The default is OFF. The setting is cached per RecentsView instance; reboot
after changing it.

### Inspect

```sh
adb logcat -d -s BB-UI-Recents:W '*:S'
adb shell settings get global bb_ui_vertical_recents
```

Verify: two columns; vertical wheel scrolling; drag from background; previews
(including running app); app launch; swipe dismissal; clear all; return to home;
recents re-entry; rotation; physical keyboard; trackpad hit targets. Report any
failures before merging.

### Build

JDK 17, Android SDK Platform 35 and Gradle 8.11.1:

```sh
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions builds an APK artifact on pushes to this feature branch.

## Branch policy

- `main`: unchanged Settings baseline
- `dev`: archived/experimental old Recents attempts, not reused
- `feat/native-vertical-recents`: isolated Recents replacement for testing
