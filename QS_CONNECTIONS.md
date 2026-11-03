> **Historical feature-branch notes.** This page predates the unified two-page shade and shared focus controller. For the current architecture, read [README.md](README.md) and [SHADE_NAVIGATION.md](SHADE_NAVIGATION.md). The implementation details below may no longer apply.

# BBOS6 Manage Connections — first QS layout branch

Branch: `feat/bbos6-quick-settings-connections`

## Scope

Only the top of the BlackBerry Curve 9300 / BBOS6 drop-down is implemented:

- Shared blue date-only header, also used by the notification shade.
- **Manage Connections** group directly below it.
- **Mobile Network**, **Wi-Fi**, and **Bluetooth**, in that order.
- Native state indicators and focus selection compatible with touch/trackpad.
- Nothing below this group is implemented yet. The remainder of the panel is blank.

The right-hand status-bar focus zone now opens this panel instead of stock QS.
The existing left-hand notification zone and its gestures are unchanged.
This is a BBOS6-inspired layout, not an attempt to copy obsolete setup/options.

## Shared date header

Both the notification and Quick Settings shades now construct
`ShadeDateHeader`, a single reusable `TextClock` view. It displays
today's date in the **existing notification-shade format**
(`EEE, MMM d, yyyy`), centered using the **former QS clock typography**
(22sp, normal weight) and a common 49dp blue-gradient band.
There is no second clock: the native Android status bar already shows time.

The original notification-group date separators (`Today` or the
date for older notifications) are intentionally unchanged.
The shared `TextClock` updates when the date or timezone changes.

## Native Android behavior

Android 12+ combines Wi-Fi and cellular connectivity into its native
**Internet** QS tile. The two separate BBOS6-style rows deliberately launch
that same existing SystemUI tile when it is installed; the Bluetooth row
launches Android's existing **bt** tile. No SystemUI tile spec is added or
reconfigured, and no radio toggle is implemented independently of Android.

If SystemUI cannot provide the native tile, the mobile row falls back to
Android's public Internet settings panel, Wi-Fi to the Wi-Fi settings panel,
and Bluetooth to Bluetooth Settings. If even these cannot open, stock QS
expansion remains a final fallback. Touch or Enter opens the corresponding
native control UI; the checkmarks are **status indicators**, not immediate
one-tap toggles.

"Mobile Network" shows the **mobile data setting** ("Data on/off"), which
does not claim that the mobile modem is powered on or registered to a carrier.
Wi-Fi and Bluetooth show their corresponding enabled state. When a platform
API denies status access, that row explicitly displays "Unknown" rather
than inventing a radio state.

## Navigation and lifecycle

- Right status-bar zone, Enter -> new BBOS6 Manage Connections panel.
- Up/Down -> sequentially select Mobile Network, Wi-Fi, Bluetooth.
- Left/Right -> stay on the same selected row.
- Enter/trackpad click or touch tap -> native connection UI.
- Back or swipe up -> close the BBOS6 panel.
- Screen-off and native system-dialog close broadcasts remove the panel.
- Native Wi-Fi, Bluetooth, airplane-mode and subscription-change events
  trigger status re-reads while the panel is open.
- The normal native status bar remains in place above the panel.

## Build and install

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch --track origin/feat/bbos6-quick-settings-connections
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb -s 202512003630 install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s 202512003630 shell am crash com.android.systemui
```

Expected package version: `0.9.27-bbos6-shared-date-dev` (versionCode 57).

Validate the same centered date header in **both** shades, followed by touch,
trackpad, native state and action handling, media playback,
incoming calls, Bluetooth/Wi-Fi disabled and enabled, no-SIM state, and
dark/light UI mode before merging. Actual LineageOS 24.0 system images may
differ in internal SystemUI tile host implementation; the public Android
settings fallbacks are intentional.

Rollback the entire BB7 shade hook if necessary:

```sh
adb -s 202512003630 shell setprop debug.bb7pulldown.enabled false
adb -s 202512003630 shell am crash com.android.systemui
```

This branch is not merged until device testing.
