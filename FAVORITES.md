# Favorites notification category (test branch)

Branch: `feat/favorites-notification-rules`

## Accessing settings

Install the BB UI APK. The application drawer now contains **BB UI Favorites**.
Open it to enable the Favorites icon, select applications, and configure filters.

- **All notifications:** Adding an app defaults to showing all its notifications in
  Favorites. Existing app categories and Silent notifications remain unchanged.
- **Only matching filters:** Rules can identify a conversation by a native shortcut
  or conversation-channel ID, or by text contained in the notification title or
  the Android conversation title. Text matching is case-insensitive.
- **Multiple rules:** Choose ANY (default) or ALL within one app.
- **Native conversation picker:** After adding an app, open the BB UI notification
  shade while that app has an active conversation notification. Return to Favorites
  settings and choose **Add filter → Select native conversation**. The picker only
  presents identifiers that Android actually supplied for currently observed
  notifications. If none were supplied, use **Notification title contains text**
  instead; no private app database or guessed ID is used.
- **No matches:** Enabling Favorites keeps its star visible even when there are
  currently zero matches.

The Favorites star is above the app selector. Selecting it shows matching
notifications from configured apps with each source application's icon. Tapping,
native actions, reply, touch swipe dismissal, and Backspace/Delete operate on
the original Android notifications. Favorites does not duplicate notification
records or alter notification importance, posting, or delivery.

## Security and data

Rules are stored in the BB UI application's private SharedPreferences, never in
SystemUI preferences or external storage. SystemUI reads them through an exported
but UID-restricted content provider; other third-party app UIDs are denied.
For the native conversation picker, SystemUI reports only observed conversation
identifiers, source package names, and conversation labels for apps already
configured in Favorites. **Message bodies are not stored**. Removing an app from
Favorites deletes its stored conversation-picker metadata. No app data is read
outside Android's native notification metadata.

The provider's rules URI uses a ContentObserver to refresh Favorites while the
shade is open. The observer is unregistered when the shade is dismissed.

## Build and test

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch --track origin/feat/favorites-notification-rules
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb -s 202512003630 install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s 202512003630 shell am crash com.android.systemui
```

### Manual acceptance checklist

1. Open **BB UI Favorites** from the launcher; enable Favorites, add Messenger.
2. With *Only notifications matching filters* disabled, ensure all Messenger
   notifications that normally appear in the shade also appear under Favorites.
3. Add a case-insensitive *Title contains* rule, then verify matching and
   nonmatching notifications across apps.
4. With a conversation notification active, reopen the shade then select a
   native conversation ID from Favorites settings if one is available.
5. Rename or vary the conversation notification title; an ID-based match should
   remain if its app retains the same ID.
6. Test ANY/ALL, disabling Favorites, empty Favorites, and deleting an app rule.
7. Verify original app and Silent categories are unchanged and touch swipe,
   Backspace/Delete, native actions, inline reply, and media bar still work.
8. Test with an app that exposes no conversation IDs; title filtering should
   remain functional.

This branch has source-level checks and unit tests but requires an APK build
and device verification before merging.
