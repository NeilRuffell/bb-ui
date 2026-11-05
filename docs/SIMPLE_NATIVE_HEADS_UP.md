# Simple native heads-up notifications — Q25 Vector test

Branch: `feat/q25-simple-heads-up-notifications`. Do not merge until physical Q25 testing.

## Goal

Only native popup heads-up notifications are simplified:

- Tap the popup to open the notification using Android's original click listener.
- Swipe to dismiss using the original native gesture (only when Android considers it dismissible).
- No reply, smart-reply, action, bubble, snooze, expand or close buttons inside the popup.
- Pinned popup long-press settings/guts are suppressed.

**BB UI's separate notification shade is completely unchanged.** Its reply,
dismiss, application actions, and notification management continue to use
`SystemUiNotificationObserver`, `NativeNotificationActions`, and
`NativeNotificationClicks`. Native expanded/contracted/shelf notification
views and notification data/actions are also preserved.

## Q25 ROM baseline

Official LineageOS 24.0-20261025-NIGHTLY-Q25.
Uploaded SystemUI APK SHA-256:
`65003bbe5b00146a0e20d8612660426be05e571cce22d244fdcd656a2b3be8c0`.

Actual compiled `classes2.dex` declares:

- `NotificationContentView.setHeadsUpChild(View)`
- `NotificationContentView.applySystemActions(View)`
- `NotificationContentView.applyExternalSmartReplyState(View, InflatedSmartReplyState)`
- `NotificationContentView.updateExpandButtonsDuringLayout(boolean, boolean)`
- `NotificationContentView.mHeadsUpChild`
- `NotificationHeaderViewWrapper.updateExpandability(...)`
- `ExpandableNotificationRow.isPinned()`, `doLongClickCallback(...)`, `onKeyLongPress(...)`

Upstream Lineage source includes additional methods which R8 removed from this
ROM; this hook validates and uses only the surviving compiled signatures.

## Implementation

`NativeSimpleHeadsUp` is registered independently in `PullDownHook` for
`com.android.systemui`. It hooks presentation of the *native heads-up
child only*, not notification posting, ranking, `Notification.Action`,
RemoteInput, pending intents, or touch/swipe gesture dispatch.

Using framework resource IDs, it hides only action/expander widgets inside
the heads-up view, leaving the native content, title, icon and parent click
listener intact. When SystemUI rebinds actions, replies or expander click
listeners the popup-only sanitization is reapplied. The expanded and
contracted notification views are not mutated.

The module does not invent clicks or force-dismiss ongoing notifications.
If the Q25 framework layout differs from the inspected template, an
explicit diagnostic warning is emitted.

