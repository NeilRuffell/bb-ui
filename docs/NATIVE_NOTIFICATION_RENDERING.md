# Native notification drawing (Vector)

**Status:** Updated by BB6 lock-screen investigation PR #39, approved for
merge in November 2026. See [touchscreen investigation](LOCKSCREEN_TOUCH_ROUTING.md)
for the remaining intermittent-device limitation.

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
checks that the stack belongs either to the currently mounted BB shade in
the same `NotificationShadeWindowView`, or to the *active* BB6 interactive
lock-screen blueprint in that window. If so, it skips the native stack's
child drawing; otherwise the original native drawing method is called.

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

Previous BB6 lock-screen builds could permanently remove the native
notification containers, even when the device was unlocked. PR #39 removes
that behavior. The native notification-stack section stays in the blueprint,
and its view hierarchy, touch routing and controllers remain attached.
Only drawing of duplicate cards is gated while the BB6 blueprint is applied
and the live keyguard state is interactive. This is a code correction, but
the rare touchscreen fault's exact runtime cause remains unproven.

## Q25 install/test

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch master
git pull --ff-only
gradle :app:assembleDebug :app:testDebugUnitTest &&
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk &&
adb -s 202512003630 shell setprop debug.bb7pulldown.enabled true &&
adb -s 202512003630 shell setprop debug.bb7pulldown.native_shade.enabled true &&
adb -s 202512003630 shell setprop debug.bb7pulldown.native_notif_draw.enabled true &&
PID=$(adb -s 202512003630 shell pidof com.android.systemui | tr -d '\r')
adb -s 202512003630 shell am crash "$PID"
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
expect `Stock notification cards skipped in BB-owned shade/lockscreen; native rows retained`.
Absence of this second message is not proof of a fault: it appears only when
the native stack performs a draw while BBUI currently owns the presentation.

## Independent rollback

```sh
adb -s 202512003630 shell setprop debug.bb7pulldown.native_notif_draw.enabled false
adb -s 202512003630 shell am crash com.android.systemui
```

This disables only the drawing gate; it does not disable the BB shade or
its Back integration. Native stock cards may become visible behind the
BB presentation. To recover from the *touchscreen fault*, use the separate
BB6 lock-screen rollback in [the investigation notes](LOCKSCREEN_TOUCH_ROUTING.md).
