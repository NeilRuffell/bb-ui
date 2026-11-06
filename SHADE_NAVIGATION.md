> **Current architecture:** This file preserves the original trackpad
> navigation design, but its branch/version and window-adapter sections
> describe an earlier stage. The native SystemUI shade is now available
> in `master` behind `debug.bb7pulldown.native_shade.enabled`.
> For the authoritative current ownership model see
> [Design philosophy](docs/DESIGN_PHILOSOPHY.md) and
> [Native shade migration](docs/NATIVE_SHADE_MIGRATION.md).
> For the current blank navigation-header and BlackBerry-key behavior, see
> [Shared shade navigation header](docs/SHADE_HEADER_NAVIGATION.md).
> Current unlocked QS presentation uses `BB6NativeQsPanel`, not
> `BB6ConnectionsPanel`.

# BB UI — unified status-bar and shade navigation

Development branch: `refactor/systemui-shade-focus-controller`
Expected test APK: `0.9.30-shared-systemui-focus-dev` (versionCode 60).

## Ownership (this refactor)

`ShadeUiController` is instantiated **once** for the SystemUI process.
It owns the active shade page (or no shade) and the currently selected native
status-bar zone. It delegates keyboard semantics to one
`ShadeStatusNavigation` instance. Neither page nor the standalone
status-bar input surface constructs another navigation controller.

`PullDownHook` provides the current **LSPosed integration adapter**:
it connects the shared controller's requests to Android's status-bar
hooks, WindowManager window lifecycle, the native Recents command,
notification services, and native connectivity actions.

`UnifiedShadeHost` is the retained, single-window view owner. It hosts
one `ShadeDateHeader` and two child content pages:
`TwoColumnPanel` (Notifications) and `BB6ConnectionsPanel` (QS).
Those Android View classes and the navigation controller contain **no
LSPosed API references**. Page transitions change visibility; they do not
create a new SystemUI overlay or repeat the entrance animation.

There is no separate `shown`/`quickShown` state. The integration
derives the active page from the single controller plus the attached
unified window.

## Hardware navigation contract

| Location | Back / Escape | Down | Left / Right | Enter / trackpad click |
|---|---|---|---|---|
| Standalone status bar | Release status-bar focus | Native Recents | Select bar zone | Open selected shade page |
| Status bar with shade open | Close **entire** shade and release focus | Return to active page content | Select bar zone | Select the page, instantly |
| Notification app-icon column | Existing Back behavior: close shade | Next icon | Bounded within column | Enter selected app's notifications |
| Notification card/action pane | Existing Back behavior: return to app icons | Next target | Bounded within pane | Native notification/action |
| Quick Settings content | Close shade | Next connection row | Bounded within row | Native connectivity control |

From the first notification icon (or the media row if present), Up moves to
the native status bar's Notifications zone. From the first QS radio row,
Up moves to the Quick Settings zone.

The status-bar navigation controller has **separate `onBack()` and
`onDown()` events**. This deliberately prevents the old discrepancy
where every Back was treated as a return to shade content.

The shared header remains gray while native status bar focus is active
and blue while content has focus. The original date, media, notification
actions/replies, scroll behavior, one-window animations, and current
Android connectivity controls remain in place.

## Future native ROM integration

The **reusable** boundary is the shared `ShadeUiController`,
`ShadeStatusNavigation`, `UnifiedShadeHost`, both content pages,
their theme/layout classes, and associated policy classes.

The **current platform adapter** is `PullDownHook`, which uses LSPosed
to intercept status-bar gestures, acquire its focus input surface,
provide a SystemUI overlay window, and invoke native services.

For a later ROM integration, replace this adapter with a native
LineageOS SystemUI integration that owns the status-bar keyboard focus
and directly attaches the BB UI shade. Native service hookups must be
implemented and tested against that ROM's SystemUI version and build.
This branch **does not** replace the stock status-bar implementation or
ship a standalone SystemUI APK. It makes the existing view/controller
layer suitable for reuse when that work begins.

## Build and install

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch refactor/systemui-shade-focus-controller || \
  git switch --track origin/refactor/systemui-shade-focus-controller
git pull --ff-only
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb -s 202512003630 install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s 202512003630 shell am crash com.android.systemui
```

## Device acceptance checks

1. Open **standalone status-bar focus** via the existing keyboard shortcut;
   Back releases focus and never opens a shade.
2. Focus status bar, Enter Notifications, Up to status bar, **Back**:
   the complete shade closes and status-bar focus is released.
3. Repeat 2 with QS selected. Back must **not** focus Mobile Network.
4. Enter either shade; Up to status bar; Down restores the existing page's
   content without closing the shade.
5. In standalone status-bar focus, Down still opens native Recents.
6. Notifications -> status bar -> QS -> status bar -> Notifications:
   selection/highlighting is correct, with **no re-animation**.
7. Verify gray/blue date-header focus indication, media row and
   notification-card Back hierarchy, Enter, keyboard Backspace/Delete,
   native reply/actions, and native radio controls.
8. Close via Home, screen-off, Back, or swipe-up. No lingering shade
   overlays, status-bar highlights, focus windows, or receivers.
9. Confirm data refresh, Favorites changes, and radio-state changes
   remain live across page switching.

JUnit tests for the extracted coordinator:
`ShadeUiControllerTest` and `ShadeStatusNavigationTest`.
Source changes are not a substitute for compilation and Q25 runtime testing.

Rollback:
```sh
adb -s 202512003630 shell setprop debug.bb7pulldown.enabled false
adb -s 202512003630 shell am crash com.android.systemui
```