## Build and install

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch --track origin/feat/q25-simple-heads-up-notifications
gradle :app:assembleDebug :app:testDebugUnitTest &&
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk &&
adb -s 202512003630 shell setprop debug.bb7pulldown.headsup_simple.enabled true &&
adb -s 202512003630 shell am crash com.android.systemui
```

Ensure BB UI remains scoped to System UI in Vector. No reboot, ROM rebuild,
SystemUI APK modification or flashing.

```sh
adb -s 202512003630 logcat -d -s 'BB7-HeadsUp:V' 'AndroidRuntime:E' '*:S' | tail -100
```

Expected: `Native tap/open and swipe/dismiss heads-up simplification registered`,
then `First native heads-up simplified...` when a heads-up appears.

## Independent rollback

```sh
adb -s 202512003630 shell setprop debug.bb7pulldown.headsup_simple.enabled false
adb -s 202512003630 shell am crash com.android.systemui
```

Existing BB shade and native full-colour status-bar icons remain enabled.

## Device verification before merge

1. Popup from messaging app: action and suggested-reply buttons absent; title, content and icon retained.
2. Tap anywhere on popup, including header: opens associated notification's app.
3. Swipe left/right: Android dismisses normally for dismissible notifications.
4. Popup long-press does not open notification settings; shade long-press still works.
5. Notification shade still shows original notification and all reply/action controls.
6. Notification updates and successive popups do not restore removed controls.
7. Locked BB6 screen, AOD, media notifications, app-provided custom RemoteViews and multiple notification groups retain native stability.
8. Switch property false and restart SystemUI: stock popup controls return.


## Square popup corners (version 0.9.73)

The `NativeSquareHeadsUpCorners` Vector hook removes the corner radius of
**pinned native heads-up popups only**. It is independent of reply/action
simplification. Ordinary notification shade rows and BB UI's custom shade
are not altered.

The Q25 compiled APK exposes:

- `ActivatableNotificationView.getClipPath(boolean): Path`, used by the
  native heads-up outline/clip
- `NotificationBackgroundView.setRadius(float, float)`, controlling
  its background/blur drawable corners
- `ExpandableNotificationRow.isPinned()`, identifying the popup
- `ExpandableNotificationRow.applyRoundnessAndInvalidate()`, for
  updating the background when SystemUI recalculates its roundness

When a row is pinned, the hook retains the bounds of the native clip path
but replaces rounded edges with a rectangle, and sets the popup
background's top/bottom radii to zero. Non-pinned rows run stock code.
No notification action, gesture, dismissal or shade implementation changes.

### Independent rollback (stock rounded popup corners)

```sh
adb -s 202512003630 shell setprop debug.bb7pulldown.headsup_square.enabled false
adb -s 202512003630 shell am crash com.android.systemui
```

This property defaults to true. The native popup simplification and the BB
shade remain unaffected.

### Test

Install the APK as described above from this branch, then confirm:
1. Pinned popup has four square corners.
2. Popup tap/swipe interactions and notification updates work normally.
3. BB notification shade remains unchanged.
4. After unpin/dismiss, normal notification-row roundness is restored.

Diagnostic lines use `BB7-HeadsUp`, including
`Native popup-only square-corner hooks registered` and
`Applied square corners to first pinned heads-up popup`.


## Global notification side insets (version 0.9.74)

**Design decision:** BB UI does not preserve Android's default notification
side gutters. Remove the shared native SystemUI padding at the measurement
source, including both heads-up popups and the stock notification stack.

Q25's `SystemUI.apk` (`classes2.dex`) exposes
`NotificationStackScrollLayout.initView()`,
`NotificationStackScrollLayout.onMeasure(int,int)`,
`mMinimumPaddings`, `mSidePaddings`, and
`mFullWidthNotificationsInLandscape`. The Q25's R8 build has no separately
hookable `updateSidePadding()` method.

`NativeFullWidthNotifications` sets the resource-derived
`mMinimumPaddings` to zero after native initialization and before each native
stack measurement. It also sets the native full-width-landscape flag to prevent
landscape QS-column alignment from adding additional gutters. SystemUI then
measures the notification children across the entire stack width and centers
them using its existing layout code. **No artificial offsets, negative margins,
row scaling or manual child-width changes.**

This is intentionally global to the native SystemUI notification stack, NOT
limited to pinned heads-up popups. BB UI's distinct notification shade
(`TwoColumnPanel`) has its own layout and actions and is not modified.

### Build/install

On `feat/q25-simple-heads-up-notifications`:

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/q25-simple-heads-up-notifications
git pull --ff-only
gradle :app:assembleDebug :app:testDebugUnitTest &&
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk &&
adb -s 202512003630 shell setprop debug.bb7pulldown.notification_full_width.enabled true &&
adb -s 202512003630 shell am crash com.android.systemui
```

Log checks:

```sh
adb -s 202512003630 logcat -d -s 'BB7-NotifWidth:V' '*:S' | tail -60
```

Expected: `Global native notification side inset removal registered` and
`Native notification stack measured with zero side inset`.
Check the popup's **actual screen edges** visually; the zero-side-padding log
only verifies the native stack, not other possible outer-window insets.

### Independent rollback

```sh
adb -s 202512003630 shell setprop debug.bb7pulldown.notification_full_width.enabled false
adb -s 202512003630 shell am crash com.android.systemui
```

This restores the stock spacing while keeping BB UI's existing popup
simplification and square-corner hooks active.

### Device test

Confirm that heads-up popups reach both screen edges, portrait and landscape
if supported, and that notification taps, swipes, grouping and BB UI shade
remain functional. Verify that no extra outside margin persists; if it does,
inspect its actual parent window layout rather than compensating with offsets.
