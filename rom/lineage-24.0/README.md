# Native Quick Settings: one-renderer prototype

**Branch:** `feat/qs-single-native-renderer` (based on BBUI `master` at `5a0dc27`).

**Status:** Source patch only; **not installed, built, or Q25-tested**. The current Vector APK is unchanged. Do not merge this to `master` as a completed migration.

## Verified reason for this approach

The Q25's LineageOS 24 Quick Settings UI uses SystemUI's Compose tile presentation. BBUI's `BB6NativeQsPanel` is a separate Android View renderer of the same SystemUI tiles. Simply hiding or moving the stock Compose view does **not** remove the second renderer from BBUI.

The correct end state is **one SystemUI-owned native QS presentation** with a BlackBerry-specific layout and hardware navigation. It must keep Android's `TileViewModel`/`QSTile` actions, details dialogs, listening and security checks. It must replace, not cover, the stock composition.

## What this patch actually does

`patches/0001-native-qs-list-prototype.patch` modifies LineageOS 24's
`packages/SystemUI/src/com/android/systemui/qs/panels/ui/compose/infinitegrid/InfiniteGridLayout.kt`.

When `persist.sys.bbui.native_qs_list=true`, the native QS grid renders each installed tile in a single vertical row using the **same existing Android Compose `Tile` implementation**. This retains native tile controls, action handling and state listening, without creating a second QS tile UI in the ROM. The native editor remains unchanged; when the switch is false, stock layout remains unchanged.

It is deliberately a **prototype of the native rendering path**, not yet the original BBOS6 visual design: native tile styling still needs to be adapted for BB6 row height, icons, focus color and trackpad behavior.

## Apply to a matching LineageOS 24 source tree

From the LineageOS source root, after inspecting the diff:

```sh
git -C frameworks/base apply --check \
  ~/Projects/bb-ui/rom/lineage-24.0/patches/0001-native-qs-list-prototype.patch
git -C frameworks/base apply \
  ~/Projects/bb-ui/rom/lineage-24.0/patches/0001-native-qs-list-prototype.patch
```

The patch was checked structurally against the public LineageOS `lineage-24.0` source of November 5, 2026; this **does not** establish that the Q25's checked-out source matches, nor that it compiles. Test in a matching ROM build before enabling.

In a ROM containing the patch, enable the prototype with a root shell:

```sh
adb root
adb shell setprop persist.sys.bbui.native_qs_list true
adb shell am crash com.android.systemui
```

To disable, set the same property to `false` and restart SystemUI. Do **not** apply these commands to the unmodified current ROM expecting a visual change.

## Required remaining work for the actual BBUI migration

1. Integrate the BB6 QS page, date header and status-bar navigation directly into the ROM's SystemUI Compose shade; route Q25 trackpad focus and Back through the existing native controls.
2. Replace the Vector `BB6NativeQsPanel` on unlocked Quick Settings **only when** the new native QS screen owns that page. Otherwise two renderers still exist; merely applying this patch does **not** make a Vector-running BBUI single-renderer.
3. Preserve the separate three-control BB6 lockscreen policy without bypassing Android's credential or tile restrictions.
4. Validate native dialogs, third-party tiles, ordering/editing, icon/state updates, text input, accessibility, touch, keyboard, trackpad, Back, lockscreen, and repeated shade/rotation transitions on Q25. Keep the legacy presentation as an independent rollback until these pass.

**Do not classify this prototype as the completed migration.** It establishes a native visual extension point; the integration and hardware verification are still required.
