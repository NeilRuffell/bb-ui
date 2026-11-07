# BBOS6 Recents replacement — one native owner

## Decision and hard constraint

Replace the old portrait two-column Quickstep task-card layout with a BlackBerry OS 6-style grid of **application icons**. Continue to use Android's actual task and activity management, and the Q25's existing Launcher3/Quickstep overview entry point. The Vector/LSPosed APK is the current integration vehicle; a native ROM implementation is the target.

**One Recents presentation at a time.** Do not add a second Activity, floating overlay, custom task backend, hidden stock Recents UI, or duplicate Quickstep view hierarchy. A hidden/invisible/intercepted stock renderer beneath BBUI is unacceptable. If the required Quickstep host cannot be adapted to mount exactly one renderer under Vector, stop and document that limitation rather than shipping a second UI.

## Current baseline — stock restored

Branch: `feat/bbos6-recents-replacement`, based on `master`.

- Deleted `app/src/main/java/dev/q25/trackpad/NativeRecentsGridHook.java` rather than disabling it behind `bb_ui_vertical_recents`.
- Removed its `app/src/main/assets/xposed_init` entry, so BBUI no longer installs any old Recents grid hooks.
- Removed the Launcher3-to-SystemUI `RECENTS_VISIBILITY` broadcast and stale status-bar appearance state; these existed solely for the retired Recents grid.
- Preserved the SystemUI BlackBerry-key popup and changed `showNativeRecents()` to forward the existing `CommandQueue.toggleRecentApps()` command with a thread-local bypass of our BB-key interception. The native command is posted to the main looper **after** the menu's input handler returns; no timed workaround is used. The menu is **not** a Recents renderer. On-device confirmation is pending.
- The active Recents presentation is now stock Quickstep **only**. This is a transitional baseline, not the final BBOS6 design.
- The deprecated global setting `bb_ui_vertical_recents` is obsolete and can be deleted on the device.

## Confirmed source-level integration boundary

The retired hook targeted these Q25 Quickstep classes:

- Process/package: `com.android.launcher3`
- `com.android.quickstep.views.RecentsView`
- `com.android.quickstep.views.TaskView`
- `com.android.launcher3.PagedView`
- `com.android.quickstep.orientation.RecentsPagedOrientationHandler`

Those references verify which implementation the old code targeted, **not** that a replacement renderer has been tested on the current device build. Inspect installed Quickstep signatures and native layout ownership before implementing hooks.

SystemUI receives the BlackBerry-key Recents command in `PullDownHook.installRecentsInterception()`. Its popup's Switch Application action forwards the **same** native `toggleRecentApps()` command without recursively opening the BB menu. The CommandQueue owner is referenced from its constructor as well as native toggle callbacks. Keep this entry routing distinct from Quickstep's visual renderer. Do not add a competing Recents command path.

## Native Recents dispatch investigation (0.9.98)

The Q25 logs supplied after the earlier 0.9.97 build show the BlackBerry menu opened and `Switch Application forwarded to native Quickstep toggle` logged. This only proves `CommandQueue.toggleRecentApps()` returned; Android enqueues this event for asynchronous delivery. No real Quickstep transition is verified by that log. There is also a `Native Recents command queue unavailable` warning from a separate class-loader entry, alongside successful registration on the first entry, so do not mistake the warning for proof that the working hook is absent.

The 0.9.98 investigation:
- Posts the **same native** Quickstep toggle after the popup Enter key dispatch has returned, avoiding an in-progress overlay/window transition.
- Adds read-only hooks on native `Recents.toggleRecentApps()` and `OverviewProxyRecentsImpl.toggleRecentApps()` when those classes exist, logging whether dispatch reaches the callback.
- For `OverviewProxyRecentsImpl`, reads its existing `OverviewProxyService.getProxy()` connection, logging `Native Quickstep proxy connected=true/false`. Does not construct a new service, connection, launcher or Recents view.
- Keeps stock Quickstep as the exclusive Recents presentation. The result must be tested on Q25.

Interpretation after pressing **Switch Application**:
- `Switch Application native toggle queued` only: native CommandQueue message queued; callback dispatch or Quickstep connection is not yet demonstrated.
- `Native Recents callback reached`: downstream SystemUI callback actually received the request.
- `Native Quickstep proxy connected=false`: native SystemUI has no connected Overview proxy at callback time; investigate Launcher3/TouchInteractionService or native user/setup state rather than adding a second Recents implementation.
- `... connected=true` but no Overview: inspect Quickstep task/gesture state and Launcher3 exceptions. Do not claim the routing works without visible Q25 verification.

Focused log:
```sh
adb -s 202512003630 logcat -d -s 'BB7-PullDown:V' 'OverviewProxyRecentsImpl:V' 'OverviewProxyService:V' '*:S' | tail -100
```

## Proposed replacement architecture

| Layer | Sole owner | Future BBUI role |
| --- | --- | --- |
| Tasks and process state | Android ActivityTaskManager / platform task services | Read the existing task records; do not keep a competing task store |
| Overview lifecycle, launch/dismiss and transitions | Native Launcher3/Quickstep | Reuse existing state transitions and task actions |
| Overview presentation | One active Quickstep-hosted BBOS6 icon-grid renderer | Display real task application icons and labels, selected/focused state, and trackpad-compatible layout |
| BlackBerry key menu | Existing `ShadeUiController` and `PullDownHook` | Forward Switch Application to the same native Overview entry point |
| Vector | Temporary process-specific Xposed adapter | Hook the actual `com.android.launcher3` UI owner without adding a window |
| Future ROM | Direct Quickstep/Launcher3 source integration | Move the renderer and input policy into ROM; remove Xposed adapter |

