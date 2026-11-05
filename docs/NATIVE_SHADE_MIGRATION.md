# BB-UI native SystemUI shade migration

**Status:** Phase 1 complete in source; Q25 hardware test pending.
**Branch:** feat/native-bb-shade-host (not merged)
**Base:** feat/q25-simple-heads-up-notifications, commit 590a6d2
**Version:** 0.9.76-native-shade-host-experimental-dev

## Objective

Use ONE native SystemUI shade window for BlackBerry-style notification
and quick settings presentation. Retain Android's own panel, notification
lifecycle, QS tile services, click/dismiss/RemoteInput services, heads-up
routing, keyguard integration and back/gesture handling.

The intended ROM will ship this functionality as native SystemUI code,
without requiring Vector/Xposed.

## Q25 compatibility basis

Inspected the user-supplied Q25 SystemUI.apk, SHA-256:

    65003bbe5b00146a0e20d8612660426be05e571cce22d244fdcd656a2b3be8c0

ROM: LineageOS 24.0-20261025-NIGHTLY-Q25.

Compiled classes2.dex declares:
- NotificationPanelView extends FrameLayout
- NotificationPanelViewController.mView: NotificationPanelView
- NotificationPanelViewController.onFinishInflate(): void
- NotificationPanelViewController.setExpandedFraction(float): void
- NotificationPanelViewController.expandToNotifications(): void
- NotificationPanelViewController.instantCollapse(): void
- NotificationPanelViewController.isExpandedWithoutHeadsUp(): boolean

These were checked against actual compiled Q25 DEX, not assumed from
upstream LineageOS Java sources.

## Phase 1: native window ownership (this branch)

NativeShadeHostBridge hooks the native panel controller, captures its
existing NotificationPanelView, and follows its expansion state.

With native mode enabled:
- A BB UnifiedShadeHost becomes a child of the existing native shade
  FrameLayout. No WindowManager TYPE_STATUS_BAR_SUB_PANEL shade is created.
- Native expandToNotifications() opens the shade, instantCollapse() closes.
- Native shade expansion (including heads-up downward drags) can display
  the same BB UI presentation without opening another shade window.
- BB date/media header, notification page, QS page, favourite app grouping,
  hardware/trackpad focus, notification actions, media and real QS tile
  sessions are preserved.
- Original native notification views remain in the native panel for layout
  and lifecycle; the opaque BB child covers them, and their accessibility
  targets are temporarily suppressed while mounted.
- The BB6 keyguard is excluded from this unlocked-shade transition.

The former small standalone status-bar keyboard-focus surface is still
used for BB keyboard focus; it is not itself a notification shade.

**LIMITATION:** Although this removes the *second window*, the BB pages
are still rendered by TwoColumnPanel and BB6NativeQsPanel. Native
notification rows remain attached behind the BB child. This is a staged
migration, NOT yet the final one-renderer architecture.

## Phase 2: native notification row and QS presentation

Refactor notification presentation around the native
NotificationStackScrollLayout/ExpandableNotificationRow and Quick Settings
containers, retaining original SystemUI click/dismiss/RemoteInput services.
Move BB icon grouping, 15/85 layout, blue highlight, trackpad selection and
the two-page navigation into native view/controller components.

Remove the redundant BB UI notification snapshot-rendering path and custom
QS row rendering after their native replacements pass device tests.

**Do not remove Android's notification pipeline or native QS tile services.**
Those are the intended production owners.

## Phase 3: ROM integration

Port the same UI and navigation controllers into the LineageOS SystemUI
module, replace Xposed reflection with direct references/dependencies, then
remove Vector as a boot requirement.

## Installation

This experiment defaults OFF so users retain the previously working
overlay shade until they explicitly opt in.

    cd ~/Projects/bb-ui
    git fetch origin
    git switch --track origin/feat/native-bb-shade-host
    gradle :app:assembleDebug :app:testDebugUnitTest && \
    adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk && \
    adb -s 202512003630 shell setprop debug.bb7pulldown.native_shade.enabled true && \
    adb -s 202512003630 shell am crash com.android.systemui

Vector must continue scoping BB UI to com.android.systemui.
The existing debug.bb7pulldown.enabled flag must also remain true.
No ROM rebuild, flash or device reboot required.

## Diagnostics

    adb -s 202512003630 logcat -d -s \
      'BB7-NativeShade:V' 'BB7-PullDown:V' 'AndroidRuntime:E' '*:S' | tail -100

Expected after opening a shade:
- Native SystemUI shade ownership hooks registered
- Captured native NotificationPanelView
- BB UI mounted inside native NotificationPanelView; one shade window
- Native SystemUI notification shade expansion requested

The module must NOT log "Unified shade window attached" in native mode.

## Rollback (independent)

    adb -s 202512003630 shell setprop debug.bb7pulldown.native_shade.enabled false
    adb -s 202512003630 shell am crash com.android.systemui

This returns to the existing working BB UI overlay shade, preserving the
colour-status-icon, popup simplification, square-corner and global-width
changes. Nothing is merged to master without Q25 test results.

## Hardware tests before Phase 2

1. Pull down from status bar -> BB notification page in native shade.
2. Pull down from a heads-up -> BB UI, not the stock shade.
3. Open notifications -> Android original click/auto-cancel behaviour.
4. Swipe and dismiss, app-supplied actions, and RemoteInput reply.
5. BB notification/QS page switch, working native tile interactions.
6. Trackpad directional focus, Back and upward closing gestures.
7. Home, screen-off/lock/unlock, landscape rotation, and repeat open-close.
8. Stock popup and shade remain available under rollback mode.

Pass all eight tests before treating Phase 1 as a usable migration.
