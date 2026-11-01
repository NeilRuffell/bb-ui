# BB UI

## BB7 Shade — independent Vector module (development branch)

The `feat/bb7-shade` branch adds `:bb7shade`, a **third, separate APK**
(`dev.q25.bb7shade`), alongside `:app` and `:bb7focus`.
Version `0.3.4-native-qs-collapse-dev` is the first **visible partial UI**:
it inserts a narrow native BB7 app-icon/count rail into the existing SystemUI
notification container, and constrains Android's original notification stack
to the right of it while the phone is unlocked. It does not add another
window or duplicate native notification rows.
Version 0.3.4 addresses the Q25 report that only the left icon rail and
stock QS appear, with no visible notification rows. When the experimental
rail is visible and native QS is fully expanded, it asks SystemUI's own
`QuickSettingsControllerImpl.closeQs()` to restore the notification
presentation (no manual manipulation of notification card geometry).
It also logs bounded `Shade presentation: ...` diagnostics with native
QS fullscreen state, stack alpha/visibility, and first-row position.
Quick Settings page routing is not yet implemented. This change should
be validated on the device before further UI alterations.

Version 0.3.3 fixes an Android 17 optimization mismatch: the runtime no longer exposes KeyguardStateControllerImpl.isShowing(). The hook now reads the showing boolean directly from notifyKeyguardState() and uses the controller's backing field during shade expansion. Version 0.3.2 began observing SystemUI's native keyguard state and shade expansion lifecycle, so a rail mounted while the device initially reports locked is re-evaluated when the shade opens or the phone unlocks. The rail remains hidden while the lock screen is active.
Version 0.3.1 adds a second attachment path from the verified native
notification stack's layout callback, scheduled after layout. This covers
cases where the container inflation hook misses the view. The module logs
which attachment path actually succeeded and whether the rail is shown.
 **Native rows are deliberately
not filtered yet** when an app is highlighted; per-app presentation filtering
and the separate Quick Settings page remain future work.

Disable the experimental layout immediately via ADB (no reboot):

```sh
adb shell setprop debug.bb7shade.layout false
adb shell am crash com.android.systemui
```

Restore the experimental layout:

```sh
adb shell setprop debug.bb7shade.layout true
adb shell am crash com.android.systemui
```

The app rail is hidden and the original notification stack constraints are
restored on the lock screen. The current layout is an **early test** and will
still show stock Quick Settings. If a device behaves unexpectedly, use the
rollback property above or disable the BB7 Shade SystemUI scope in Vector.

The module also observes
the finalized SystemUI notification render list after filtering/grouping,
preserves the ordered notification references for each application, detects
the native shade window and logs notification stack geometry. It does **not** add another shade window, intercept system input, or alter notification contents. The future replacement is deliberately
gated on both pages being functional, avoiding two competing interfaces.

Build and install only the new module:

```sh
./gradlew :bb7shade:assembleDebug
adb install -r bb7shade/build/outputs/apk/debug/bb7shade-debug.apk
```

Enable **BB7 Shade** for **System UI** (`com.android.systemui`) in Vector.
Restart SystemUI without rebooting:

```sh
adb shell am crash com.android.systemui
adb logcat -d -s BB7-Shade:I '*:S'
```

Expect `BB7 Shade stage 2 loaded`,
`Finalized notification render-list observer registered`, and
`Rendered notification index updated: apps=... entries=...` when Android rebuilds the finalized list. If no rebuild occurs, expect `Native notification rows indexed: rows=... apps=... entries=...` from the live native stack. Both paths are read-only. Native stack scans are throttled to avoid repeated work during layout. Version 0.2.3 first tries the existing `mEntryAdapter` / `mEntry` backing fields of native notification rows, because Q25 reported `getEntryAdapter (NoSuchMethodError)`. The successful reader logs `Native row access path: ...` once. A failing accessor is reported once by stage and error type without notification contents.
`Native stack geometry: ...` identifies the actual notification layout
for later UI placement. Log output contains only counts and geometry, not
notification content. Run `./gradlew :bb7shade:testDebugUnitTest` for
the keyboard navigation state model. This confirms
**data integration**, not the redesigned UI. The original shade stays
fully functional. To roll back, disable just this module's SystemUI scope
and restart SystemUI.

