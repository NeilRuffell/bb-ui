# Native BBOS6 shade: touch-following drag

**Branch:** `feat/native-shade-touch-drag`  
**Version:** `0.9.86-native-touch-drag-q25-dev`  
**Status:** Included in `master` as part of version 0.9.86. The user confirmed the touch behaviour on Q25. Gesture regressions remain subject to further on-device testing.

Originally developed on `feat/native-shade-touch-drag`, based on the earlier timed-slide branch.

## Intended behaviour

- Drag down from the existing native status-bar surface: the **existing**
  SystemUI notification-shade window follows the finger.
- Release: finish opening if travel is at least 35% of the total distance,
  or if the downward gesture is sufficiently fast; otherwise return closed.
- Drag upward from within an expanded BB shade: its existing native window
  follows the finger, then finishes closing or springs back open.
- Native drag-to-close starts only when the touched BB content cannot
  scroll further down (including notification and QS lists). Horizontal
  notification swipe and text-entry/seekbar targets are protected.
- If a second pointer appears during a close drag, restore the shade.
- Keyboard/trackpad Back, tapping the status bar, Home, notification action
  launches and screen-off retain their existing routes. Trackpad/keyboard
  open/close still use 220/180 ms automatic slides.
- BB's two-column layout, cards, trackpad focus and native tile/notification
  service ownership are unchanged. No second window.
- Native window root reinflation, immediate dismissal and screen-off
  restore the original translation.

### Boundary

This routes touch through the current Vector module and moves a single
SystemUI shade root. It is **not** a rewrite of Android's touch or notification
services. The real Q25 native `NotificationShadeWindowView` remains the
window owner. Visual smoothness and gesture continuity must be device-tested.

### Switches

The slide animation must be enabled, as on the parent branch.
`debug.bb7pulldown.native_drag.enabled` is enabled by default **when the
native slide itself is enabled**.

Disable touch following but keep timed slide animation:

```sh
adb -s 202512003630 shell setprop debug.bb7pulldown.native_drag.enabled false
adb -s 202512003630 shell am crash com.android.systemui
```

Re-enable touch following:

```sh
adb -s 202512003630 shell setprop debug.bb7pulldown.native_drag.enabled true
adb -s 202512003630 shell am crash com.android.systemui
```

The existing slide rollback `debug.bb7pulldown.native_slide.enabled=false`
remains available, restoring immediate native-shade opening/closing.

## Q25 build/install

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch master
git pull --ff-only
gradle :app:assembleDebug :app:testDebugUnitTest &&
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk &&
adb -s 202512003630 shell setprop debug.bb7pulldown.enabled true &&
adb -s 202512003630 shell setprop debug.bb7pulldown.native_shade.enabled true &&
adb -s 202512003630 shell setprop debug.bb7pulldown.native_slide.enabled true &&
adb -s 202512003630 shell setprop debug.bb7pulldown.native_drag.enabled true &&
adb -s 202512003630 shell am crash com.android.systemui
```

## Hardware checks

1. Slowly pull from the status bar, stop and reverse; the shade should
   track the finger and settle based on distance/direction at release.
2. Flick down quickly; verify the shade finishes opening.
3. Drag up from BB's date header; confirm the whole shade moves up.
4. Drag up from the notification card list **when at its bottom**; confirm
   the shade follows; if it can still scroll, it must scroll instead.
5. Horizontal card dismissal, seekbar, inline reply and QS interaction
   must still work.
6. Press Back while the shade is opening or closing; ensure no stock
   Android collapse animation takes over.
7. Test touch during repeated open/close, Home, screen-off, a notification
   action launch and rotation. Look for stock shade flashes or a stuck
   translated window.
8. Confirm status-bar clock, signal/battery and notification indicators
   stay fixed and do not translate with the shade.

Collect:

```sh
adb -s 202512003630 logcat -d -s \
  'BB7-ShadeSlide:V' 'BB7-NativeShade:V' \
  'BB7-NativeBack:V' 'AndroidRuntime:E' '*:S' | tail -120
```

Expected drag log messages include `Native shade slide prepared ... touch=true`,
`Native shade opening under finger control`, and
`Finger captured native shade close drag`.

The Q25 user confirmed the overall touch interaction; these checks remain
useful when modifying gesture handling or porting to other ROMs.
