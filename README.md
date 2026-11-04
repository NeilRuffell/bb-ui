# BBOS6 lock-screen native-blueprint Vector preview

**Branch:** `feat/bb6-lockscreen-banner`  
**APK:** `0.9.43-bb6-vector-blueprint-dev`  
**Status:** Installable unified BB UI Vector APK. Not merged; requires Q25 test.

## Design / source ownership

This remains the existing **root/Vector workflow**. NO ROM checkout,
`repo sync`, system image rebuild, or flashing is required.

The previous 0.9.42 build incorrectly removed our BB6 clock/date while
leaving the stock versions. This version restores the BB6 clock/date, but
**only when our alternate native keyguard blueprint is actually selected**.
If the blueprint adapter cannot attach safely, the feature falls back to
notification indicators only rather than duplicating stock clocks.

`BB6BlueprintHook` uses the **actual Q25 SystemUI** classes and fields
verified in the supplied APK:

- `KeyguardBlueprintRepository.blueprintIdMap` maps blueprint names to
  instances of the native `KeyguardBlueprint` interface (methods
  `getId()` and `getSections()`).
- It registers an alternate `bb6-interactive` blueprint and delegates
  the View lifecycle and constraints to Android's own
  `KeyguardBlueprint` machinery.
- In this interactive blueprint, stock `ClockSection`, `SmartspaceSection`,
  `KeyguardSliceViewSection`, native notification icons, promoted
  notification area and notification placeholder are **not constructed
  or bound**. The original `DefaultKeyguardBlueprint` remains available.
- **Native status-bar/carrier section remains**: one TELUS label and real
  signal/battery/privacy icons. We don't duplicate the carrier label.
- Existing `BB6LockScreenHeader` shows BB6 date/time plus real application
  notification icons and active counts, inside the existing SystemUI root.
- The notification-list subtree is detached only while the interactive
  BB6 keyguard is shown, then reattached for shade and AOD. The native
  credential presentation, keyboard shortcut, and emergency access remain.

## AOD crash prevention

0.9.40 crashed because the AndroidX AOD burn-in layer still referenced
views that were detached. 0.9.43 does not directly detach native
clock/Smartspace View children. The native blueprint transition invokes
the stock section lifecycle; before that lifecycle removes clock/date
views the Vector adapter unregisters their native AOD reference IDs.
It additionally validates AOD Layer references before native
`transform()`/`updatePostLayout()` calls and prunes only IDs provably
missing from the native parent. The AOD anchor and default blueprint remain.

**This is not yet proven on a real Q25.** APK compilation and unit tests
cannot certify native lock-screen/AOD runtime transitions. The feature
remains on this branch, with a direct rollback control.

