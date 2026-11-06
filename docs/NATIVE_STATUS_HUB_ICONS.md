# Native status bar — Hub category icons and counts

BBUI can group status-bar notification indicators by native Android category
without adding a second status bar or altering notification delivery.

## User-visible mode

- **By application** (default): existing app notification icons and existing
  Q25 full-colour icon treatment remain completely unchanged.
- **By notification type (Hub)**: the actual status bar displays one icon per
  currently present native notification type, with the number before the icon
  as in BlackBerry OS. Categories with no eligible notifications are omitted.
  Supported categories are Messages, Email, Calls (including missed calls and
  voicemail), Calendar, Reminders, Alarms, Other.
- Counts use the same text size and typeface as the native status-bar clock
  (LineageOS fallback 14sp). Category icons use a 26dp-wide, full-height
  image box without additional padding; the drawable provides its margins.
  A 5dp count-to-icon gap and 10dp gap between active category groups prevent
  crowding. No trailing margin is added after the last category, so the
  group remains horizontally centered as a unit.
- Switching modes in **BB UI Settings** takes effect through the existing
  app-owned ContentProvider and its live mode-change observer.

The status bar intentionally excludes the shade-only Favorites pseudo-category:
Favorites matches an overlapping subset, and showing it would duplicate
status-bar counts. Silent status-bar notifications are included only if native
SystemUI already gave them a normal status-bar icon.

## Ownership and safety

- `NativeStatusBarHubIcons` targets only the real SystemUI
  `NotificationIconContainer` underneath `notification_icon_area`.
- The real native icon container remains attached; its notification-entry
  lifecycle, filtering, ordering, privacy checks and companion AOD/shelf views
  are not replaced or re-parented.
- The category strip is a normal display-only child of the existing
  **full-width PhoneStatusBarView**, centered with
  `Gravity.CENTER_HORIZONTAL` against the actual status-bar width.
  It is **not** centered against the wrap-content left notification slot,
  which would place it too far left. This is not a second window,
  foreground service, or independent listener. App mode removes the strip
  visually and restores the original native icon container.
- Native SystemUI may change icon-container visibility after notification
  changes. To avoid showing both app icons and Hub indicators, Hub mode
  suppresses all native children of the **notification-only**
  `notification_icon_area` host, reapplies suppression just before drawing,
  and restores each child's captured prior visibility on mode change.
  The centered category strip is not a child of that host and therefore
  cannot be suppressed with the original app icons.
  This never touches clock, battery, connectivity or privacy containers.
- Native status icon view membership is the eligibility source. Only icons
  admitted by the native status-bar binder may contribute to counts. The
  existing `NotificationTypePolicy` supplies type classification.
- Group summaries are deduplicated across types before counting. The
  displayed number refers to notification entries, not unread messages
  within an application.
- If the required Q25 view classes, notification references, or parent area
  cannot be resolved, the hook leaves stock status icons visible.
- Notification content, PendingIntents, dismissals, ranking, and original
  notifications are never modified. All display updates run on SystemUI's
  main thread.

This development-stage view adapter is separate from the future direct
LineageOS SystemUI implementation. The ROM port should integrate category
grouping at the native icon-viewmodel/container layer.

## Build and Q25 validation

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/statusbar-notification-categories || \
  git switch --track origin/feat/statusbar-notification-categories
git pull --ff-only
gradle :app:testDebugUnitTest :app:assembleDebug &&
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk &&
adb -s 202512003630 shell am crash com.android.systemui
```

Select **By notification type (Hub)** in **BB UI Settings**.

Test one/multiple app notifications of the same category, new arrivals and
removals, **absence of any original coloured app icons while Hub mode is active**,
group summaries, unclassified entries, mode switching while
the shade is open and closed, SystemUI restart, screen lock/unlock, and
status bar appearance on light/dark wallpaper. Confirm that the category
group is centered against the full screen, not against the left icon slot,
with one or multiple categories. Compare the number size against the clock
and glyph dimensions against signal indicators, verifying no clipping. Verify native icon overflow and correct
width allocation on the Q25 hardware, including center-clock or center-traffic
configurations if enabled; do not cover system status indicators.

```sh
adb -s 202512003630 logcat -d -s \
  'BB7-StatusHub:V' 'BB7-StatusIcons:V' 'AndroidRuntime:E' '*:S' | tail -120
```

## Independent emergency rollback

```sh
adb -s 202512003630 shell setprop debug.bb7pulldown.hub_status_icons.enabled false
adb -s 202512003630 shell am crash com.android.systemui
```

This restores the existing per-app native status icon behavior and does
not disable the BBUI shade, category mode, or full-colour app icon hook.
The property is checked when SystemUI installs the hook, so a SystemUI
process restart is necessary to change its value.
