# Shared notification / Quick Settings trackpad navigation

Development branch: `feat/shared-shade-status-navigation`

Both shades and the standalone native status-bar focus window now reuse
`ShadeStatusNavigation`, one class responsible for status-zone state,
Left/Right movement, Up bounds, Down return, Enter activation, and Back.

## From the notification shade

1. On the left app-icon column, move **Up** from the first icon. If a native
   media session bar is present, Up from that row reaches the status bar.
2. **Left** selects the notification status-bar zone; **Right** selects
   Quick Settings.
3. On the right-hand zone, press **Enter** / trackpad click to open the
   BBOS6 Manage Connections shade.
4. **Down** returns to the notification content; **Back** retains the
   previously implemented return behavior.

Notification card/action navigation, inline reply, and Backspace/Delete
dismissal retain their existing handlers.

## From the Quick Settings shade

1. Select **Mobile Network**, the first connection row.
2. Press **Up** to focus the right-hand side of the native status bar.
3. Press **Left** to select Notifications.
4. Press **Enter** / trackpad click to switch to the notification shade.
5. Press **Down** (or **Back**) while on the status bar to return to the
   first Quick Settings row.
6. Press **Enter** on the right status zone to return to Quick Settings
   content without reopening the panel.

Up/Down within the three QS rows uses the same **key-down** navigation
convention as the notification icon column. Left/Right within a row
remain bounded: only the status-bar zone changes sections.

## From the standalone status bar

The status-bar focus window also uses `ShadeStatusNavigation`.
Left/Right and Enter select the two shades; Down still opens native
Recents, and Back still dismisses status-bar focus.

## Status-bar highlighting

The native status-bar highlight now follows the active zone in **both**
shades. When content has focus, neither status-bar half is highlighted.
The Android native status-bar remains the visual target and is never
replaced with a duplicate focus overlay.

## Build and test

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/shared-shade-status-navigation || \
git switch --track origin/feat/shared-shade-status-navigation
git pull --ff-only

./gradlew :app:testDebugUnitTest :app:assembleDebug
adb -s 202512003630 install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s 202512003630 shell am crash com.android.systemui
```

Expected version: `0.9.28-shared-shade-navigation-dev`, versionCode 58.

Device acceptance:

- Notifications -> status bar -> QS -> status bar -> Notifications, repeated
  several times with no lost focus or duplicate windows.
- QS: Up from Mobile Network focuses the right-hand status zone; other
  rows move Up to their previous connection row.
- Both status zones: Left/Right change the active highlight; Down returns
  to content, and Enter opens the selected section.
- QS: Back when status focused returns to the first radio row, while Back
  from a radio row closes QS.
- Notifications: existing media focus, app icon/notification pane rules,
  native reply/action, Backspace dismissal, swipe dismissal.
- Original standalone status-bar focus: Down still opens native Recents;
  Back exits, Left/Right/Enter open the intended shade.
- Lock screen, native home, and system-dialog dismissal clean up both
  panels without lingering focus.

The new shared state machine has JVM unit tests in
`ShadeStatusNavigationTest`; actual device testing is still required
before merging.
