# Native BB-UI shade timing diagnostic (Q25)

Branch: `diag/native-shade-timing`, forked from working `feat/native-bb-shade-host`
at commit `56344315657bf7318e0622fe7e825b7ea8d1872e`.

**This change is instrumentation only.** Neither Back/Home routing, animation
durations, native expansion/collapse logic, notification rendering, nor input
consumption behaviour are modified. The working feature branch and master
remain untouched.

## Why

The hardware Q25 reportedly closes BB UI immediately on Home, but takes
nearly one second on Back. The legacy BB view slide animations are disabled
in native mode; measure the physical key path, actual handler callback,
SystemUI native collapse call and post-removal UI-thread responsiveness.

## Activate

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch --track origin/diag/native-shade-timing
gradle :app:assembleDebug :app:testDebugUnitTest &&
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk &&
adb -s 202512003630 shell setprop debug.bb7pulldown.native_shade.enabled true &&
adb -s 202512003630 shell setprop debug.bb7pulldown.shade_latency.enabled true &&
adb -s 202512003630 shell am crash com.android.systemui
```

With shade closed, clear old logs:

```sh
adb -s 202512003630 logcat -c
```

Perform the following on Q25:
1. Open notifications from the BB key/trackpad; press hardware Back once.
2. Open notifications again; press Home once.
3. Open Quick Settings from BB key/trackpad; press Back once.
4. Optional: repeat once by touch-down notification shade + Back.
5. If Back does not close from notification detail, press Back again and
   keep both key events; the logger differentiates navigation-level exit
   from actual shade closure.

Retrieve single tag:

```sh
adb -s 202512003630 logcat -d -v time -s 'BB7-ShadeTiming:I' '*:S'
```

## Logged stages

Each line contains a session number, Android uptime in milliseconds,
milliseconds since `OPEN_BEGIN`, milliseconds since `CLOSE_BEGIN`, and
elapsed time since the first Back DOWN and latest Back UP event, when present.

- `BACK_KEY ... DOWN/UP` includes input-event age and key-held duration.
  These identify whether the delay occurs before the UI receives the key or
  between DOWN and UP (long press, input dispatch, or delayed delivery).
- `BACK_NOTIFICATIONS_REQUEST_SHADE_CLOSE`,
  `BACK_QS_REQUEST_SHADE_CLOSE`, `BACK_STATUS_ZONE_EXIT_CALLBACK` indicate
  which BB navigation level actually asked to close.
- `HOME_CLOSE_SYSTEM_DIALOGS_BROADCAST` identifies Home's observable SystemUI
  entry point; it **does not** measure the physical Home key's earlier handling.
- `DISMISS_SHADE_REQUEST` and `CLOSE_BEGIN` locate the cleanup entry.
- `NATIVE_INSTANT_COLLAPSE_BEGIN` / `_RETURNED` isolate the native
  `NotificationPanelViewController.instantCollapse()` call.
- `CLOSE_NATIVE_CHILD_DETACHED`, `CLOSE_CLEANUP_DONE` isolate detachment
  and observer/session teardown.
- `NEXT_FRAME_CALLBACK_AFTER_CLOSE` reports the next Choreographer callback.
  It **is not** a confirmed rendered or displayed frame. For actual
  compositor presentation timing use Perfetto/SurfaceFlinger tracing.
- Opening stages report notification snapshot refresh, favourites,
  view creation, root mount, media/observer setup, expansion, and the
  next Choreographer callback.

## Interpretation

- Back key first logged ~1 s after physical press: delay before BB input
  delivery; distinguish key hold from event age.
- Back UP prompt, close-request delayed: input focus/navigation-level path.
- Close begins promptly but native collapse returns late: native SystemUI
  expansion/collapse or UI-thread work.
- Close ends promptly but following frame callback is late: UI-thread
  scheduling/traversal may be delayed; frame-present trace still needed.
- Home and Back both have fast cleanup: root cause lies before cleanup,
  not in the discarded 180 ms native-mode animation.

This is a diagnostic only; do not infer causation from a single sample.
If desired, disable the probe independently:

```sh
adb -s 202512003630 shell setprop debug.bb7pulldown.shade_latency.enabled false
adb -s 202512003630 shell am crash com.android.systemui
```

The `feat/native-bb-shade-host` branch remains unchanged and can be
restored by checking it out and reinstalling its APK.
