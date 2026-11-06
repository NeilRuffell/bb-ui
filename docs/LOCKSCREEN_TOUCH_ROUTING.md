# BB6 lock-screen touchscreen fault — November 2026

**Status:** Investigation corrections merged by owner approval via
[PR #39](https://github.com/NeilRuffell/bb-ui/pull/39).
**Intermittent fault not conclusively eliminated on hardware.**

## Observed on Zinwa Q25 (LineageOS 24)

- Touching or dragging almost anywhere opened a blank stock-looking
  notification shade; ordinary launcher/app touch did not work.
- The physical trackpad still worked. Rebooting did not resolve the fault.
- Twice, setting `debug.bb6lockscreen.enabled=false` and restarting
  `com.android.systemui` by **PID** restored normal touch.
- The separate reported double-tap-to-sleep symptom was not reliably
  reproduced.
- After the investigation build, the symptom was absent during an initial
  Q25 observation. Because occurrence is rare, **this is not proof of a
  permanent fix**. Long-term device stability remains unverified.

A full-screen `NotificationShade` window in `dumpsys window` is not by
itself abnormal; the earlier capture did not prove it was intercepting input.
The exact causal path for full-screen touch-to-shade remains unproven.

## Confirmed source defects corrected

1. `BB6LockScreenHook` removed
   `shared_notification_container` and `notification_stack_scroller`
   from the live SystemUI hierarchy without restricting that removal to
   interactive keyguard. The original hosts were not restored at unlock.
   The code now retains native views and their touch/controller lifecycle.
2. The BB6 blueprint omitted
   `DefaultNotificationStackScrollLayoutSection`. It now preserves that
   section. `NativeNotificationRenderBridge` skips only duplicate stock
   card **drawing**, gated by current BB ownership of the native shade or
   the applied interactive BB6 blueprint; it does not consume ordinary
   launcher/app touch.
3. Earlier Q25 logs contained 268 `BB6-Blueprint`
   `NoSuchMethodError: AodBurnInLayer#getReferencedIds()` occurrences.
   The adapter now checks the actual loaded public method signatures before
   any reference access, verifies attempted replacements and refuses
   unsupported APIs. This fixes an independently confirmed compatibility
   defect, **not a proven cause of the touchscreen fault**.

The selection-gradient changes preceding the observed recurrence did not
modify the blueprint/AOD code. These repairs were kept separate from
unrelated QS, launcher, media and Settings features.

## Verification and remaining limitations

- GitHub Actions for source commit `dbd78e7d85547ad728ca65a31a540e8312e540da`
  passed `gradle :app:assembleDebug :app:testDebugUnitTest`:
  [successful run](https://github.com/NeilRuffell/bb-ui/actions/runs/42148324828).
- Regression tests cover native notification rendering ownership,
  locked/unlocked blueprint policy, and AOD method compatibility.
- Source and CI results do **not** establish that native Q25 touch routing
  is fixed indefinitely, or that every AOD implementation is compatible.
- PR #39 was approved for merge after a short symptom-free physical-device
  observation. Keep monitoring under ordinary use; do not label this
  device-verified permanent resolution.

## If the touchscreen fault returns

Do not redo the completed broad isolation steps or assume the AOD exception
is the input fault. When needed, capture targeted native shade/keyguard
state and input ownership for the failing session. A temporary, previously
successful recovery is:

```sh
SERIAL=202512003630
adb -s "$SERIAL" shell setprop debug.bb6lockscreen.enabled false
PID=$(adb -s "$SERIAL" shell pidof com.android.systemui | tr -d '\r')
adb -s "$SERIAL" shell am crash "$PID"
```

This property is temporary and may revert at reboot; it is not a
permanent fix. The Q25 does not expose a usable `su` binary, and
`adb shell am crash com.android.systemui` did not reliably restart the
process. Use the PID-targeted restart above.

## Native ROM integration requirement

Never detach a notification host that is still used by SystemUI for
notification controllers, shade transitions or touch dispatch.
Use a source-level keyguard presentation switch, preserve the native
notification pipeline and AOD references, and validate on the actual
LineageOS/Q25 runtime. See
[ROM integration guidance](../rom/lineage-24.0/LOCKSCREEN_INTEGRATION.md).
