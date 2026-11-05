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
