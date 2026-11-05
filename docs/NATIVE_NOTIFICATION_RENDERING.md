# Native notification drawing experiment (Vector)

**Branch:** `feat/native-notification-draw-policy`  
**Status:** Experimental, requires Q25 runtime validation. Not yet in master.

## Goal

Keep the BBOS6 two-column notification presentation **exactly as it is**:
app icons on the left, notification cards/actions on the right, Backspace
dismissal, media and keyboard/trackpad navigation. Reuse the single existing
SystemUI shade window and the Android-owned notification service.

The native Android `NotificationStackScrollLayout` ordinarily owns views of
its own. When BB-UI mounts an opaque child ABOVE it, drawing those same
stock notification cards is unnecessary.

## Implementation

`NativeNotificationRenderBridge` hooks only the native stack's own
`dispatchDraw(Canvas)` method. Before its original draw pass, the bridge
checks that this particular native stack is INSIDE the same currently mounted
`NotificationShadeWindowView` as the BB host. If so, it skips that native
stack's child drawing; all other windows and all non-BB sessions call the
original native method.

This avoids constructing a second window or reparenting/destroying native
notification views. It preserves Android's notification records, native
row/controller life cycle, click listeners and action services. If the Q25
signature is unavailable the feature logs a warning and leaves stock
drawing unchanged.

A separate change removes the duplicate `OPEN_ACTIONS` map. Notification
opening now reads the active `StatusBarNotification`'s app-supplied
`PendingIntent` directly, using the original content/fullscreen priority.
The initial notification snapshot also reads native ranking once per refresh
instead of once per notification.

### Critical limitation

**This prevents double drawing; it does not prevent native row inflation or
destroy all duplicate renderer objects.** Vector cannot safely remove stock
row construction while Android's NotifPipeline still depends on the row
controllers, without a tested platform-level integration. The final ROM
should install the BB renderer in native SystemUI's presentation lifecycle
and stop building the replaced stock view tree at its source.

The existing BB6 lock-screen hook can permanently remove the native
notification stack. This experiment does NOT modify the lock screen. In
that configuration, there may be no stock stack left to draw, and no
`Stock notification card drawing skipped...` log to emit. The device should
still display the existing BB notifications.

## Q25 install/test

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch --track origin/feat/native-notification-draw-policy
gradle :app:assembleDebug :app:testDebugUnitTest &&
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk &&
adb -s 202512003630 shell setprop debug.bb7pulldown.enabled true &&
adb -s 202512003630 shell setprop debug.bb7pulldown.native_shade.enabled true &&
adb -s 202512003630 shell setprop debug.bb7pulldown.native_notif_draw.enabled true &&
adb -s 202512003630 shell am crash com.android.systemui
```

Open/close the notification shade, open cards, click and dismiss, test
Backspace, inline reply, action buttons, media, QS, heads-up, Back/Home,
and lock/unlock. Ensure all existing interaction still behaves the same.

```sh
adb -s 202512003630 logcat -d -s \
  'BB7-NotifRender:V' 'BB7-NativeShade:V' 'AndroidRuntime:E' '*:S' | tail -90
```

Expected registration:
`Q25 native notification dispatchDraw gate installed`.

If a stock native row is present beneath BB and its draw method runs,
expect `Stock notification card drawing skipped behind mounted BB shade`.
Absence of that second message is NOT proof of a fault, since the existing
lock-screen module may already have removed the stock stack.

## Independent rollback

```sh
adb -s 202512003630 shell setprop debug.bb7pulldown.native_notif_draw.enabled false
adb -s 202512003630 shell am crash com.android.systemui
```

This disables only the drawing gate; it does not disable the working
native BB shade or its Back fix.
