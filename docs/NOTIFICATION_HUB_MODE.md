# BB UI notification organization

The BB UI Settings launcher (formerly named BB UI Favorites) provides the
**Notification shade** grouping choice:

- **By application** (default): existing dynamic app icons with the existing
  shared Silent and optional Favorites entries. Existing behavior remains the
  default when the setting is missing, invalid or the settings provider is
  unavailable.
- **By notification type (Hub)**: seven persistent entries — Messages, Email,
  Calls, Calendar, Reminders, Alarms, Other — plus optional Favorites.
  A category stays visible when empty, and its number is hidden when zero.

Category rows reuse the existing 56dp touch/trackpad target, selection
gradient, Back hierarchy and date/card display. These category icons are drawn
locally by `NotificationTypeIconDrawable` and remain UI-only; no Android
notification is copied, rerouted, dismissed or re-ranked.

## Classification

Android `Notification.category` is authoritative where available:
`msg`, `email`, `call`, `missed_call`, `voicemail`, `event`,
`reminder`, `alarm`. For missing/unrecognized categories, native
`CallStyle`, `MessagingStyle` and conversation shortcut metadata are
fallbacks. No text-matching or app-name heuristics. Unclassified notifications
appear under Other. Native video-call metadata is retained on the
`NotificationItem` for potential future presentation but grouped under Calls.

The right pane and count use one `NotificationShadeModePolicy.select` path.
Group-summary suppression and silent-media deduplication reuse existing
code. A silent message still belongs to Messages in Hub mode. Favorites is
an optional cross-category filtered shortcut, as in App mode.

## Settings ownership and live updates

`FavoritesSettingsProvider` remains app-private and stores the mode under
the existing preferences. A new `shadeMode` query is available only to the
BB UI UID and real SystemUI UID. The settings screen writes it; SystemUI reads
it through `NotificationShadeModeClient`. Writes notify the existing
live change observer while the shade is visible. This does not require
a new background service or a second SystemUI notification listener.

## Verification (Q25)

Build `feat/notification-hub-categories` and run:

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/notification-hub-categories || \
  git switch --track origin/feat/notification-hub-categories
git pull --ff-only
gradle :app:testDebugUnitTest :app:assembleDebug
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 202512003630 shell am crash com.android.systemui
```

Test settings persistence across SystemUI restart, live switching while the
notification shade is open, empty persistent categories, multi-app messages,
calls/missed calls, missing classifications under Other, grouped summaries,
Favorites, media suppression, highlight/trackpad/touch navigation, and
existing App mode including its separate Silent category.

Hardware testing is required: apps do not universally provide category
metadata, so actual app classifications vary.
