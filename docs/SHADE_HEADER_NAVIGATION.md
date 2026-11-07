# BlackBerry-button page menu — vertical bottom-left popup

Implementation branch: `feat/bb-bottom-left-vertical-menu`.
Based on `master` commit `4c0d46c8c6947b758935b67ee5676158c7e14710`.

## Interaction contract

The BlackBerry hardware key opens a compact, **transient** two-row popup at
the bottom-left of the display, above navigation-bar insets:

1. **Notifications** (initial focus when no shade is open)
2. **Quick Settings**

The popup uses a 224dp width, two 48dp rows, and BB theme gradient/highlight.
Up and Down choose the corresponding row, with bounded selection. Enter,
trackpad click, or direct row tap opens the selected page. Back or another
BlackBerry-key press dismisses only the popup. Left and Right are not popup
selection commands. The previous **BB -> Down -> native Recents** shortcut
is retired because Down is now used for page selection.

No shade opens merely from displaying the popup. The popup is a separate
SystemUI-owned status-bar subpanel and is destroyed after a successful page
activation. It is never reparented into the shade and no duplicate menu is
allocated on subsequent keypresses.

When the notification/Quick Settings shade is already open, BB reopens the same
popup with the currently displayed page highlighted. Up from the first
notification/media focus target or first QS row also opens the popup.
Selecting either destination switches the retained pages in place, without
rebuilding their View instances or replaying the shade entrance animation.
Back from that popup returns focus to the current shade content, and does not
close the expanded shade.

## Shade ownership

`ShadeUiController` owns selected page and popup focus. `PullDownHook`
intercepts the existing native Recents command and attaches the popup only
when requested. `ShadeDateHeader` retains its historical Java class name,
but is now a **popup-only** vertical menu; `ShadeHeaderSegmentPolicy` tests
vertical Y-coordinate rows.

`UnifiedShadeHost` contains **no permanent navigation header, placeholder,
or selector view**. Its Notifications and QS content starts below the native
status bar. The separate 40dp media mode bar is preserved when media is
expanded. `NativeShadeHostBridge` keeps its native status-band clipping and
touch-drag handling but has no selector-specific clipping or reveal path.

Both BB-confirmed shade openings and native status-bar swipe openings use
the existing native full-window slide. The popup closes on successful mount,
Back, second BB press, screen-off, and shade shutdown. Failure before mount
keeps the original popup input surface available.

## Test on Q25

Build and install from the feature branch:

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/bb-bottom-left-vertical-menu || \
  git switch --track origin/feat/bb-bottom-left-vertical-menu
git pull --ff-only
gradle :app:testDebugUnitTest :app:assembleDebug && \
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk && \
adb -s 202512003630 shell am crash com.android.systemui
```

Acceptance:
- BB opens only the bottom-left two-row popup; Notifications starts highlighted.
- Down highlights Quick Settings; Up returns to Notifications; Enter opens
  once. Direct tap activates the touched row. No horizontal selection remains.
- Back or second BB press dismisses the popup without opening a shade.
- From an open shade, BB reopens the popup. Selecting another page changes it
  in place without a shade blink. Back closes the popup, not the shade.
- Native status-bar swipe-down and finger-drag close remain operational.
- No reserved 49dp header above notification or QS content.
- Expanded media still displays its independent 40dp shuffle/repeat header.
- Screen-off, lockscreen, Home, and fast repeat BB presses leave no stray
  focus windows, input interception, or stock Android QS leakage.
- Run all app and `bb7pulldown` tests if supported by the local build.

Rollback without installing a different APK:

```sh
adb -s 202512003630 shell setprop debug.bb7pulldown.enabled false
adb -s 202512003630 shell am crash com.android.systemui
```

This is feature-branch implementation, not Q25 runtime validation.
