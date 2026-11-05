# Native full-colour notification status-bar icons — Q25 Vector test

Branch: feat/q25-native-color-statusbar-icons. **Not merged into master.**

## Target and verification

Official Q25 LineageOS build: 24.0-20261025-NIGHTLY-Q25.

User-supplied \`SystemUI.apk\` SHA-256:
\`65003bbe5b00146a0e20d8612660426be05e571cce22d244fdcd656a2b3be8c0\`.

Direct parsing of the APK's \`classes2.dex\` verifies:

- \`IconManager.createIcons(NotificationEntry): void\`
- \`IconManager.updateIcons(NotificationEntry, boolean): void\`
- \`NotificationEntry.mIcons: IconPack\`
- \`NotificationEntry.mSbn: StatusBarNotification\`
- \`IconPack.mStatusBarIcon: StatusBarIconView\`
- \`StatusBarIconView.mNotification: StatusBarNotification\`
- \`StatusBarIconView.mShowsConversation: boolean\`
- \`StatusBarIconView.setStaticDrawableColor(int): void\`
- \`StatusBarIconView.updateDrawable(boolean): boolean\`
- \`StatusBarIconView.onDarkChanged(ArrayList, float, int): void\`

The previous reverted implementation used methods **not defined in this APK**:
\`StatusBarIconView.getNotification()\`, \`StatusBarIconView.setIconColor()\`,
\`IconManager.createSbIconView()\`, \`IconManager.updateSbIcon()\`.
Those calls/hooks must not be reintroduced without runtime verification.

## Design

This remains one ordinary BB-UI APK, injected by Vector into
\`com.android.systemui\`. The existing native SystemUI status bar, icon
containers, notification pipeline, ordering, animations, overflow, status
indicators and accessibility content remain owned by Android.

After \`IconManager\` creates or updates an icon pack, the hook selects **only**
\`NotificationEntry.mIcons.mStatusBarIcon\`. It checks that this view's
\`mNotification\` key matches its entry and excludes important-conversation
avatars. It replaces the visual drawable with the application's standard
icon and applies the native \`NO_COLOR\` value. Native drawable reload and
dark-mode callbacks are handled on the registered view only.

Shelf, AOD, grouped bundle icons, lockscreen app/count indicators, and
system indicators are untouched. If an application icon cannot be loaded,
leave the original notification small-icon drawable in place. No overlay,
SystemUI APK replacement, boot image, or ROM compilation is used.

This has **not yet been hardware-verified**. Android compile/unit tests and
the extracted class/method signatures cannot validate appearance or
lifecycle behavior on the phone.

## Build/install on Q25

\`\`\`sh
cd ~/Projects/bb-ui
git fetch origin
git switch --track origin/feat/q25-native-color-statusbar-icons
gradle :app:assembleDebug :app:testDebugUnitTest &&
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk &&
adb -s 202512003630 shell setprop debug.bb7pulldown.color_icons.enabled true &&
adb -s 202512003630 shell am crash com.android.systemui
\`\`\`

Enable **BB UI** for **System UI (\`com.android.systemui\`)** in Vector.
Confirm the normal \`debug.bb7pulldown.enabled\` property is true.

Expected log messages after SystemUI restarts and active notifications appear:
\`Q25 native full-colour notification icon hooks registered\` and
\`Rendered first full-colour native status-bar app icon\`.

\`\`\`sh
adb -s 202512003630 logcat -d -s \
  'BB7-StatusIcons:V' 'AndroidRuntime:E' '*:S' | tail -100
\`\`\`

## Independent rollback

\`\`\`sh
adb -s 202512003630 shell setprop debug.bb7pulldown.color_icons.enabled false
adb -s 202512003630 shell am crash com.android.systemui
\`\`\`

The property is checked when Vector installs hooks into SystemUI. The
default is true. Rolling it back leaves the rest of BB UI active.

## Device verification before merge

1. New notifications from several different applications display colour icons.
2. Existing notifications survive SystemUI restart and update correctly.
3. Changes of wallpaper/theme/dark status bar preserve app-icon colours.
4. Notification dismissal, ordering and overflow dots behave natively.
5. Important-conversation avatars remain unchanged.
6. Notification shelf, AOD, locked keyguard and system status indicators do
   not change appearance.
7. Repeated screen off/on and lock/unlock transitions do not crash SystemUI.
8. Disabling only the colour-icon property restores monochrome stock icons.
