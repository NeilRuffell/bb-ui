# BB UI: design philosophy and system ownership

BB UI is a BlackBerry OS-inspired **user interface for Android**. Our aim is
to change how the device looks and how its keyboard/trackpad works, while
leaving Android responsible for the underlying system services.

The Q25 implementation currently runs as one Vector/LSPosed APK. It is being
structured for a future direct integration into LineageOS SystemUI, without
Vector or a separate always-running overlay service.

## One owner per responsibility

| Responsibility | Authoritative owner | What BB UI does |
| --- | --- | --- |
| Notifications | Android NotificationManagerService and SystemUI | Group, focus and present existing notifications; call native actions, dismissals and reply paths |
| Quick Settings | SystemUI QS host, QSTile objects and TileServices | Display native tile state in BB rows, forward primary/secondary/long actions, and follow the native listening lifecycle |
| Shade lifecycle | SystemUI notification-shade window and panel controller | Attach the BB page host to the existing native window; use the native expansion/collapse state |
| Status bar | Android's native status-bar window and views | Preserve real clock, notification, network, battery and privacy indicators; style focus without replacing their owners |
| Media | Android MediaSessionManager / MediaController | Present and control real active sessions, artwork, position and supported shuffle/repeat functions |
| Lock screen/security | Android keyguard, bouncer and credential services | Style the lock screen and expose supported native shortcuts; never replace credential verification |
| Recent applications | Android task management and Launcher3/Quickstep Overview | Replace Quickstep's visual presentation with **one** BBOS6 icon grid, while reusing native task actions, lifecycle and navigation; never mount or conceal a second renderer |
| Hardware navigation | One BB `ShadeUiController` and `ShadeStatusNavigation` | Map trackpad and keyboard focus to the appropriate native surface/action |

**Rule:** A new BB screen must not introduce a second implementation of an
Android service that already exists.

## What "one native shade" means today

With `debug.bb7pulldown.native_shade.enabled=true`:

1. SystemUI still owns `NotificationShadeWindowView` and its panel
   controller. BB UI adds `UnifiedShadeHost` as a child of that **same**
   shade window; no second `WindowManager` notification-shade window is made.
2. One `UnifiedShadeHost` owns both BB Notifications and Quick Settings
   pages, plus the shared date/media header. Changing pages changes their
   visibility instead of opening another window.
3. The existing native status-bar window is **separate by Android design**.
   BB UI keeps its real content visible and adds only a reversible focus
   background. A small temporary keyboard-focus input surface used outside
   the shade is *not* a second shade or a replacement status bar.
4. While the BB child is mounted, touch is routed to that presentation;
   underlying stock shade views cannot also respond to the same gesture.
   Native drawing is clipped below the real status bar, and underlying shade
   accessibility targets are temporarily hidden, then restored on removal.
5. Android still owns the notification and QS pipelines and all their
   permissions, settings, state changes and lifecycle.

The native shade is currently **opt-in**; the older independent BB overlay
remains available as an explicit rollback mode. Do not confuse that fallback
code path with the active native-shade path.

## Important limitation: one window is not yet one renderer

The stock native notification rows still exist under BB UI's interim
`TwoColumnPanel` and `BB6NativeQsPanel` views. The latter draw BB-styled
cards/rows using data and actions from native Android. This avoids a
*duplicate backend or competing interactive shade*, **but it still means
two view presentations exist in memory while BB UI is open**.

We must not describe this stage as a complete replacement of the stock
notification/QS view tree.

The planned next stage is to adapt native
`NotificationStackScrollLayout` / `ExpandableNotificationRow` and native
QS presentation components to the BB layout, grouping and trackpad focus;
then remove the redundant BB notification-snapshot renderer and custom
QS row renderer. Preserve Android's actual click, dismissal, RemoteInput,
tile listening, permissions, dialogs and accessibility behaviour.

That stage requires separate device testing. It is not implemented yet.

## Shared navigation, no competing Back handlers

`ShadeUiController` and `ShadeStatusNavigation` own one set of focus
and page-selection rules. Neither BB page builds another controller.

`NativeShadeBackBridge` hooks the Q25's existing
`BackActionInteractor.onBackRequested()` **only when BB UI is mounted**.
Back then follows the same depth semantics used by the physical-key
handlers: exit inline reply, collapse media controls, return from a
notification to its app column, or close the shade. A QS-level Back closes
the QS shade. Focused status-bar Back closes the active shade.

