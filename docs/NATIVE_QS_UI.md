# Native-backed BlackBerry Quick Settings (LineageOS 24.0)

Branch: feat/native-qs-ui (from feat/bb6-lockscreen-quick-settings).
This is a Vector APK; no ROM build, reflash or separate app is needed.

## Implemented

- Unlocked QS lists all available installed native tiles, in SystemUI order.
- Locked QS retains three BB6 labels: Mobile Network, Wi-Fi and Bluetooth.
  These use installed cell, wifi or shared internet tiles and the bt tile.
  Uninstalled or unavailable native tiles display Unavailable, not a new backend.
- QSHostAdapter supplies the installed tile collection and edit notifications.
  QSTile supplies labels, state, icons, callbacks, listening and actions.
- Per-panel native callbacks and listening are registered while the view is
  active, and unregistered on exit. SystemUI retains permission checks,
  authentication, third-party TileServices, native tile lifecycle and user
  switching.
- Up/Down navigate rows; Left/Right leave the row unchanged; Enter and
  trackpad center activate. Menu uses native secondary click when supported,
  otherwise native long click. Touch long-press invokes native long click.
  Locked QS permits only native primary clicks.
- Locked Back returns to the status/date focus row; Space remains owned by
  the existing WindowRootView Space-to-password hook.

## Source review

Inspected the following LineageOS sources on branch lineage-24.0:

- https://github.com/LineageOS/android_frameworks_base/blob/lineage-24.0/packages/SystemUI/src/com/android/systemui/qs/QSHostAdapter.kt
- https://github.com/LineageOS/android_frameworks_base/blob/lineage-24.0/packages/SystemUI/src/com/android/systemui/qs/QSHost.java
- https://github.com/LineageOS/android_frameworks_base/blob/lineage-24.0/packages/SystemUI/src/com/android/systemui/qs/pipeline/domain/interactor/CurrentTilesInteractor.kt
- https://github.com/LineageOS/android_frameworks_base/blob/lineage-24.0/packages/SystemUI/plugin/src/com/android/systemui/plugins/qs/QSTile.java

The Q25 Pro LineageOS 24 SystemUI binary was verified by device logcat to
throw NoSuchMethodError for both QSHostAdapter.getTiles() and
CurrentTilesInteractorImpl.getCurrentTiles(). These source-level method names
must not be assumed to survive ROM compilation.

The bridge now reads the native CurrentTilesInteractor's existing StateFlow
through runtime-verified type information and extracts the QSTile references
from TileModel fields by their native interface type. It does not instantiate
or manage a separate tile backend. QSHostAdapter.getTiles() is used only if
that method actually exists in the installed runtime. Native tile loading can
be asynchronous; a brief startup-only retry checks for the first populated
collection. Logs include host/interactor presence and tile count under the
BB6-NativeQS tag.

The structural adapter needs device testing to verify that the actual ROM's
QSTile methods and fields remain accessible. A successful Gradle build alone
does not prove successful runtime access.

The old QSTileHost constructor hook is not appropriate for the inspected
LineageOS source; QSHostAdapter now fronts the QS pipeline.

NativeQuickSettingsTiles.java contains the Vector-specific bridge.
BB6NativeQsPanel.java is an ordinary Android View implementation and has
no Xposed imports. Both unlocked and locked surfaces use the same class.

The old BB6ConnectionsPanel and NativeConnectionControls implementations
were retired. ConnectionControlPolicy now only defines locked row labels.

## Q25 Bluetooth dialog crash guard

The Q25 LineageOS 24 Bluetooth details dialog uses an AndroidX
NestedScrollView containing `bluetooth_toggle`. On November 3, 2026, the
device repeatedly reported:

    IllegalArgumentException: parameter must be a descendant of this view
    androidx.core.widget.NestedScrollView.isWithinDeltaOfScreen()
    androidx.core.widget.NestedScrollView.onSizeChanged()

Closing the BB shade before opening the dialog did NOT resolve the crash.
`NativeBluetoothDialogGuard` hooks only this native AndroidX focus-scroll
calculation, and returns false only when the focused View is not a
descendant of the Bluetooth dialog's `scroll_view`. The normal native
Bluetooth dialog, native QSTile actions, and valid scroll calculations are
preserved. The guard logs when installed and when it suppresses the
invalid-focus calculation. This is a temporary Vector-only workaround;
a future ROM port should address the AndroidX focus defect at its source.
The workaround requires physical Q25 verification.

## Still native / not yet replaced

- Android-owned tile dialogs, permission/authentication surfaces and Compose
  detail views remain native. Custom/third-party tile-supplied dialogs cannot
  be safely synthesized by a general-purpose Vector QS list.
- Native tile editor and installed-tile catalog are not re-skinned.
  Android retains tile ordering, persistence, creation, destruction, user
  switching and third-party TileService management.
- Unlocked activation currently removes the BB shade before invoking the
  native tile action, preserving native dialog stacking rather than retaining
  QS over the action. Keeping QS open for ordinary toggles needs Q25 tests.
- No Q25 runtime tests have yet been completed. ROM-specific implementation,
  keyguard transitions, AOD, native details, and third-party controls require
  verification on the actual LineageOS 24 device.

The later ROM port should inject BB6NativeQsPanel into native SystemUI and
replace the Vector adapter, not introduce a second tile backend.

## Build and install (KDE neon)

    cd ~/Projects/bb-ui
    git fetch origin
    git switch --track origin/feat/native-qs-ui
    gradle :app:testDebugUnitTest :app:assembleDebug
    adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/bb-ui.apk
    timeout 45s adb shell pm install -r /data/local/tmp/bb-ui.apk
    adb shell setprop debug.bb7pulldown.enabled true
    adb shell setprop debug.bb6lockscreen.qs.enabled true
    adb shell am crash com.android.systemui

Expected version: 0.9.52-native-bt-focus-guard-dev (versionCode 82).

Test native tile state changes, edited ordering, installed third-party tiles,
keyboard focus, Enter, Menu, Back, touch, and all native action dialogs on
the unlocked shade. On the lock screen, test the three-row restriction,
Space-to-password, Back-to-header, bouncer, screen-off and unlock/relock.

    adb logcat -d -s 'BB6-NativeQS:V' 'BB6-LockScreen:V' \
      'BB7-PullDown:V' 'BB6-Keyguard:V' 'AndroidRuntime:E' '*:S' | tail -150

Disable both QS surfaces without disabling the BBOS6 lock-screen clock or
Space-to-password:

    adb shell setprop debug.bb7pulldown.enabled false
    adb shell setprop debug.bb6lockscreen.qs.enabled false
    adb shell am crash com.android.systemui

Do not merge to master without explicit approval and device verification.