## Install / recover (KDE neon)

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/bb6-lockscreen-banner
git pull --ff-only
gradle :app:assembleDebug :app:testDebugUnitTest &&
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/bb-ui.apk &&
timeout 45s adb shell pm install -r /data/local/tmp/bb-ui.apk &&
adb shell setprop debug.bb6lockscreen.enabled true &&
adb shell setprop debug.bb6lockscreen.blueprint true &&
adb shell am crash com.android.systemui
```

If the native blueprint is unsupported or malfunctions, turn it off
without affecting the working Space-to-password implementation:

```sh
adb shell setprop debug.bb6lockscreen.blueprint false
adb shell am crash com.android.systemui
```

To disable the entire BB6 lock-screen preview:

```sh
adb shell setprop debug.bb6lockscreen.enabled false
adb shell am crash com.android.systemui
```

Diagnostics:

```sh
adb logcat -d -s 'BB6-Blueprint:V' 'BB6-LockScreen:V' 'AndroidRuntime:E' '*:S' | tail -120
```

**ROM future:** There is a separate native source implementation documented
under `rom/lineage-24.0` on its own branch, but the current test is the
ordinary BB UI Vector APK.


---

# BBOS6 keyguard Space-to-credential entry

**Merged into `master`:** Q25 Space-to-native-credential entry, version 0.9.37.
The physical-device test succeeded before the merge.

## Requirement

When the screen is locked, pressing **Space** must request Android's
normal credential input (PIN, password, or pattern) and never select a
notification or Quick Settings tile. Once that input is open, Space must
behave normally. The module never attempts to verify or bypass credentials.

## Verified LineageOS 24 source paths

- `WindowRootView.dispatchKeyEvent` sends keys to focusable child
  controls **before** `WindowRootViewKeyEventHandler`.
- `SysUIKeyEventHandler.handleAction` is true on **key UP**, which is
  why merely invoking that handler on key DOWN cannot open authentication.
- `KeyguardKeyEventInteractor` treats Space as a confirm key, but handles
  `SHADE_LOCKED` by collapsing the shade rather than opening the
  credential screen.
- Upstream Scene mode invokes `DeviceEntryInteractor.attemptDeviceEntry`,
  while upstream legacy mode invokes `StatusBarKeyguardViewManager`,
  which delegates to `PrimaryBouncerInteractor.show`.
  **The Q25's actual compiled APK changes these APIs** (see below).
- In legacy locked-shade mode, `ShadeController.collapseShade(true)`
  explicitly runs registered post-collapse actions even if the shade is
  already collapsed. The earlier `animateCollapseShadeForced()` approach
  did not guarantee callback execution.

Implementation: intercept a **physical Space DOWN and UP** at the declaring
`WindowRootView.dispatchKeyEvent` method before a focused control can
consume either event. Request native credential entry on the matching **UP**,
just as LineageOS handles confirm keys. This prevents the release from
entering a newly shown password editor. No synthetic input is generated.
Native credential editors and an already visible bouncer retain key input.

## Actual Q25 APK verification

Inspected the user-provided `Q25-SystemUI.apk`, SHA-256
`65003bbe5b00146a0e20d8612660426be05e571cce22d244fdcd656a2b3be8c0`.
The optimized DEX class definitions explicitly declare:

- `classes2.dex`:
  `StatusBarKeyguardViewManager.showPrimaryBouncer(String, boolean)`
- `classes.dex`:
  `PrimaryBouncerInteractor.show(String, boolean)`
- `classes.dex`:
  `DeviceEntryInteractor` has no defined `attemptDeviceEntry` method.

This differs from upstream LineageOS, which places the boolean argument
before the reason string. The Q25 runtime log independently confirmed
that both native bouncer objects are captured but attempts to invoke the
*upstream-order* signatures match no method. The module now tries the
**Q25-confirmed `(reason, true)` signatures first**, with upstream-order
signatures retained for other ROMs.

The keyguard manager is tried first so it can perform the normal native
keyguard checks and transitions. The primary-bouncer interactor is the
fallback. The reflection matcher is covered by unit tests using method
signatures extracted from this APK. No custom credential input or
verification is involved.

A successful compile confirms code validity, not actual Q25 lock-screen
behavior. Do not merge until tested on the phone.

## Build/install on KDE neon

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/bb6-keyguard-keyboard-entry
git pull --ff-only
gradle :app:assembleDebug :app:testDebugUnitTest &&
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/bb-ui.apk &&
timeout 45s adb shell pm install -r /data/local/tmp/bb-ui.apk &&
adb shell am crash com.android.systemui
```

**Vector:** Keep the unified BB UI module enabled with SystemUI scope;
keep deprecated separate BB7 modules disabled.

## Verification / troubleshooting

Lock the Q25, press Space, and confirm the native PIN/password entry
appears. Test when the highlighted control is a notification or a
Quick Settings tile. Test Space while entering a password, then unlocked.

If there is no response, capture **only** the concise integration logs:

```sh
adb logcat -d -s 'BB6-Keyguard:I' '*:S' | tail -50
```

Key messages: `Space UP`, `Native route scene=`, and
`Q25 native primary credential entry invoked via manager` (or its
bouncer fallback). If no input appears, capture the error lines.
An invocation log is **not** proof that the interface appeared.

To disable this feature without disabling other BB UI work:

```sh
adb shell setprop debug.bb7keyguard.enabled false
adb shell am crash com.android.systemui
```

To re-enable, set `debug.bb7keyguard.enabled true` and restart SystemUI.

---

## BB7 shade follows Android Light/Dark appearance

The BB7 two-column notification panel now resolves Android's standard
`Configuration.UI_MODE_NIGHT_MASK` when a new panel is created. It uses
shared theme palettes for the shade background, app-icon column, notification
column, header, cards, native action rows, text, dividers and inline reply.
Focused notifications/actions/app icons retain the same BBOS7 blue gradient;
selected card and action text switches to white for contrast.

