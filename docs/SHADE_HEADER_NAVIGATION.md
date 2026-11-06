# Shared BB UI navigation header

The former 49dp BBOS6 date bar is now a two-segment navigation header
above Notifications and Quick Settings. The left half is labeled
**Notification** and the right half **Quick Settings**. Labels are
centered, and the gradient and header height remain unchanged.

## New keyboard behavior

The BlackBerry hardware key invokes SystemUI's normal
`CommandQueue.toggleRecentApps` entry point. BB UI intercepts that
command to reveal only a **49dp Notification / Quick Settings selector**.
The real notification shade is **not** expanded until the selected page
is confirmed. The selector uses the same `ShadeDateHeader` view displayed
inside the full shade and the same `ShadeUiController` keyboard state. It
is positioned directly below the native status bar in a compact
SystemUI-owned `TYPE_STATUS_BAR_SUB_PANEL` input window.

On first BB press, **Notification** is focused by default. Left/Right
moves the blue focus to either of the two labeled header segments;
Enter/trackpad click opens only the selected shade page. Back (or a second
BB press) closes the selector without opening a shade. Tapping either
segment opens its corresponding page. The selector also closes on
screen-off, with its receiver unregistered after dismissal.

When the shade is already open, pressing BB focuses that shade's
existing navigation header; it does not create a second selector.

## Fixed-header transition on selection

When Enter/trackpad click or a deliberate tap chooses a page, BB UI does
**not** create another navigation header. The `ShadeDateHeader` View from
the compact selector is detached from its input window and mounted as the
header of `UnifiedShadeHost`. The now-empty selector window is removed
only after the new shade is attached. Header size, screen position,
labels and selected highlight stay consistent through the transition.

The normal touch pull-down continues animating the full native shade
window. For **selector-confirmed** opening, that full-window translation
is skipped: the native `NotificationShadeWindowView` stays stationary
and its **bottom clip edge** animates downward beginning immediately
below the existing header. Clipping the entire native window (rather
than only the BB foreground child) also prevents stock QS and notification
views from flashing through while the BB page is being revealed. The
native status-bar upper clipping boundary remains unchanged.

After the reveal, the window is registered as fully open with the existing
native slide controller so drag-to-close and normal close animations remain
available. The legacy non-native shade path reveals its content viewport
beneath the same transferred header instead. Any interrupted reveal
restores the prior native clipping during cleanup. If native attachment
fails before the compact selector is removed, the original header is
returned to that selector instead of leaving a blank bar.

- **Notification** occupies the **left half** of the header.
- **Quick Settings** occupies the **right half**.
- On the compact selector, Down retains the existing native Recents
  shortcut. The selected shade remains closed until confirmed.
- **Up** from the first notification icon, media row or first QS row enters
  the corresponding header selection.
- **Left / Right** changes the selected header half.
- **Enter / trackpad click** switches pages inside the existing shade.
- **Down** returns to the active page's contents.
- **Back** from the selected header closes the whole shade.
- Tapping either half switches to that page. Upward swipe on the header
  continues to dismiss the shade.

When media controls are expanded, the shorter media header remains active
unless header navigation is selected, temporarily showing the shared
navigation header. The normal media header returns after exiting header
focus. The controls remain on their existing page, not a separate window.

The date at the top of the shade is removed. Per-day date separators
between notification cards are unrelated and remain unchanged.

## SystemUI integration

The navigation still uses one `ShadeUiController` and its
`ShadeStatusNavigation` state machine. The existing native status bar
is no longer painted with two blue zone highlights. The compact selector
never mounts native shade content: only a deliberate selection invokes
the existing `showShade(context, page)` lifecycle. Its real notification
icons, clock, QS status, battery, privacy indicators and blur/backdrop
ownership remain untouched. The native notification shade window
and its animation/drag-to-open paths are unchanged.

`ShadeDateHeader` retains the historical class/file name for internal
compatibility. It no longer extends `TextClock`; it now contains two
equal-width, labeled focus regions. `UnifiedShadeHost` owns its
presentation and swipe/tap routing. `ShadeHeaderSegmentPolicy` is a
pure, unit-tested touch hit-test.

## Q25 build and acceptance

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/shade-header-segments-bb-open || \
  git switch --track origin/feat/shade-header-segments-bb-open
git pull --ff-only
gradle :app:testDebugUnitTest :app:assembleDebug &&
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk &&
adb -s 202512003630 shell am crash com.android.systemui
```

Verify: press BB once and confirm that **only the labeled header**
appears directly below the native status bar with Notification
highlighted; no notification or QS content should appear yet. Move
Left/Right and press the trackpad: the same header must remain stationary,
at the same size and screen position, while the selected page slides
DOWN from its lower edge. There must be no second/duplicated header and
no stock Android QS controls visible behind the reveal. Press BB twice
to open then dismiss the selector; repeat with Back, touch selection,
and screen off/on. Check that invoking BB with the shade already open
focuses the existing header. Once in Notifications/QS, Up, Left/Right,
Enter, Down, and Back retain their previous navigation semantics.
Check swipe-up dismissal in the expanded shade, media-header switching,
lockscreen exclusion, and that the existing intermittent keyguard
touch problem does not reappear.

The existing emergency rollback remains
`debug.bb7pulldown.enabled=false` plus SystemUI restart.
This change is not merged into master until Q25 hardware validation.
