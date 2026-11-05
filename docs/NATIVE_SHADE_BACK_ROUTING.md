# Q25 native shade Back integration

Branch: `fix/native-shade-back-routing`
Base: proven Q25 BB native shade `feat/native-bb-shade-host` at
`56344315657bf7318e0622fe7e825b7ea8d1872e`.

## Source-level cause

The uploaded Q25 SystemUI.apk contains
`com.android.systemui.back.domain.interactor.BackActionInteractor`
with `onBackRequested(): boolean`. LineageOS 24's implementation calls
`ShadeController.animateCollapseShade()` on normal Back navigation.
It can also take a separate QS-expanded Back path.

BB UI already closes by `NotificationPanelViewController.instantCollapse()`.
Prior Q25 timing showed native collapse/host teardown taking 4-11 ms, while
Back delivered no KeyEvent to BB's child views.

## Fix

`NativeShadeBackBridge` registers an Xposed hook on the exact Q25 method.
It only intercepts when the BB host is actually attached to
`NotificationShadeWindowView` and the callback is on the main UI thread.

- Status bar focused -> existing `ShadeStatusNavigation` Back handler.
- Notification view -> existing reply, media controls, notification list and
  final-shade-close navigation levels.
- Quick Settings -> same close callback as the existing QS key listener.
- Other SystemUI states -> unchanged native Android Back handler.
- Home, swipe-dismiss, lockscreen, native QS tile services -> unchanged.

The bridge returns `true` to native `onBackRequested()` ONLY after the BB
navigation action succeeds. It does not synthesize Back key events or add
an animation. The previous `TwoColumnPanel` key handler now calls the exact
same Back function used by the native callback.

## Q25 install and test

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch --track origin/fix/native-shade-back-routing
gradle :app:assembleDebug :app:testDebugUnitTest &&
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk &&
adb -s 202512003630 shell setprop debug.bb7pulldown.enabled true &&
adb -s 202512003630 shell setprop debug.bb7pulldown.native_shade.enabled true &&
adb -s 202512003630 shell am crash com.android.systemui
```

Diagnostics:

```sh
adb -s 202512003630 logcat -d -s 'BB7-NativeBack:V' \
  'BB7-NativeShade:V' 'BB7-PullDown:V' '*:S' | tail -90
```

Expected:
- `Q25 BackActionInteractor.onBackRequested() reroute registered`
- `BB native Back handled directly elapsedMs=...` after a Back action.

Test Back with the notification app icon list focused, in notification
detail, in inline reply, in media controls, in QS, and with the blue
status-bar selection. Verify nested Back does not close the entire shade
prematurely. Compare with Home for closing performance.

**Limitations:** This fixes one native callback; real Q25 visual performance
must be confirmed with hardware testing. If the callback never fires or a
different predictive Back route handles the key, that must be measured
instead of extending hooks by guesswork.

## Rollback

This is a test branch. The known working feature branch and master
are untouched. Return to the working feature branch:

```sh
cd ~/Projects/bb-ui
git switch feat/native-bb-shade-host
git pull --ff-only
gradle :app:assembleDebug &&
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk &&
adb -s 202512003630 shell am crash com.android.systemui
```
