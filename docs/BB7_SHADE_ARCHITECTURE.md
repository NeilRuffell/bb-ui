# BB7 Shade — architectural plan (LineageOS 24, Zinwa Q25)

## Objective
Provide **one** user-visible system shade with two horizontal pages:

1. **Notifications (default)**: narrow left column with one entry per app
   that has eligible notifications; right pane displays that app's
   notifications as native Android notification cards. An `All` category
   is a UI-only affordance and is not an Android notification.
2. **Quick Settings (right)**: an independent QS page that reuses Android's
   existing controllers and state.

SystemUI's native notification pipeline and QS functionality remain the
authoritative backends. The stock shade must never flash through or remain
interactive behind the replacement after takeover is enabled.

## Input contract
- Notifications: up/down selects apps in left column; right enters the
  selected app's notification list; left returns to app column.
- Notifications list: up/down navigates native rows/actions.
- Page switch: explicit horizontal navigation at a page boundary, without
  stealing horizontal input needed for native notifications/actions.
- The selected app filters only the **presentation**, never the Android
  notification collection or pipeline.
- Device keyboard/trackpad selection remains authoritative; no commands.
- Lock-screen shade and notification privacy are unchanged in initial phases.
- Preserve status bar, heads-up alerts, notification dismissal, grouping,
  inline reply, QS edit mode, media, privacy controls, and accessibility.

## Separation
- `:app`: BB UI existing launcher/recents/settings behavior.
- `:bb7focus`: BB7 Appearance, existing Settings/notification focus visuals.
- `:bb7shade`: new independent Vector APK, scoped to
  `com.android.systemui`. Package: `dev.q25.bb7shade`.

## Upstream interfaces (LineageOS 24)
- `ShadeScene.kt` and `QuickSettingsScene.kt` in
  `packages/SystemUI/compose/features/.../ui/composable/` implement
  separate scenes, but the shade scene also contains QQS by default.
- `NotifPipeline.kt` exposes `addOnBeforeRenderListListener`: the
  callback supplies the **already filtered, grouped, sorted** list handed
  to rendering. Top-level elements can be NotificationEntry, GroupEntry,
  or BundleEntry. Use this for application indexing, not getAllNotifs().
- `NotificationStackScrollLayout` and `ScrollingNotificationPanel` own
  native row rendering and scrolling behavior.
- Quick Settings uses Compose; native View repositioning does not
  rearrange QS tiles reliably.

## Phase gates
1. **Instrumentation and data index (v0.1; implemented):** Independent
   APK; observe SystemUI shade host and index package names/counts from
   the pre-render pipeline. No UI replacement, no suppression, no key
   interception, no notification mutation. Log counts only (no content).
2. **Replaceable host and native notification pane:** Develop and test
   the single-surface two-column interface and native notification row
   handoff; detect actual Q25 shade mode before activating.
3. **Quick Settings right page:** Integrate existing Compose QS
   controllers/tiles and explicit keyboard/trackpad page navigation.
4. **Atomic takeover:** Enable custom shade **only** after both pages and
   notification actions are functional. Then suppress stock visual
   presentation and test expansion, collapse, lock screen, shade gestures,
   lifecycle restart and rollback. No simultaneous stock/custom surfaces.

## Safety
- No APK in phase 1 changes the appearance of the stock shade.
- Disabling the BB7 Shade Vector scope and restarting SystemUI restores
  the stock behavior.
- Do not require system image reflashing, a device reboot for routine
  iteration, or changes to the existing modules.
- An unavailable pipeline/classloader results in a warning and passive
  fallback, **never an attempted replacement**.
