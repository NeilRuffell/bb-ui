# BB UI

LSPosed module for physical-keyboard Android improvements.

## Confirmed feature: Type to search in Settings

On the Settings homepage, typing a character on a physical alphabetic keyboard opens Settings search and inserts that character. The user confirmed this feature works on Zinwa Q25 / LineageOS 24.

LSPosed scopes: `com.android.settings` and `com.android.settings.intelligence`.

## Experimental feature: Two visible Recent Apps cards

The abandoned RecentsGridHook and all of its custom layout, input, translation, scroll and preview code were deleted. The new implementation is `RecentsTwoColumnHook.java` only.

The replacement asks Quickstep's existing `PagedView` to show **two native panels horizontally** and adjusts the native task-size Rect to fit two previews. It does not touch `TaskView`, snapshots, click listeners, animations, scrolling, or gesture dispatch. **Horizontal scrolling remains the native Quickstep behavior.** This is not a vertically scrolling grid.

**Off by default; untested on the device.** The native two-panel API is present in LineageOS 24 Launcher3 source, but correct Quickstep rendering with two panels has not yet been verified on the Q25.

LSPosed scope additionally needs the package implementing the **system Quickstep overview**. It does not require changing the default home launcher.

Enable or disable:

```sh
adb shell settings put global bb_ui_recents_grid 0    # retire old flag
adb shell settings put global bb_ui_recents_two_up 1  # enable new two-panel experiment
# Disable:
adb shell settings put global bb_ui_recents_two_up 0
```

Restart Quickstep (or reboot) after changing the setting.

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
