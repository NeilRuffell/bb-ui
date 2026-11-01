# BB UI

Experimental LSPosed module for BlackBerry-inspired Android SystemUI improvements.

## Initial feature: trackpad focus for Quick Settings

The first prototype hooks `com.android.systemui` to mark known Quick Settings tile view classes focusable and enable Android's default focus highlight.

This **does not** implement trackpad gesture-to-DPAD conversion or guarantee navigation on LineageOS 24.0. It has not yet been tested on the Q25 device.

## Build

Requirements: JDK 17, Android SDK Platform 35, Gradle 8.11.1 or compatible Gradle 8.x.

```bash
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Testing

1. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Enable **BB UI** in LSPosed.
3. Scope only to **System UI** (`com.android.systemui`).
4. Reboot and test the Quick Settings focus highlight and activation.
5. If unstable, disable the module in LSPosed and reboot.

## Status

- Experimental, unverified against LineageOS 24.0.
- Uses the legacy Xposed API (82); requires a compatible LSPosed implementation.
- No lock screen, authentication UI, or ROM modifications.
- SystemUI class names can change across Android releases.
- Additional UI features are not implemented yet.
