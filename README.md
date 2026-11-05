# BB UI

BlackBerry OS 6/7-inspired interface and hardware-keyboard navigation for
Android on the Zinwa Q25. The current test build is a single **Vector/LSPosed
APK** that integrates with LineageOS SystemUI. The long-term target is a
native LineageOS SystemUI implementation.

**Current merged baseline:** `0.9.84-native-notification-draw-q25-dev`
(versionCode 114). The native shade integration is **opt-in**, not the
default, until its remaining presentation work is completed.

**Development branch:** `feat/native-shade-bbos6-slide` adds an opt-in
BBOS6-style downward entrance and upward dismissal to the existing native
shade window. See [shade slide experiment](docs/NATIVE_SHADE_SLIDE.md).
This animation is not yet in master.

## Start here

- [Design philosophy and architecture](docs/DESIGN_PHILOSOPHY.md) —
  what Android owns, what BB UI changes, and how we avoid redundant systems.
- [Native shade migration](docs/NATIVE_SHADE_MIGRATION.md) —
  implementation, Q25 evidence, limitations and ROM migration steps.
- [Shade and trackpad navigation](SHADE_NAVIGATION.md).
- [Native Quick Settings](docs/NATIVE_QS_UI.md).
- [Native Back integration](docs/NATIVE_SHADE_BACK_ROUTING.md).
- [Historical development notes](docs/HISTORICAL_DEVELOPMENT_NOTES.md) —
  earlier features, commands and branch histories; **not current setup instructions**.

## Architecture in brief

BB UI changes **presentation and physical-key navigation**, not Android's
notification delivery, QS tile services, credentials or system status.

With native shade mode enabled, `UnifiedShadeHost` is mounted **inside
SystemUI's existing `NotificationShadeWindowView`**. It is not a second
full-screen notification window. The real Android status bar continues to own
clock, notification icons, signal, battery and privacy indicators.

BB UI still uses its own notification and QS **view renderers** as an
intermediate migration stage. Stock native rows remain attached behind BB
presentation; they are not a second notification or connectivity *backend*.
The next stage is to replace these interim renderers with native SystemUI
views while keeping the BB layout and trackpad behaviour. See the design
document for the precise boundary.

## Build and test

Requirements: Android SDK 35, JDK 17, globally installed Gradle, ADB and the
Q25's BB UI module enabled in Vector with SystemUI scope.

```sh
git clone https://github.com/NeilRuffell/bb-ui.git
cd bb-ui
gradle :app:assembleDebug :app:testDebugUnitTest
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 202512003630 shell setprop debug.bb7pulldown.enabled true
adb -s 202512003630 shell setprop debug.bb7pulldown.native_shade.enabled true
adb -s 202512003630 shell am crash com.android.systemui
```

The device serial above belongs to the original Q25 development setup;
substitute the serial shown by `adb devices` on other devices.

To return to the earlier standalone BB shade (without changing unrelated
features):

```sh
adb -s 202512003630 shell setprop debug.bb7pulldown.native_shade.enabled false
adb -s 202512003630 shell am crash com.android.systemui
```

This project is **not yet a flashable SystemUI/ROM replacement**. The APK
requires the supported Android/LineageOS runtime and Vector scope.

## Contributing or porting to a ROM

Start with [the architecture contract](docs/DESIGN_PHILOSOPHY.md). Reuse
Android's existing notification, QS, media, status and keyguard machinery.
Do not add competing background services, duplicate status indicators or a
second shade window to achieve a visual change. Device-tested integration
and a rollback path are required when working through ROM-specific APIs.