This replaces the stock animated Back dismissal only for the BB native
shade. When BB UI is absent, Android's own Back handling remains intact.
Home and system-dialog closure continue through the native collapse path.

## Source boundaries

- `PullDownHook.java`: present-day SystemUI/Vector entry adapter,
  one shade session, notification and media wiring.
- `NativeShadeHostBridge.java`: use the existing native shade window,
  expansion state, touch routing, clipping and teardown.
- `NativeShadeBackBridge.java`: native Back callback integration.
- `NativeShadeStatusBarVisibility.java` and
  `NativeShadeBlurPolicyHook.java`: preserve the original status-bar
  content and focus appearance when the BB shade is expanded.
- `ShadeUiController.java` and `ShadeStatusNavigation.java`:
  framework-independent navigation policy.
- `UnifiedShadeHost.java`, `TwoColumnPanel.java`,
  `BB6NativeQsPanel.java`: current BB presentation.
- `SystemUiNotificationObserver.java`, `NativeQuickSettingsTiles.java`,
  `NativeMediaSessions.java`: adapters to Android-owned system data.

The reusable UI/policy classes do not need to import Xposed types.
ROM-specific interception is confined to the current adapters wherever
practical. The standalone Vector APK is a development/compatibility
delivery method, not the final ROM architecture.

## Rules for future changes

- **Reuse first.** Inspect the actual installed Q25 SystemUI classes, then
  prefer existing Android controllers, view models, events and services.
- **One visible interaction path.** Avoid duplicate notification shades,
  Quick Settings panels, Recents renderers, status indicators, focus controllers
  and synthetic tile/radio state. Hiding a second surface is not enough:
  the superseded Recents view tree must not remain mounted behind BB UI.
- **Do not break ownership.** Native notification actions, RemoteInput,
  tile dialogs, permission checks, media sessions and credential prompts
  must still be handled by Android.
- **Keep changes reversible.** Scope hooks to the correct process, current
  view and device state. Restore view attributes, listeners, clip bounds
  and accessibility state on teardown.
- **Design for a ROM port.** Use native SystemUI dependencies in the ROM
  rather than carrying reflection/Xposed hooks forward. Remove redundant
  custom renderers when native views take their place.
- **Test more than appearance.** Check touch, physical Back, Home, trackpad,
  notification actions/replies, QS details, media, heads-up, lock/unlock,
  rotation, screen-off and repeated open/close. Compilation is not evidence
  of on-device UI correctness.

## Recents migration boundary

The previous `NativeRecentsGridHook` portrait task-card adapter was deleted
in `feat/bbos6-recents-replacement`. Quickstep's native `RecentsView`
remains the **only** Overview host and Quickstep remains the system task owner.
`QuickstepIconGridHook` intercepts **RecentsView's top-level draw(Canvas)**
and supplies BBUI's own presentation. It does not call native draw or
dispatchDraw: **no Quickstep tile, thumbnail, header, chip, label, card or
stock focus visual is rendered**. The native TaskView objects survive
only as headless Quickstep task-data and launch-action handles; they are not
another presenter. No second Activity, overlay, parallel task list or
alternative Overview window is constructed.

All touch hit-testing, swipe paging, keyboard selection and focus highlighting
come from the BBUI 4x4 icon grid, not the native carousel. The eventual
LineageOS source integration can remove the now-unnecessary native task
child-view inflation and bind directly to the same Quickstep task model.
Until that ROM integration, the Vector hook is opt-in and Q25 validation
is pending. The BB key continues to route Switch Application through the
native SystemUI Recents command. See [Recents plan](RECENTS_REPLACEMENT.md).

## Development status and next steps

**Q25-tested:** One native shade window hosts the BB interface, normal
status bar contents and blue focus styling are retained, and the native
Back callback closes responsively. These findings were checked on-device
during the 0.9.76–0.9.83 development cycle.

**Still in progress:** Replace the interim BB notification/QS renderers
with native SystemUI presentation components; port the finished behaviour
into LineageOS SystemUI source; eliminate Vector as a runtime dependency.

See [native shade migration](NATIVE_SHADE_MIGRATION.md) for the staged
migration, Q25-specific evidence and rollback. This document is the current
architecture contract; older per-version diagnostics remain historical.
