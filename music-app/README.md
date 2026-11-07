# BB Music — standalone Android application

BB Music is an independent Android APK (`dev.q25.bbmusic`) that builds and
runs without BB UI, Vector, LSPosed, root, SystemUI privileges or a custom ROM.
It has its own Gradle project and launcher.

## Implementation status

**UI:** The first prototype was discarded. `MusicActivity` now contains
only a native Android `ListView` with an empty `BaseAdapter`. Its setup
mirrors `BB6NativeQsPanel`: the ListView owns native touch scrolling,
one-item D-pad/trackpad selection, and recycling. No menus, headers, music
rows, transport bar or UI styling beyond the empty dark canvas are defined.
The user will direct UI construction one element at a time.

**Sources:** `SymfoniumSource.java` remains an optional source adapter. It
uses public `MediaBrowser` and `MediaController` APIs and retains the
read-only catalog support established by the Q25 diagnostic. `MusicActivity`
does **not** automatically connect to Symfonium. The app can be opened
without it installed.

**Native player:** Not yet implemented. The required architecture is
first-party local playback using AndroidX Media3 ExoPlayer in a
`MediaSessionService`, so playback can continue in the background.
A local audio-library adapter will use Android `MediaStore`.
This is necessary to make BB Music a functional player with **no other music
app**. Do not describe the current empty UI as a working independent player.

**Cloud:** Direct OneDrive support is feasible through Microsoft Graph
with Microsoft account authorization, selected-folder enumeration, media
indexing, metadata caching and range-capable downloads. It is **not**
implemented. Symfonium can still be a user-selected provider for its
existing OneDrive-indexed catalog, but is never the mandatory player.

## Architecture target

```text
BB Music APK (dev.q25.bbmusic)
  MusicActivity
    Android ListView + BaseAdapter — user-directed BBOS6 UI
             |
         Music library / controller (to be implemented)
             |
        +----+------------------------------+
        |                                   |
  Built-in library/playback              Optional source
  MediaStore (planned)                   SymfoniumSource (retained)
  Media3 MediaSessionService (planned)   MediaBrowser/Controller
  OneDrive/Graph (later)                 Symfonium app if installed
```

The user interface must never need Vector or the BB UI APK at runtime.
Existing BB UI playback visuals can be ported into the standalone project's
resources and classes later, without linking to SystemUI code.

## Build

Requires Android SDK platform 35, JDK 17, and globally installed Gradle.

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/standalone-bb-music
git pull --ff-only
cd music-app
gradle :app:assembleDebug
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch **BB Music**. It currently shows an intentionally empty black
ListView. No Symfonium connection or playback is initiated.

## Source verification to date

On Q25, Symfonium 15.1.0 exposed a browsable `auto_root` under
`app.symfonik.music.player/app.symfonik.core.playback.service.PlayerService`.
Independent non-SystemUI diagnostics enumerated artists, albums, individual
track IDs, genres, folders, and subsequent album pages; search was advertised.
Playlists returned a `No results` placeholder. Actual playback commands,
cover-art access from the new APK and direct OneDrive access remain untested.
