# Bringing BB UI into a LineageOS build

This page is for ROM maintainers who want to understand the code before integrating it. **There is no ready-to-apply ROM patch yet.** BB UI currently runs as an Xposed/Vector module on the development device.

The long-term goal is to keep the BB UI appearance and navigation code while replacing the runtime hooks with native integrations.

## The current design

```text
Android SystemUI process
    |
    +-- PullDownHook              current Xposed integration
          |
          +-- ShadeUiController    single state and focus owner
          |     +-- ShadeStatusNavigation
          |
          +-- UnifiedShadeHost    one shade window
                +-- ShadeDateHeader
                +-- TwoColumnPanel       notifications
                +-- BB6ConnectionsPanel  connections
```

The status-bar graphics currently modify the native status-bar view. The custom shade is still a separate SystemUI overlay window, although its two pages share one host and do not reopen when switching.

The focus controller and view classes above do not import Xposed APIs. They are the starting point for a native port, **not a complete standalone SystemUI implementation**.

## What to reuse and what to replace

| Reuse as a starting point | Requires native integration or replacement |
| --- | --- |
| `ShadeUiController`, `ShadeStatusNavigation` | `PullDownHook` and its Xposed hook registration |
| `UnifiedShadeHost` and the shade page layouts | Status-bar and shade ownership inside the target ROM's SystemUI |
| `ShadeDateHeader`, `BB7Theme`, UI/policy classes | Notification observation, click/action reporting, media and Quick Settings service bindings |
| The existing keyboard-focus behavior and tests | Lock-screen behavior, system dialogs, process lifecycle and platform permissions |

Review `SystemUiNotificationObserver`, `NativeNotificationClicks`, `NativeNotificationActions`, `NativeQuickSettingsTiles`, and `NativeMediaSessions` carefully. These are interfaces to real Android services and internal SystemUI behavior; their implementation and availability are ROM-version-dependent.

Many of the classes are package-private. When moving files into a ROM source tree, preserve their package or update access and imports deliberately.

## Suggested porting order

1. **Choose a specific LineageOS source revision and device.** Check how its SystemUI owns the status bar, shade, notification pipeline, and Quick Settings. Do not assume internal class names match the development device.
2. **Move the reusable controller and view code** into a SystemUI build target. Keep one `ShadeUiController` per UI session owner, not one per page.
3. **Replace the Xposed adapter.** Connect the controller to native status-bar focus, keyboard events, shade open/close, and page selection.
4. **Integrate the shade into SystemUI's real window/lifecycle.** The current `WindowManager` overlay is for module deployment; simply preinstalling the APK does not make the shade native.
5. **Connect native services.** Preserve Android's permission checks, lock-screen privacy, notification click/action handling, cancellation semantics, media sessions, and connectivity controls.
6. **Remove competing entry paths.** Once the native shade is installed, confirm that stock and custom shade gestures cannot open two different interfaces.
7. **Port other features separately.** Quickstep Recents lives under Launcher3, and Settings search lives in Settings/Settings Intelligence. Replacing the shade alone does not move those features into a ROM.
8. **Build and test on the target ROM.** Test hardware navigation, notification actions, lifecycle, keyguard, screen-off, orientation, and recovery from SystemUI restarts.

Do not copy `xposed_init`, the APK's Xposed manifest metadata, or its module-specific hook setup into a native SystemUI build. Native integration uses the ROM's normal build, package, and signing arrangements.

## Behavior that should remain consistent

- Back on the standalone status bar releases focus.
- Back on the status bar with a shade open closes the shade and clears focus.
- Down on the status bar returns to the open page; without a shade it opens native Recents.
- Notifications and Quick Settings share one status-bar focus controller.
- Switching between the two pages does not replay the shade-opening animation.
- The date header turns gray while the status bar is selected and blue while shade content is selected.
- Android remains authoritative for notifications, notification actions, and connectivity.

The source-level navigation tests are under `bb7pulldown/src/test/java/`. They do not cover native ROM integration.

## Before redistributing

The repository does not currently contain a root `LICENSE` file. Check the original source's license and obtain any necessary permissions before distributing modified code in another ROM. This document does not grant reuse rights.

For the buildable module and source directory map, return to the [README](../README.md).
