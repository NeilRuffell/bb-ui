# BBOS6 Recents replacement — one native owner

## Decision and hard constraint

Replace the old portrait two-column Quickstep task-card layout with a BlackBerry OS 6-style grid of **application icons**. Continue to use Android's actual task and activity management, and the Q25's existing Launcher3/Quickstep overview entry point. The Vector/LSPosed APK is the current integration vehicle; a native ROM implementation is the target.

**One Recents presentation at a time.** Do not add a second Activity, floating overlay, custom task backend, hidden stock Recents UI, or duplicate Quickstep view hierarchy. A hidden/invisible/intercepted stock renderer beneath BBUI is unacceptable. If the required Quickstep host cannot be adapted to mount exactly one renderer under Vector, stop and document that limitation rather than shipping a second UI.

## Current baseline — stock restored

Branch: `feat/bbos6-recents-replacement`, based on `master`.

- Deleted `app/src/main/java/dev/q25/trackpad/NativeRecentsGridHook.java` rather than disabling it behind `bb_ui_vertical_recents`.
- Removed its `app/src/main/assets/xposed_init` entry, so BBUI no longer installs any old Recents grid hooks.
- Removed the Launcher3-to-SystemUI `RECENTS_VISIBILITY` broadcast and stale status-bar appearance state; these existed solely for the retired Recents grid.
- Left the existing SystemUI BlackBerry-key popup and its `showNativeRecents()` action unchanged. It is a command into Android's existing native Recents; it is **not** a renderer. Its behavior requires separate Q25 verification.
- The active Recents presentation is now stock Quickstep **only**. This is a transitional baseline, not the final BBOS6 design.
- The deprecated global setting `bb_ui_vertical_recents` is obsolete and can be deleted on the device.

## Confirmed source-level integration boundary

The retired hook targeted these Q25 Quickstep classes:

- Process/package: `com.android.launcher3`
- `com.android.quickstep.views.RecentsView`
- `com.android.quickstep.views.TaskView`
- `com.android.launcher3.PagedView`
- `com.android.quickstep.orientation.RecentsPagedOrientationHandler`

Those references verify which implementation the old code targeted, **not** that a replacement renderer has been tested on the current device build. Inspect installed Quickstep signatures and native layout ownership before implementing hooks.

SystemUI receives the BlackBerry-key Recents command in `PullDownHook.installRecentsInterception()`; its popup's Switch Application action calls native `showRecentApps`/fallback `toggleRecentApps`. Keep this entry routing distinct from Quickstep's visual renderer. Do not add a competing Recents command path.

## Proposed replacement architecture

| Layer | Sole owner | Future BBUI role |
| --- | --- | --- |
| Tasks and process state | Android ActivityTaskManager / platform task services | Read the existing task records; do not keep a competing task store |
| Overview lifecycle, launch/dismiss and transitions | Native Launcher3/Quickstep | Reuse existing state transitions and task actions |
| Overview presentation | One active Quickstep-hosted BBOS6 icon-grid renderer | Display real task application icons and labels, selected/focused state, and trackpad-compatible layout |
| BlackBerry key menu | Existing `ShadeUiController` and `PullDownHook` | Forward Switch Application to the same native Overview entry point |
| Vector | Temporary process-specific Xposed adapter | Hook the actual `com.android.launcher3` UI owner without adding a window |
| Future ROM | Direct Quickstep/Launcher3 source integration | Move the renderer and input policy into ROM; remove Xposed adapter |

The presentation model and keyboard/trackpad policy should not import Xposed types. Use a minimal Q25-specific Vector bridge for class/lifecycle adaptation.

## Next implementation checkpoint

1. Inspect the actual installed Q25 Quickstep layout and task APIs. Determine where native Recents renders cards, handles focus and opens/dismisses tasks. Confirm whether a true single-renderer replacement is possible from Vector.
2. Build an icon-grid view **inside the existing native Overview host**, replacing the stock presentation, not covering it. Preserve existing Quickstep lifecycle, task actions, accessibility and system gestures. Never retain two renderer trees.
3. Map directional keys/trackpad to grid selection, Enter to native task opening and Back to the native Overview exit. Confirm BBOS6-specific sizing and selection visuals against verified references.
4. Test with real tasks, empty overview, running foreground app, dismissal, rotation, sleep/wake, gesture entry, Home, Back and repeated opening. Check no blank/duplicate surfaces, crashes, stale views or unreachable tasks.
5. After Q25 verification, remove obsolete adaptation code and document the direct LineageOS ROM integration points.

No icon resource identity or exact grid geometry is asserted at this stage; first verify the BBOS6 reference. Any original numbered icon used must be indexed in `assets/bbos6/icon-index.csv` under the repository-wide policy.

## Build and first on-device baseline test

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/bbos6-recents-replacement
git pull --ff-only
gradle :app:assembleDebug :app:testDebugUnitTest && \
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk && \
adb -s 202512003630 shell settings delete global bb_ui_vertical_recents && \
adb -s 202512003630 reboot
```

After boot, open Android Recents through a native available path and verify stock presentation; verify BB key still opens its existing menu. The menu's Switch Application action is not claimed to be repaired by this baseline. Confirm functionality on the physical Q25 before proceeding to a new Quickstep renderer. `master` remains unchanged.
