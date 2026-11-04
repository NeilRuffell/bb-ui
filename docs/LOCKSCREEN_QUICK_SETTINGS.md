# Limited Quick Settings on the BBOS6 lock screen

**Status:** `feat/bb6-lockscreen-quick-settings` — Vector APK test branch.
No ROM rebuild, reflash, or new application is involved.

## User behavior

- On the interactive BBOS6 lock screen, select the combined **status bar +
  date/time** item using the trackpad and press **Enter / trackpad center**.
- A compact BB6 **Manage Connections** panel opens in the same SystemUI
  window, just below the date/time bar.
- The only locked controls are **Mobile Network**, **Wi-Fi**, and
  **Bluetooth**. The panel uses our existing `BB6ConnectionsPanel`.
- Up/Down selects a row; Enter/trackpad center invokes that row's native
  SystemUI QS tile. Left/Right do not change rows.
- Back (or Up from the first row) closes the compact panel and returns
  focus to the combined status/date-time item.
- Physical **Space** is never a QS activation key while locked. The
  unchanged `BB6KeyguardKeyboardHook` intercepts it at
  `WindowRootView.dispatchKeyEvent` to open Android's native credential UI.

The original notification indicators remain centered. The unlocked
notification/QS shade still uses its original navigation and presentation.

## Implementation

There is one shared connection stack:

- `ConnectionControlPolicy`: labels and Android tile specs.
- `NativeConnectionControls`: reads current system Wi-Fi, Bluetooth,
  and mobile-data states. Both panels use the same status source.
- `NativeQuickSettingsTiles`: captures SystemUI's actual
  `QSTileHost` and calls the existing tile `click(null)` method.
  The hook is registered only once even when both locked and unlocked
  integrations initialize.
- `BB6ConnectionsPanel`: same view and row behavior with a separate
  `lockscreenMode` flag. It never creates a new independent radio API.
- `LockscreenQuickSettingsPolicy`: explicitly fixes the lock-screen
  allowlist to exactly those **three** kinds, even if the unlocked
  `ConnectionControlPolicy.COUNT` grows in future.
- `BB6LockScreenHook`: owns the compact panel lifecycle, checks Android's
  live keyguard and bouncer state, and subscribes to native connection
  state broadcasts while the panel is displayed.

## Security boundaries

The limited QS panel is available **only** when the device is locked,
interactive, not dozing, not displaying the native credential bouncer, in
the actual `KEYGUARD` state, and the BB6 interactive blueprint is
applied. These conditions are checked when opening **and again on every
control activation**.

Locked actions call the **same SystemUI QS tile backend** as the unlocked
panel. A tile's own native lock-screen behavior remains authoritative.
If the corresponding tile is unavailable, the action fails closed:
**no fallback to Internet Settings, Bluetooth Settings, or another
Settings activity occurs from the locked panel**. Direct Wi-Fi,
Bluetooth, telephony, or mobile-data mutators are not used.

The panel closes on screen-off and on native password/PIN/pattern
presentation, and is removed when the keyguard, bouncer, blueprint or
lock-screen mode changes. Its broadcast receiver is unregistered on close.
It is a child of SystemUI's existing native window, not an additional
focusable `WindowManager` overlay.

## Build, install, and verify

On KDE neon with the same system-wide Gradle and Android SDK as before:

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/bb6-lockscreen-quick-settings
git pull --ff-only
gradle :app:assembleDebug :app:testDebugUnitTest &&
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/bb-ui.apk &&
timeout 45s adb shell pm install -r /data/local/tmp/bb-ui.apk &&
adb shell setprop debug.bb6lockscreen.enabled true &&
adb shell setprop debug.bb6lockscreen.blueprint true &&
adb shell am crash com.android.systemui
```

Check on Q25: lock → select status/date → Enter → three controls →
trackpad navigation → Back → status/date focused again. Check native QS
tile behavior for each control, including the native Internet and
Bluetooth UI if opened by the tile. Verify Space opens the password
entry **while the panel is open**, and test unlock/relock and screen off/on.
Verify the original unlocked QS panel still works unchanged.

```sh
adb logcat -d -s 'BB6-LockScreen:V' 'BB6-Connections:V' 'BB6-Keyguard:V' 'AndroidRuntime:E' '*:S' | tail -100
```

Emergency rollback (keeps the merged Space-to-password feature):

```sh
adb shell setprop debug.bb6lockscreen.enabled false
adb shell am crash com.android.systemui
```

**GitHub Actions can verify only APK compilation and unit tests, not
native Q25 tile security behavior or physical trackpad input. Do not
merge this feature into `master` before it is tested on the device.**