There is no custom preference, timer, observer or appearance service. To test
a theme change, switch Android Light/Dark mode and **reopen the shade**.
The current panel instance does not live-update while already displayed.
This modifies styling only; notification handling and keyboard navigation
are unchanged.

```sh
cd ~/Projects/bb-ui
git pull --ff-only
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am crash com.android.systemui
```

---

## Unified BBOS7 appearance — shared visual tokens

**Branch:** `feat/unified-bb7-visual-theme`, based on merged `master`.
The single `BB UI` Vector APK compiles `BB7Theme` in the common app
source root. Settings focused rows, native SystemUI notification focused
rows, BB7 shade cards/actions, and the status-bar focus outline now
reference the same theme constants (blue gradient, focus border, primary
and secondary text, graphite pane surfaces).

This change is visual only. Existing trackpad focus logic, physical key
handling, notification actions, SystemUI shade ownership, and Quickstep
task layout/navigation are unchanged. The native Quickstep task cards
retain their existing platform styling at this stage; their focus rendering
is intentionally not replaced until a device-proven hook is identified.
This avoids introducing a second focus/navigation pipeline.

Only the unified `:app` APK is built. Retain the existing single
`BB UI` module and its four scopes in Vector. The retired BB7 Appearance
and BB7 Pull-down APKs must remain disabled to avoid duplicate hooks.

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/unified-bb7-visual-theme
git pull --ff-only
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.android.settings
adb shell am force-stop com.android.launcher3
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell am crash com.android.systemui
```

Validate that only one Settings row highlights at once, native
notification focus still clears when moving away, and BB7 notification
cards/actions and status zones share a consistent blue tone.
Check the native 2×2 Recents grid and BlackBerry key behavior for regressions.
Do not merge until device testing passes.

---

# One Vector module: BB UI (migration candidate)

**Branch:** `feat/unified-vector-module`, derived from the device-tested
`feat/bb7-unified-overview-entry`. Not merged.

This changes the APK packaging, **not** the working functionality:
`settings.gradle` builds only `:app`. The BB UI app Gradle source set compiles
the original `bb7focus` and `bb7pulldown` Java classes alongside Settings
type-to-search and Quickstep Recents. Those directories are retained as
source roots; they no longer produce separate APKs from the root Gradle build.
The five original Xposed entry classes retain their class/package names and
process guards.

| Vector package (new single APK) | Scope | Function |
| --- | --- | --- |
| `dev.q25.trackpad` | `com.android.settings` | Settings type-to-search and focused-row appearance |
| `dev.q25.trackpad` | `com.android.settings.intelligence` | Settings search integration |
| `dev.q25.trackpad` | `com.android.launcher3` | Native 2-column Recents |
| `dev.q25.trackpad` | `com.android.systemui` | Native notification focus, BB7 shade, status-bar focus and BlackBerry-key handling |

**Critical migration:** In Vector, add SystemUI scope to **BB UI** and
ensure the other three scopes listed above are selected. After that, disable
the old **BB7 Appearance** and **BB7 Pull-down** Vector modules. Do not leave
their scopes active concurrently with the consolidated APK: that would load
duplicate hooks into Settings/SystemUI. Do not remove the old APKs until the
new one passes device tests. This migration does **not** require framework
`system` or `android` scope for BB UI.

Build and install the **one APK**:

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/unified-vector-module
git pull --ff-only
./gradlew clean :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

After completing the Vector scope/enablement migration, restart the affected
processes **without root or reboot**:

```sh
adb shell settings put global bb_ui_vertical_recents 1
adb shell setprop debug.bb7pulldown.enabled true
adb shell am force-stop com.android.settings
adb shell am force-stop com.android.settings.intelligence
adb shell am force-stop com.android.launcher3
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell am crash com.android.systemui
```

Test: Settings search and focus highlighting; BlackBerry key leaves the active
app on screen and focuses status bar; Left/Right switches zones; Enter
opens Notifications or system controls; Down reveals native Recents; Back
dismisses focus; 2×2 Recents and native notification actions remain functional.

If this build fails or regresses, disable consolidated BB UI's new
`com.android.systemui` scope, re-enable the former two modules with their
original scopes, and reinstall the last verified app APK if necessary.
**Compilation and Q25 device testing are pending.**

---

## Unified BlackBerry-key status navigation (experimental)

**Branch:** `feat/bb7-unified-overview-entry`.

The BlackBerry key is still mapped by Android to its **Recents command**, but
the BB7 Pull-down module intercepts **SystemUI's CommandQueue.toggleRecentApps()**
before it forwards the request to Quickstep. SystemUI displays only a
focusable status-bar-height navigation window. The foreground app remains
visible underneath: no Overview transition is started, no Recents activity
is opened, and no task grid is drawn or concealed.

- Left/Right moves between the Notifications and System Controls zones.
- Click/Enter opens the chosen destination without entering Overview.
- Back closes status-bar focus, returning input to the foreground application.
- Down dismisses status-bar focus and explicitly forwards one native Recents
  command, revealing the unchanged existing 2-column Quickstep grid.

The prior experiment that entered Quickstep and made RecentsView transparent
has been **removed**. The app module's existing Recents implementation has
been restored unchanged. No framework/system_server hook is used.

This catches the SystemUI `toggleRecentApps` route. Device validation must
confirm that the Q25 BlackBerry key uses that route on this build.
If the status bar view is unavailable or the module is disabled, the
original Android Recents command is left intact.

Build/install both modules (the app APK removes the previous concealment code):

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/bb7-unified-overview-entry
git pull --ff-only
./gradlew :app:assembleDebug :bb7pulldown:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r bb7pulldown/build/outputs/apk/debug/bb7pulldown-debug.apk
adb shell settings put global bb_ui_vertical_recents 1
adb shell setprop debug.bb7pulldown.enabled true
adb shell am force-stop com.android.launcher3
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell am crash com.android.systemui
```