See [BB7 Shade architecture](docs/BB7_SHADE_ARCHITECTURE.md).

---
## BB7 Appearance — Settings and SystemUI native notification focus (experimental)

The `feat/bb7-focus-settings` branch builds a **separate, compatible Vector/LSPosed APK**
named **BB7 Appearance** from the Gradle target `:bb7focus`. The original `:app`
and its behavior hooks are unchanged.

BB7 Appearance observes Android Settings' **existing** focus changes and
draws a BlackBerry OS 7-inspired blue gradient on the currently focused
AndroidX Preference row, making its text readable against the blue.
Focus order, key handling, touch handling, selection, scrolling, and
button behavior are **not intentionally changed**. It does not add focus
to an item that lacks keyboard focus. On focus loss, the row background,
padding and text colours are restored.

**Current scopes:** `com.android.settings` (Settings) and `com.android.systemui`
(SystemUI native notification rows). It does not change Settings Intelligence,
Google Messages or other apps.

### SystemUI: native notification-row focus

The accessibility dump from the Q25 confirms keyboard focus on
`com.android.systemui:id/expandableNotificationRow`. The LineageOS 24
`status_bar_notification_row.xml` layout confirms that ID identifies an
`ExpandableNotificationRow` with its native `NotificationBackgroundView`
child (`duplicateParentState=true`).

The earlier global `ViewTreeObserver` approach missed the focused row.
Version `0.2.2-native-row-focus-dev` instead reads `row.isFocused()` in
the native notification background's normal `onDraw` and
`drawableStateChanged` calls. The same BB7 gradient draws after the
stock notification background and before notification content. Text colours
are restored when a native row loses focus. No focus, key, touch, click, or
scroll properties are changed, and the existing Settings styling is untouched.

Quick Settings tiles are Compose-based and remain outside this phase.
The updated SystemUI hook is **build-tested only**, pending Q25 visual
verification.

For SystemUI testing, enable `com.android.systemui` in **BB7 Appearance** in
Vector, preserving Settings and all existing BB UI scopes, then run:

```sh
adb shell su -c 'killall com.android.systemui'
# After SystemUI restarts, open the notification shade and navigate the
# existing notification rows using the Q25 keyboard/trackpad.
adb logcat -d -s BB7-SystemUI:I '*:S'
```

**Warning:** Restarting SystemUI can temporarily blank the status bar and
notification shade. If the new hook causes a SystemUI crash loop, disable the
SystemUI scope for BB7 Appearance in Vector and restart SystemUI, or use
Vector's module recovery method. No reboot is required for routine tests.

For rollback, uncheck only `com.android.systemui` in BB7 Appearance's scopes
and restart SystemUI; the working Settings highlight is independent.
Non-AndroidX preference lists and custom-rendered views are excluded
until tested. The module needs **device validation on Q25/LineageOS 24.0**.

### Build and test

```sh
git fetch origin
git switch feat/bb7-focus-settings
git pull --ff-only
./gradlew :bb7focus:assembleDebug
adb install -r bb7focus/build/outputs/apk/debug/bb7focus-debug.apk
```

In Vector, enable **BB7 Appearance**, and scope it to **Settings**
(`com.android.settings`) and optionally **SystemUI** (`com.android.systemui`).
Leave the existing **BB UI** module
enabled with its existing scopes.

Then restart the Settings app, **not the whole phone**:

```sh
adb shell am force-stop com.android.settings
adb shell am start -a android.settings.SETTINGS
```

Use the Q25 keyboard/trackpad to move focus across Settings rows. The
current row should become blue; the previous row should immediately
return to its normal background. Verify switches, touch, Enter/Back,
scrolling and the existing type-to-search. If no blue appears, retrieve
diagnostics:

```sh
adb logcat -d -s BB7-Focus:I '*:S'
```

To roll back, disable **BB7 Appearance** in Vector, then force-stop and
restart Settings. The module does not modify persisted Android settings.

**Note:** A successful build does not verify which controls LineageOS
marks focusable. This module styles only detected focus; it does not
alter navigation or force otherwise-unfocusable controls to gain focus.

---

