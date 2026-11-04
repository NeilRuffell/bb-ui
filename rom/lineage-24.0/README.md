# Native BBOS6 keyguard for LineageOS 24 (Zinwa Q25)

**Development branch:** `feat/bb6-native-lineage24`. This is native ROM
source code, **not** an Android APK or an LSPosed/Vector module. It has not
been merged into `master` or installed on a Q25 ROM image.

Official sources:
- [LineageOS Q25 build guide](https://lineageos.github.io/lineage_wiki/devices/Q25/build/)
- [LineageOS 24 SystemUI](https://github.com/LineageOS/android_frameworks_base/tree/lineage-24.0/packages/SystemUI)
- [Official Q25 device tree](https://github.com/LineageOS/android_device_xelex_Q25)

## Native design

`Bb6KeyguardBlueprint` is a real SystemUI `KeyguardBlueprint`, selected
**only** for awake, interactive, unoccluded, non-bouncer `KEYGUARD`.
The existing `DefaultKeyguardBlueprint` is selected for screen-off/AOD,
locked/unlocked shades, primary PIN/password/pattern bouncer and other modes.

On the interactive BB6 keyguard, the stock `ClockSection`,
`SmartspaceSection` (date), `KeyguardSliceViewSection`, native
notification icons, promoted notification row, and notification stack
placeholder are **absent from the blueprint's section list**. They are
not constructed or bound for that presentation. We do not make them
transparent, set them to `GONE`, or detach live views behind SystemUI's back.

The BB6 header shows date and time in a compact row with application icons
followed immediately by the number of active Android notifications.
`Bb6HeaderSection` listens directly to `NotifPipeline`. It counts posted
records (not unread emails/messages), suppresses duplicate group summaries
and excludes records prohibited by native lock-screen privacy/ranking
settings. It shows no content, sender or message preview.

**Only one TELUS/carrier label** is intended: the existing Android
`KeyguardStatusBarView` owns the operator, system status, signal, battery
and privacy indicators. `Bb6HeaderSection` intentionally contains no
carrier text. This prevents duplicate labels without breaking native
status icons.

The native AOD sentinel section remains in **both blueprints**. When the
default clock or Smartspace section is removed in a blueprint transition,
their corresponding burn-in `Layer` references are cleaned up first.
This explicitly addresses the `AodBurnInLayer.transform()` crash
found in the earlier, non-native 0.9.40 prototype.

### Known scope limits

- The first native blueprint does **not yet restore the optional Smartspace
  weather/alarm/DND cards** as a separate BB6 section. Their shared stock
  date/weather section cannot be reused unmodified without reintroducing
  the duplicate date and references to the stock clock.
- The full source image has **not** been compiled or tested on the phone.
  CI currently confirms patch applicability and source-level invariants only.
- The existing, Q25-tested Space-to-password Vector code is unchanged.
  Native keyboard routing is a separate ROM-integration task; no custom
  authentication implementation is introduced by this patch.
- The notification stack is still a shared SystemUI view used for the shade.
  This patch removes its **keyguard section/placeholder** only. Confirm
  the native shade still renders and that no residual container appears
  during Q25 device testing.

## Apply to an official LineageOS 24 source checkout

A complete LineageOS 24 tree is needed. Official guide:

```sh
mkdir -p ~/android/lineage
cd ~/android/lineage
repo init -u https://github.com/LineageOS/android.git -b lineage-24.0 --git-lfs --no-clone-bundle
repo sync
```

If a checkout already exists, **do not reinitialize or overwrite it**.
To validate and apply only the SystemUI changes:

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/bb6-native-lineage24
git pull --ff-only

python3 rom/lineage-24.0/apply_native.py --check ~/android/lineage
python3 rom/lineage-24.0/apply_native.py ~/android/lineage
```

The patcher checks exact upstream anchors before writing anything. It is
idempotent and fails if the source has drifted; it does not modify device
trees, partitions or firmware.

Review modifications in the Lineage checkout:

```sh
cd ~/android/lineage/frameworks/base
git diff -- packages/SystemUI/src/com/android/systemui/keyguard
git status --short
```

Compile via the [official Q25 build instructions](https://lineageos.github.io/lineage_wiki/devices/Q25/build/).
A source patch alone does **not** update the phone. Do not flash a test ROM
until complete build and recovery procedures are reviewed independently.

## Verification checklist

- One time and date, one carrier label, real app icons plus counts
- No native lock-screen notification cards or reserved blank frame
- No stock large clock, native date or duplicate AOD icon row while awake
- Standard native clock and AOD icons after display off
- Native status bar, battery, signal, privacy and emergency access
- Native PIN/password/pattern entry and hardware Space shortcut
- Native notification shade works locked/unlocked, with actions
- Lock/unlock and screen on/off cycles without any SystemUI restart
- Privacy and user-profile notification visibility
- Doze, weather/alarm/DND (when the separate optional section is implemented)

**No merge into `master` before a Q25 ROM build and device tests.**
