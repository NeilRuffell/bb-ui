# BB Music — standalone Android application

BB Music is an ordinary, independent Android APK (`dev.q25.bbmusic`),
built separately from the BB UI Vector/LSPosed module. It does **not** require
BB UI, Vector, LSPosed, root, SystemUI privileges or a custom ROM.

This project has its own Gradle settings, build files, manifest, application
ID and launcher. The existing BB UI module is not a build dependency.

## Current state

The first prototype UI was explicitly discarded. The launchable
`MusicActivity` is intentionally an **empty black screen**, pending
user-guided construction of the BlackBerry OS 6-style interface. No browse
list, navigation, transport bar or other previously prototyped presentation
remains.

The tested Symfonium integration is being retained separately in
`SymfoniumSource.java`. It uses only ordinary Android public APIs:

- `MediaBrowser`: connect, retrieve browseable media and paginated results.
- `MediaController`: request playback and observe native playback metadata.
- `SymfoniumSource.Listener`: return data to whichever UI is built next.

This source has no visual components. Its playback requests have not been
verified on the Q25 under the new application ID.

The previous experimental BB UI diagnostic established that a non-SystemUI
app process can connect to Symfonium and browse albums, artists, albums'
individual tracks, folders and genres. Pagination and advertised search
support were also observed. Playlists returned an empty result.

## Build and install

With global Gradle, JDK 17 and Android SDK 35:

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/standalone-bb-music
git pull --ff-only
cd music-app
gradle :app:assembleDebug
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **BB Music** from the Android launcher. The screen is intentionally
blank. The connection status is available in logcat:

```sh
adb -s 202512003630 logcat -d -s 'BBMusic:I' 'BBMusic-Source:I' '*:S'
```

## Architecture contract

```text
BB Music APK (dev.q25.bbmusic)
  MusicActivity — empty UI host awaiting guided design
  SymfoniumSource — MediaBrowser / MediaController adapter
       |
       v
  Symfonium's MediaBrowserService
       |
       v
  Symfonium's indexed OneDrive library and audio playback

BB UI APK (dev.q25.trackpad) — separate; no dependency
```

The UI will be rebuilt incrementally based on the Quick Settings list's
native Android `ListView` and `BaseAdapter` architecture where requested.
Do not copy Xposed hooks, SystemUI ownership or shade lifecycle into BB Music.