# BB UI

Vector/LSPosed module for physical-keyboard Android improvements.

## Settings type-to-search

**Branch:** `feat/settings-hide-idle-search-bar`, created from `master`.

On the Settings homepage the idle search card is hidden, reclaiming its
height and bottom spacing. The app-bar container keeps the system status-bar
inset so homepage content is not drawn underneath the status bar.

The existing physical-keyboard type-to-search and search-query seeding
are unchanged: type a letter or digit on the Settings homepage to open the
regular Settings search activity with that first character inserted. The
search activity retains its editable search field. The existing Vector scopes
remain `com.android.settings` and `com.android.settings.intelligence`.

## Two-column vertical Recents — native Quickstep adapter (experimental)

**Branch:** `feat/native-vertical-recents`. `main` and the older `dev`
Recents branch are unchanged. This branch is built from the working Settings
baseline, not the older Recents experiments.

**Technical approach (LineageOS 24 Quickstep):**

- Reuse native `com.android.quickstep.views.RecentsView`, `TaskView`,
  thumbnail loading and task lifecycle. There is no replacement Recents app.
- Use Quickstep's native landscape primary axis for vertical scrolling,
  snapping, flings and wheel navigation, while delegating portrait-only
  rendering and thumbnail rotation to the portrait handler.
- Synchronize the orientation adapter across Quickstep's `RecentsView`,
  `RecentsOrientedState`, and `TaskView` so drag detection, dismissal
  translation, and native animations use the **same** axes.
- Use Quickstep's native horizontal task-dismiss controller for **both**
  swipe-left and swipe-right removal. Its own SingleAxisSwipeDetector already
  provides DIRECTION_BOTH. Adjust the native dismissal factor and distance
  for whichever edge the swipe approaches, retaining Quickstep's standard
  task-removal spring and threshold behavior.
- Disable the separate swipe-to-launch controller in the portrait grid.
  **Tap still launches**; neither horizontal swipe should launch an app.
- Use native PagedView's vertical scrolling without custom touch handlers.
  LineageOS RecentsView's original `computeMinScroll()` and
  `computeMaxScroll()` rely on carousel task indices and produced a
  zero native maximum on the Q25 despite the vertical grid needing
  433 pixels of scrolling. Override only those two bounds using the
  existing `getPageScrolls()` grid geometry. The original pager still
  handles scrolling, fling, and snap.
- Use native two-panel grouping and TaskView measurement.
- Enter Overview at its **native page zero**, resetting its previous page
  selection at the start of the state transition or gesture. The two native
  entry callbacks share an idempotent latch so selection executes **once per
  overview entry**, and the latch clears on exit.
- For an Overview transition **from Home**, classify the native
  `GroupedTaskInfo` in `RecentsView.showCurrentTask()` and veto ONLY that
  method for `ACTIVITY_TYPE_HOME`. The native gesture-start state, running
  task metadata, and app entry animations are otherwise unchanged. This
  covers both the existing-running-card and new-placeholder paths.
- The previous `shouldAvoidAddingStubTaskView()` gate was ineffective on
  existing-card paths and has been removed. The subsequent attempt to null
  the `onGestureAnimationStart()` input was also removed because it was
  broader than necessary.
- At native Overview entry, log the source's activity type and running-task
  index. At the exact insertion/reuse boundary log
  `Native HOME running task skipped at showCurrentTask`. If no such line
  occurs when opening from Home, the user-visible motion must be traced
  elsewhere; this build does not mask the motion with any offset.
- Preserve stock `TaskViewSimulator` geometry for
  `ACTIVITY_TYPE_HOME` remote animation targets; only app windows are
  redirected toward their corresponding recent-task card.
- Fix `TaskViewSimulator.calculateTaskSize()` to use the same first-column,
  first-row card geometry as the grid; the app-window surface now has a
  grid destination **before** its opening animation is created.
- Prevent stock non-grid horizontal task translations at the TaskView setter,
  instead of subtracting them inside `layoutNativeTasks()`. TaskView's native
  X/Y translation animations are left intact.
- At `RecentsView.getPageScrolls()`, while Quickstep is already laying out
  children, arrange existing TaskViews in two columns and return matching
  vertical page offsets. No post-layout card offsets or manual scrollY state.
