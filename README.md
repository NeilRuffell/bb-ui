# BB UI

Experimental LSPosed module for BlackBerry-inspired Android UI improvements.

## Feature 1: Type to search in Android Settings

On the **main Settings homepage**, pressing a letter or number on a detected **physical alphabetic keyboard** starts the existing Settings search and attempts to prefill it with that first character. No need to move focus to the search bar. Up/Down/Enter/Back and modifier shortcuts are not intercepted.

### Scope and limitations

- LSPosed scope: `com.android.settings` **and** `com.android.settings.intelligence`.
- Hooks the Settings homepage keyboard dispatcher and the separate Settings Intelligence search screen.
- Does **not** change behavior in other apps, submenus or edit fields.
- Only the initiating character is explicitly transferred; rapid additional typing during the activity transition may be missed.
- Uses AOSP/LineageOS internal class names; **not yet compiled or tested on LineageOS 24.0/Q25**.
- No ROM patching or reflashing.
- Previous Quick Settings experiment remains in source but is not activated.

### Build

Install JDK 17, Android SDK Platform 35, and compatible Gradle tooling. Then:

```sh
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

This repository currently contains no Gradle Wrapper. Use a compatible local Gradle installation.

### Enable and test

1. Open LSPosed and enable **BB UI**.
2. Scope the module to **Settings** (`com.android.settings`) and **Settings Intelligence** (`com.android.settings.intelligence`).
3. Reboot.
4. Open the top-level Settings page with no text field focused.
5. Press a hardware letter key: search should open with that character prefilled.
6. Confirm arrow-key navigation still works, and Settings submenus are unaffected.
7. If there is an issue, disable BB UI in LSPosed and reboot.

## Project status

Research prototype. Compatibility and functionality must be verified on the actual device before calling this a working release.

## Continuous integration

Every push to `main` triggers [Build BB UI debug APK](https://github.com/NeilRuffell/bb-ui/actions). Open the latest successful workflow run and download the `bb-ui-debug-apk` artifact. GitHub Actions artifacts are ZIP files containing the installable `app-debug.apk`. This is a debug build, not a signed production release.

## Feature 2 (v0.2.1-dev): Experimental two-column Recents grid

**This is unverified development code. Do not enable on devices you cannot recover using ADB.**

The module hooks the existing Quickstep `com.android.quickstep.views.RecentsView` where available. It visually repositions its existing task views in a two-column layout, with vertical wheel/touch scrolling and DPAD movement. It does **not** replace the home launcher or create its own replacement task switcher.

Existing Quickstep animations and dismissal calculations still assume horizontal paging. Their compatibility with this prototype is **unknown**. This should not be considered production-ready, and thumbnails or hit targets may misbehave.

1. Build from `dev` using `./gradlew :app:assembleDebug` (or your local Gradle 8.11.1).
2. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. In LSPosed, scope BB UI to **Settings**, **Settings Intelligence**, and the **system Quickstep/Recents provider**. On LineageOS this is typically the system Launcher3/Trebuchet component, **not** your chosen third-party home launcher.
4. Reboot so the Quickstep process loads the new hook.
5. The grid is **off by default**. Enable it from a computer with ADB:

```sh
adb shell settings put global bb_ui_recents_grid 1
```

Open Recent Apps. Exercise vertical swipe/wheel scrolling, trackpad selection, app launch, dismissal, and returning to home. If anything is unstable, disable immediately:

```sh
adb shell settings put global bb_ui_recents_grid 0
```

Then restart the Quickstep process or reboot. If necessary, disable the BB UI scope for the Quickstep provider in LSPosed and reboot.

### Important limitations

- This first iteration **repositions the original task-card views** rather than rewriting Quickstep's pagination/animation internals. It is an experimental visual prototype, not a complete native vertical Recents implementation.
- Task animations, dismissal, rotated layouts, split-task cards, and touch hit testing need actual-device validation.
- The hook has **not been compiled or tested** on LineageOS 24.0 here.
- The prototype does not add notifications to Recents.
- The tested Settings type-to-search feature is unchanged.

### Grid v2 correction (experimental)

The first grid prototype shifted task cards via translations while Quickstep continued paging horizontally; this caused off-screen and missing cards. v0.2.1-dev positions the actual task view bounds, resets horizontal scroll, and intercepts background vertical gestures. It is **not yet device-tested**. Quickstep's internal task animations, dismissal, and transforms still require validation; this is not a complete replacement of horizontal paging. Keep the global toggle off until ready to test, and disable immediately if the overview behaves incorrectly.

### v0.2.2-dev experiment

Quickstep's internal horizontal PagedView scroll and TaskView translations are now constrained in grid mode. The horizontally positioned Clear All button is hidden in this experiment. This is still a hook-based prototype, **not a native replacement of PagedView**; animations or interactions may remain inconsistent. Reboot after disabling grid mode to restore native task-view properties and visibility. **Do not merge to master until validated.**