Diagnostics: `adb logcat -d -s BB7-PullDown:I BB-UI-Recents:W '*:S'`.
This is unverified on the Q25; do not merge until foreground-app preservation,
status navigation, Back, Down, and notification activation are confirmed.

---
# BB UI

## BB7 Pull-down — inline native notification actions (v0.6.0)

**Branch:** `feat/bb7-inline-notification-actions` (from `master`).

Notification activation uses the native SystemUI notification row click listener
if one exists. Otherwise, it invokes SystemUI's existing `ActivityStarter` with
the notification's real `PendingIntent`, and reports the click with SystemUI's
`NotificationClickNotifier.onNotificationClick()`. Visibility data is created
using Android's own `com.android.internal.statusbar.NotificationVisibility.obtain()`,
with the same no-native-entry representation used by LineageOS's
`NotificationVisibilityProviderImpl`. This removes the previous unnecessary
dependency on a captured visibility-provider instance.

LineageOS `NotificationManagerService.onNotificationClick()` performs stock
auto-cancellation after a notification is opened; persistent, foreground
and other non-eligible notifications remain. The module adds no custom
automatic dismissal, `FutureDismissal`, synthetic notification or bubble
handling. A separate, explicitly selected **Dismiss** control calls Android's
native notification listener cancellation API only for clearable notifications.
If SystemUI click reporting services are unavailable, this is logged rather
than replaced with a custom clearing algorithm.

Android 17 requires the `RECEIVER_NOT_EXPORTED` flag when registering
the Home/system-dialog receiver. If that mixed-action registration is
rejected, the module falls back to a screen-off-only receiver so the
shade still opens and its lock-screen privacy guard remains active.

Home uses Android's ACTION_CLOSE_SYSTEM_DIALOGS broadcast, already handled by
LineageOS CentralSurfacesImpl for the stock shade. This module observes the
same event and removes its own overlay. Screen-off dismissal is retained.

A separate Vector/LSPosed module, `:bb7pulldown` (`dev.q25.bb7pulldown`),
intercepts a finger swipe starting in the native status-bar region and presents
an **independent, full-screen-width window with two columns**:

- Left column: exactly 15% of available window width; one vertically
  scrollable **application icon per app with a current shade notification**.
- Right column: exactly 85% of available window width, showing the selected
  app's notification titles, preview text and posting times.