- Keep `RecentsViewModel` thumbnail loading; filter visible task IDs against
  the vertical viewport.
- Use Android/Quickstep's own gesture interception. No custom swipe,
  trackpad-scroll or fling implementations.
- Convert the currently running app's live SurfaceControl into a real
  Quickstep screenshot when Overview settles; only then finish the remote
  Recents animation so the original-size live app cannot remain behind the
  grid while its task card shows no image.

**Regression rollback (0.8.6):** Home-to-Recents placement is restored
byte-for-byte to the source implementation of `0.8.4-home-task-dev`
(commit `f07a346`), which was visually confirmed on the Q25.
The `0.8.5` replacement with two native decision-point hooks reintroduced
the empty first cell / upward grid shift, so it was reverted. Do not
reintroduce that refactor without a device-proven alternative.

This is **still a platform-specific LSPosed compatibility hook**, not an
upstream feature flag. A successful build does **not** prove that every Quickstep
animation, live tile and swipe dismissal behaves correctly. Device testing
on the Q25 is required before merging.

### Vector scopes

Enable BB UI and check **Launcher3** (`com.android.launcher3`) in addition
to the two existing Settings scopes. No other launcher or ROM modification.

### Enable

The feature is off by default. After installing the branch APK:

```sh
adb shell settings put global bb_ui_vertical_recents 1
adb reboot
```

### Restore original Recents

```sh
adb shell settings put global bb_ui_vertical_recents 0
adb reboot
```

### Diagnostics (after opening Recent Apps)

```sh
adb shell settings get global bb_ui_vertical_recents
adb logcat -d -s BB-UI-Recents:W '*:S'
```

Expected sequence: `BB UI entry reached`, `Vertical Recents setting=1`,
`Two-column vertical Quickstep adapter registered`, and
`Native vertical grid geometry: tasks=... rows=... maxScroll=...`.
A gesture over the grid should log
`Native pager touch: deltaX=... deltaY=... intercepted=... maxScroll=...`.
A zero `maxScroll` means all rows fit on screen; scrolling is not possible
until there are more recent tasks. The `Native pager bounds: min=0 max=...` diagnostic must now
match the grid's `maxScroll=...` when the native layout is active.
On entry from Home, look for
`Native overview gesture source: activityType=2` and
`Native HOME running task skipped at showCurrentTask`.
If a Home remote surface is present it logs
`Native HOME target: leaving simulator geometry unchanged`.
The entry animation path logs `Native entry animation target=[...]`
when an individual simulator destination changes, not only once per process. On overview entry the native
page-selection path logs `Native overview initial page=0 scrollY=0`.
If these messages are missing, the relevant paths did not execute.
Check for `Native bidirectional task dismissal registered` and the per-swipe `Native dismiss drag direction=left/right` messages. A bounds mismatch is a bug in the hook; no pager touch event suggests
the parent did not receive the gesture. Android runtime validation
is still required even if Gradle succeeds.

If the first log is absent, check that Vector actually injects the module
into the active `com.android.launcher3` Quickstep process. If registration
fails, the error log identifies the missing class or hook.

### Device acceptance checks

- Two portrait columns, task previews displayed correctly
- Native vertical touch dragging and trackpad wheel navigation without dismissing an app
- Both horizontal swipes dismiss and animate toward their respective edges; neither launches
- Tapping an app launches that exact task
- Swipe-to-dismiss and Clear All work
- Running task preview is not duplicated/overlaid by a live tile
- Recents always opens at the top of the list; repeated entry from a previous bottom scroll works
- Entering from an app shrinks and moves directly into that app's grid card without a carousel detour or pop
- Recents opens and closes reliably; no horizontal snap
- App task order and thumbnail loading update after dismiss
- Rotation, accessibility, keyboard focus and Android back/home still work

### Build

Requires JDK 17, Android SDK Platform 35 and Gradle 8.11.1.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If this checkout lacks `gradlew`, copy the known working Gradle 8.11.1
wrapper from your existing `tv-dv` workspace, as done previously.

GitHub Actions also creates `bb-ui-debug-apk` artifacts on pushes to the
feature branch.
