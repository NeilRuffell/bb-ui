# BBOS6-style slide on the native SystemUI shade

**Branch:** `feat/native-shade-bbos6-slide`  
**Version:** `0.9.85-native-bbos6-slide-q25-dev`  
**Status:** Experimental Vector feature; requires physical Q25 testing.

## Goal

Open BB notifications/QS by sliding the existing shade **down** from the
top of the screen; close with Back or an upward shade gesture by sliding
**up** offscreen. Keep the real native status bar stationary and preserve
BB two-column notifications, media, native QS services, trackpad focus and
the fast native Back callback.

## How it works

- The same `NotificationShadeWindowView` is translated vertically; there
  is no extra window, duplicate notification host or full-screen overlay.
- The BB host remains attached ABOVE the native stock shade contents
  throughout motion, so underlying QS and notification views move with it
  instead of peeking through.
- On open, the native window begins above the display. After the existing
  SystemUI panel is expanded, the window moves down over 220 ms with
  deceleration. Focus is given to the BB page at the end.
- On a normal Back/upward-gesture dismissal, movement **starts immediately**
  and travels up over 180 ms. Only after that does the native panel collapse
  and detach BB-UI. This preserves native Back dispatch rather than returning
  to the old slow stock collapse animation.
- Back during an opening slide reverses the movement; repeated Back during
  closure is consumed without launching a competing Android animation.
- Home, screen-off, launching an app and opening native QS detail dialogs
  keep their existing immediate cleanup, to avoid lingering sensitive
  content or interfering with Android-owned windows.
- The window's prior translation is always restored on normal cleanup,
  exceptions or an interrupted native-window reinflation.

**Note:** 220/180 ms are BB-UI design choices, not claims about exact
historical BlackBerry OS 6 transition timings.

## Rollback and safety

The animation is OFF by default. Set this debug property to enable it
without changing the already-tested native shade or Back path:

```sh
adb -s 202512003630 shell setprop debug.bb7pulldown.native_slide.enabled true
adb -s 202512003630 shell am crash com.android.systemui
```

Disable only the animation:

```sh
adb -s 202512003630 shell setprop debug.bb7pulldown.native_slide.enabled false
adb -s 202512003630 shell am crash com.android.systemui
```

The independent native-shade rollback is still
`debug.bb7pulldown.native_shade.enabled=false`.

## Q25 install

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch --track origin/feat/native-shade-bbos6-slide
gradle :app:assembleDebug :app:testDebugUnitTest &&
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk &&
adb -s 202512003630 shell setprop debug.bb7pulldown.enabled true &&
adb -s 202512003630 shell setprop debug.bb7pulldown.native_shade.enabled true &&
adb -s 202512003630 shell setprop debug.bb7pulldown.native_slide.enabled true &&
adb -s 202512003630 shell am crash com.android.systemui
```

## Device tests

1. Open notifications by touch and trackpad; see a downward slide.
2. Press Back in notifications, and close QS; see an upward slide.
3. Back inside notification details first returns to the app column;
   it must not dismiss the shade prematurely.
4. Press Back **during** the opening animation; it must reverse cleanly.
5. Press Home, launch a notification action, or turn off the screen;
   closure must remain immediate and restore native view position.
6. Verify original clock, status icons and blue focus remain stationary.
7. Check media, QS dialogs, replies, heads-up, repeated opening/closing
   and orientation/keyguard transitions.
8. Check there is no flash of stock Android cards or native QS underneath.

Collect logs after testing:

```sh
adb -s 202512003630 logcat -d -s \
  'BB7-ShadeSlide:V' 'BB7-NativeBack:V' \
  'BB7-NativeShade:V' 'AndroidRuntime:E' '*:S' | tail -110
```

Expected lifecycle: `slide prepared`, `sliding down`,
`slide-down completed`, `sliding up`, `slide-up completed`,
`original ... position restored`. A successful CI build does not prove
that native root translation displays correctly on Q25 hardware.

## ROM future

Replace the experimental view-root animation bridge with source-level
native SystemUI shade transition control; reuse its single shade
window and BB content. Never introduce a second shade window.
