# Status bar and shade navigation

BB UI has **one focus controller**, shared by the status bar, Notifications, and Quick Settings. This document describes the behavior in the current code on `master`, not an earlier feature branch.

## How it fits together

- `ShadeUiController.java` owns which page is open and whether the status bar has focus.
- `ShadeStatusNavigation.java` handles status-bar key events and the left/right selection.
- `UnifiedShadeHost.java` holds a single date header and two content pages in one shade window.
- `TwoColumnPanel.java` and `BB6ConnectionsPanel.java` handle navigation *inside* their respective pages.
- `PullDownHook.java` connects this logic to SystemUI through Xposed. It also owns the current overlay window lifecycle.

No content page constructs another `ShadeUiController`. When the active page changes, the host switches which page is visible without reopening its window.

## Trackpad and Back behavior

| Current focus | Back | Down | Enter / trackpad click |
| --- | --- | --- | --- |
| Status bar, no shade open | Release status-bar focus | Open native Recents | Open selected shade page |
| Status bar, shade open | Close shade and release focus | Return to active page content | Open/switch to selected page |
| Notification app icons | Close shade | Move to next icon | Open selected app's notification list |
| Notification card or action | Return toward app-icon selection | Move to next item | Open notification or invoke its native action |
| Quick Settings row | Close shade | Move to next row | Open Android's native connectivity control |

**Left/Right** changes the selected half of the status bar, but does not move between notification columns or between Quick Settings rows.

**Up** from the first notification icon reaches the media row if visible, then the status bar. **Up** from the first Quick Settings row goes directly to the status bar.

The status-bar navigation controller treats **Back** and **Down** as distinct actions. Back exits; Down returns to content when a shade is open.

## Appearance and lifecycle

The shared date bar is **gray** while the status bar has focus and **blue** while a shade's content has focus. Opening and closing the whole shade uses its normal animation; switching between Notifications and Quick Settings does not reanimate it.

The current module uses a SystemUI overlay. On screen-off, lock-state protection, or system-dialog close, the integration removes that overlay and clears its focus state.

## Tests

The controller's tests are:

- `bb7pulldown/src/test/java/dev/q25/bb7pulldown/ShadeUiControllerTest.java`
- `bb7pulldown/src/test/java/dev/q25/bb7pulldown/ShadeStatusNavigationTest.java`

Run them with:

```sh
gradle :app:testDebugUnitTest
```

Device testing is still required for SystemUI window ownership and hardware key delivery. For ROM integration details, see [docs/ROM_PORTING.md](docs/ROM_PORTING.md).