The presentation model and keyboard/trackpad policy should not import Xposed types. Use a minimal Q25-specific Vector bridge for class/lifecycle adaptation.

## Exclusive BBUI Recents presentation (0.9.101)

Root cause: the screenshot of 0.9.99 shows stock Quickstep card
geometry, thumbnail empty slots and pill-shaped app-chip headers. The 0.9.100
attempt changed native TaskView drawing but still kept Quickstep's layout
and interaction model. That does **not** meet the user's requirement.

**Current architecture** (branch `feat/bbos6-recents-replacement`):

- **One authoritative native host:** Quickstep `RecentsView`, which remains
  responsible for task enumeration, task identity, native launch and task
  lifecycle. No new Activity, overlay window or second task list is created.
- **One exclusive presentation:** intercept `RecentsView.draw(Canvas)`
  and paint BBUI's **4x4 application icon grid** to the host canvas.
  The stock `RecentsView.draw`, `dispatchDraw`, **all native TaskView
  drawing**, thumbnail widgets, header chips, previews, labels, stock
  focus visuals and live-tile drawing are **not invoked**.
- Native TaskView objects are retained as *headless data/action endpoints*
  because Quickstep itself owns their task binding. They do **not** render
  in this mode; this is not two concurrent renderers. A future ROM port
  can remove their UI inflation completely while keeping the task model.
- Remove separate stock Overview action and empty-message sibling views
  from the active window rather than simply setting their alpha to zero.
- BBUI owns *all* grid hit-testing, per-page selection, touch-swipe paging,
  DPAD navigation and blue icon highlight. It does not invoke Quickstep
  carousel snapping, tile layout, gesture recognition or task-card focus.
- Enter/trackpad click/tap invokes the **existing native Quickstep task
  object**, preferably through its non-animated native task launch API.
  Back still follows Android's native Overview exit behavior.
- No app labels or boxes: only the actual native app icon Drawables and
  a compact blue focus indicator on a solid BBUI dark background.
- The flag `debug.bbui.recents.icons=true` remains opt-in until Q25
  confirmation. **Reboot** to return to stock behavior after testing.
  The old `NativeRecentsGridHook` has been deleted.
- CI remains disabled on this branch (workflow is manual-only), all commits
  use `[skip ci]`, and testing uses **local Gradle and ADB only**.

**Validation status:** implementation committed, **not yet confirmed
on-device**. If the original cards are visible again, inspect the hook
registration and Q25 Quickstep class/method signature; do not add another
overlay or preserve stock card layout.

### Device test (single consolidated check)

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/bbos6-recents-replacement
git pull --ff-only
gradle :app:assembleDebug :app:testDebugUnitTest && \
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk && \
adb -s 202512003630 reboot
```

After Android has rebooted and Launcher3/Vector is loaded:

```sh
adb -s 202512003630 shell setprop debug.bbui.recents.icons true
```

BlackBerry button → Switch Application should use the **same** native Quickstep Overview, now presented as an icon grid. Check multiple tasks, arrows/trackpad, Enter, Back, touch launch, Home, and reopen. The first result can indicate that Q25 Quickstep layout or icons differ from the upstream source; do not merge without validation.

If stock Recents appears instead of icons or layout is abnormal, gather *only*:

```sh
adb -s 202512003630 logcat -d -s 'BBUI-RecentsIcons:V' '*:S' | tail -80
```

Rollback: `adb -s 202512003630 reboot` resets the temporary property, returning to stock Quickstep. This reboot also reconstructs any styled native TaskViews; setting the property false without a restart is not a complete view-teardown mechanism.

## Next implementation checkpoint

1. Inspect the actual installed Q25 Quickstep layout and task APIs. Determine where native Recents renders cards, handles focus and opens/dismisses tasks. Confirm whether a true single-renderer replacement is possible from Vector.
2. Build an icon-grid view **inside the existing native Overview host**, replacing the stock presentation, not covering it. Preserve existing Quickstep lifecycle, task actions, accessibility and system gestures. Never retain two renderer trees.
3. Map directional keys/trackpad to grid selection, Enter to native task opening and Back to the native Overview exit. Confirm BBOS6-specific sizing and selection visuals against verified references.
4. Test with real tasks, empty overview, running foreground app, dismissal, rotation, sleep/wake, gesture entry, Home, Back and repeated opening. Check no blank/duplicate surfaces, crashes, stale views or unreachable tasks.
5. After Q25 verification, remove obsolete adaptation code and document the direct LineageOS ROM integration points.

No icon resource identity or exact grid geometry is asserted at this stage; first verify the BBOS6 reference. Any original numbered icon used must be indexed in `assets/bbos6/icon-index.csv` under the repository-wide policy.

## Build and first on-device baseline test

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/bbos6-recents-replacement
git pull --ff-only
gradle :app:assembleDebug :app:testDebugUnitTest && \
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk && \
adb -s 202512003630 shell settings delete global bb_ui_vertical_recents && \
adb -s 202512003630 reboot
```

After boot, press the BlackBerry key, select **Switch Application**, and press Enter. Verify that Quickstep's *stock* Recents appears (no other task switcher or hidden view), selecting a task opens that same Android task, and Back/Home close Overview normally. Also reopen the BB popup to verify its Notifications and Quick Settings actions remain functional. If Overview still does not open, capture `adb -s 202512003630 logcat -d -s 'BB7-PullDown:I' 'BB7-PullDown:E' '*:S' | tail -100` and inspect the native Quickstep service state. The native menu action is only source-verified pending device test. `master` remains unchanged.