Swipe down from the top status bar to open; swipe up inside the panel or use
Back to dismiss. The left icon list comes from SystemUI's own registered
**notification listener**, using an initial active-notifications snapshot and
live posted/removed callbacks. An additional refresh occurs when opening
the panel. Duplicate notifications from the same package show one icon,
in first-seen order. This enumerates active notifications rather than the
shade's filtered/rendered list, so some system/silent entries may appear.
The icon column is now selectable by touch, trackpad and keyboard:
Up/Down moves the selection, trackpad click/Enter selects, and the currently
selected row has a blue BB7-inspired gradient. The first icon is focused on
opening. Selection persists across live notification updates when its app
remains active. The right 85% panel now displays a vertically scrollable,
summary of that app's active notifications; live updates and removals
refresh it. This filters our presentation, not Android's notification list.
Navigation has two explicit levels: Up/Down selects application icons;
trackpad click/Enter enters that app's notifications. Up/Down then selects
individual notification cards, with a blue highlight. Trackpad click/Enter
delegates to the existing native SystemUI notification-row click handler
when a native row is available. When no stock row has been inflated, it
delegates the notification's original opening PendingIntent to LineageOS's
existing SystemUI ActivityStarter (which handles Android background launches).
With no native row, the fallback uses ActivityStarter even if
the native NotifCollection entry cannot be retrieved. If the real native
click services are captured, they handle click reporting and FLAG_AUTO_CANCEL.
Uncaptured native services are logged explicitly; no direct cancellation method
is used, and ongoing notifications remain untouched. Back returns to the same app icon, then Back again
closes the pull-down. Touch can tap apps and notification cards directly.
Left/Right never changes navigation levels. Notifications without a
native clickable row and no original opening PendingIntent remain selectable,
but activating them leaves the pull-down open and logs the reason. Native action buttons and Dismiss are integrated into the normal Up/Down focus
sequence: notification card → app-supplied actions → Dismiss (if clearable) →
next notification card. Enter/trackpad click activates the focused item.
There is no long-press menu. Actions come only from Android's real
`Notification.Action` array and dispatch the original `PendingIntent`.
Non-activity actions use Android's native `PendingIntent.send` with the same
background-activity-start options as LineageOS; activity actions delegate
to native `ActivityStarter`. Action click reporting uses the existing
SystemUI `NotificationClickNotifier`.

Reply actions with free-form `RemoteInput` show an inline hardware-keyboard
text field; Enter sends via Android's `RemoteInput.addResultsToIntent` and the
original action PendingIntent, while Back returns to the action row.
Data-only attachment replies and custom notification layouts remain unsupported.
Dismiss appears only when `StatusBarNotification.isClearable()` is true and
calls the native SystemUI `NotificationListenerService.cancelNotification(key)`;
notification removal is owned by Android, not by this module. The panel closes on screen-off to avoid exposing previews
on the lock screen.

**Initial gesture limitation:** This prototype consumes finger touches that
begin on the visible status bar while enabled. Fullscreen immersive apps
that hide the status bar may not expose that gesture source. It does not
intercept other SystemUI routes that open the stock shade. This stage
adds inline Android notification actions to the validated 15/85 layout.

### Build and enable

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/bb7-inline-notification-actions
git pull --ff-only
./gradlew :bb7pulldown:assembleDebug
adb install -r bb7pulldown/build/outputs/apk/debug/bb7pulldown-debug.apk
adb shell setprop debug.bb7pulldown.enabled true
adb shell setprop debug.bb7shade.layout false
adb shell am crash com.android.systemui
```

In Vector, enable **BB7 Pull-down** with **SystemUI** scope, and disable
the abandoned **BB7 Shade** module to avoid competing modifications.

Logs:

```sh
adb logcat -d -s BB7-PullDown:I '*:S'
```

Notification card activation continues to use the validated native SystemUI row
or ActivityStarter path and the native NotificationClickNotifier. Individual
notification action buttons use Android's documented action PendingIntents. If that launcher
is unavailable or the notification has no opening action, the panel stays
open and logs why. If you don't see `Native SystemUI ActivityStarter captured via constructor`\nor `captured via notification constructor` in logcat after SystemUI restarts,\nthe launcher was not captured. An ActivityStarter callback reports attempted dispatch,
not proof of app foregrounding.

Rollback without a full phone reboot:

```sh
adb shell setprop debug.bb7pulldown.enabled false
adb shell am crash com.android.systemui
```

**Security:** The prototype does not open over the locked keyguard; the
system remains authoritative for notification privacy. SystemUI can
reject the independent window type; failure is logged and not retried
indefinitely. Physical Q25 validation is required.

---

# BB UI

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
