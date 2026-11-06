# Shared BB UI navigation header

The former 49dp BBOS6 date bar is now a blank, two-segment navigation
header above Notifications and Quick Settings. Its gradient and height
remain unchanged; visible labels will be added separately.

## New keyboard behavior

The BlackBerry hardware key invokes SystemUI's normal
`CommandQueue.toggleRecentApps` entry point. BB UI now intercepts that
command to open the existing notification shade directly, rather than
creating a separate keyboard focus window over the native status bar.

- Notifications occupies the **left half** of the header.
- Quick Settings occupies the **right half**.
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
is no longer painted with two blue zone highlights. Its real notification
icons, clock, QS status, battery, privacy indicators and blur/backdrop
ownership remain untouched. The native notification shade window
and its animation/drag-to-open paths are unchanged.

`ShadeDateHeader` retains the historical class/file name for internal
compatibility. It no longer extends `TextClock`; it now contains two
equal-width, unlabeled focus regions. `UnifiedShadeHost` owns its
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

Verify: BlackBerry key opens the real shade directly; the header has no
date, text labels or native status bar blue halves; from top of
Notifications/QS, Up, Left/Right, Enter, Down and Back retain their
navigation semantics; touch either half changes page; swipe up still
closes; media expansion correctly swaps header; the lock screen and
ordinary native status-bar icons remain unaffected. Confirm the
intermittent keyguard touch issue does not reappear.

The existing emergency rollback remains
`debug.bb7pulldown.enabled=false` plus SystemUI restart.
This change is not merged into master until Q25 hardware validation.
