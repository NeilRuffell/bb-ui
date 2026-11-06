# Notification counts in the shade

The left column displays a numeric count beside each application or category
icon. A count is the number of **cards shown in that category's right pane**,
not the application's unread-message total.

- Alerting app: count visible notifications for that app only.
- Silent: count the shared Silent pane. Media notifications already represented
  by the native media bar are excluded.
- Favorites: count notifications matching the current Favorites rules; show
  zero when no rules match.
- Android group summaries do not add an extra count when matching child cards
  are already displayed. A summary without a displayed child counts as one.

## Implementation

`SystemUiNotificationObserver` remains the only source of native
notification data. `TwoColumnPanel` uses
`NotificationCategoryCounts` to apply the same existing filters as the
right-hand cards. Counts update in-place when notification content changes,
even if the set of app icons does not. No notification is posted, removed,
modified, or re-ranked by the counter.

Each 56dp icon row remains one touch/trackpad focus target. The count is
display-only, has no independent input handling, shares the row highlight,
and is included in the row's accessibility description. The 15/85 column
split is unchanged.

## Tests

`NotificationCategoryCountsTest` verifies normal apps, Silent, Favorites,
group summaries, zero counts, media suppression and updates that leave the
app-icon list unchanged.

On the Q25, test an app receiving multiple notifications, dismissing one,
grouped notifications, Silent/Favorites categories, live updates while the
same app remains selected, highlight contrast, and touch/trackpad navigation.

Build and install from the feature branch:

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/notification-shade-icon-counts || \
  git switch --track origin/feat/notification-shade-icon-counts
git pull --ff-only
gradle :app:testDebugUnitTest :app:assembleDebug
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 202512003630 shell am crash com.android.systemui
```
