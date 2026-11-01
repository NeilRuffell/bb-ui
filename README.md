# BB UI

LSPosed module for physical-keyboard Android improvements.

## Confirmed feature: Type to search in Settings

On the Settings homepage, typing a character on a physical alphabetic keyboard opens Settings search and inserts that character. The user confirmed this feature works on Zinwa Q25 / LineageOS 24.

LSPosed scopes: `com.android.settings` and `com.android.settings.intelligence`.

## Recent Apps: two native columns and experimental vertical list

**Working baseline:** The original native two-panel Recents implementation is preserved on
`recents-two-up-baseline` (v0.3.0-dev). It displays two complete previews side by side,
with original horizontal paging, and was confirmed working on the Q25.

**New experimental option (v0.4.0-dev):** `RecentsVerticalListHook` puts Quickstep's
existing two-up TaskView objects into vertically stacked rows. It preserves the existing
TaskView thumbnail implementations, transforms, click handlers and dismissal listeners.
It does not reparent tasks, replace previews or modify screenshot data.

Vertical navigation: trackpad wheel; touch drag starting in the background; or
two-finger drag. One-finger swipes beginning on task previews remain handled by Quickstep.
This is an experimental geometry/input adaptation over Quickstep's horizontal PagedView,
**not** a native vertical Recents mode. Native animations, live tiles, screen rotation,
and task dismissal still require device testing.

Enable both flags:

```sh
adb shell settings put global bb_ui_recents_two_up 1
adb shell settings put global bb_ui_recents_vertical_list 1
adb reboot
```

**Immediate fallback to the tested horizontal two-up implementation:**

```sh
adb shell settings put global bb_ui_recents_vertical_list 0
adb reboot
```

All Recents work remains on `dev`. `master` is unchanged.

LSPosed requires scope to the installed **Quickstep/Recents provider** (plus Settings scopes
for the independent Settings search feature). It does not require changing the home launcher.

## Local build

Requires JDK 17+, Android SDK Platform 35 and Gradle 8.11.1.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb reboot
```

This repository's Gradle Wrapper can be copied from an existing project using the correct version if not yet committed.

## Branches

`master`: device-tested Settings search baseline.
`dev`: experimental Recents work, not yet validated or merged.
