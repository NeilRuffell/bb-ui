# BB UI

Experimental LSPosed module for BlackBerry-inspired Android UI improvements.

## v0.1 prototype: Type to search in Android Settings

On the **main Settings homepage**, pressing a letter or number on a detected **physical alphabetic keyboard** starts the existing Settings search and attempts to prefill it with that first character. No need to move focus to the search bar. Up/Down/Enter/Back and modifier shortcuts are not intercepted.

### Scope and limitations

- LSPosed scope: `com.android.settings` **and** `com.android.settings.intelligence`.
- Hooks the Settings homepage keyboard dispatcher and the separate Settings Intelligence search screen.
- Does **not** change behavior in other apps, submenus or edit fields.
- Only the initiating character is explicitly transferred; rapid additional typing during the activity transition may be missed.
- Uses AOSP/LineageOS internal class names; **not yet compiled or tested on LineageOS 24.0/Q25**.
- No ROM patching or reflashing.
- Previous Quick Settings experiment remains in source but is not activated.

### Build

Install JDK 17, Android SDK Platform 35, and compatible Gradle tooling. Then:

```sh
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

This repository currently contains no Gradle Wrapper. Use a compatible local Gradle installation.

### Enable and test

1. Open LSPosed and enable **BB UI**.
2. Scope the module to **Settings** (`com.android.settings`) and **Settings Intelligence** (`com.android.settings.intelligence`).
3. Reboot.
4. Open the top-level Settings page with no text field focused.
5. Press a hardware letter key: search should open with that character prefilled.
6. Confirm arrow-key navigation still works, and Settings submenus are unaffected.
7. If there is an issue, disable BB UI in LSPosed and reboot.

## Project status

Research prototype. Compatibility and functionality must be verified on the actual device before calling this a working release.
