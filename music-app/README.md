# BB Music — standalone Android application

BB Music is an independent Android APK (`dev.q25.bbmusic`) that builds and
runs without BB UI, Vector, LSPosed, root, SystemUI privileges or a custom ROM.
It has its own Gradle project and launcher.

## Implementation status

**UI:** The initial browsing prototype was discarded. `MusicActivity` now
contains only the BBOS6 Music home menu, in this order:

1. All Songs
2. Artists
3. Albums
4. Genres
5. Playlists
6. Shuffle Songs

The six rows use a native Android `ListView` and fixed `BaseAdapter`. This
mirrors the proven QS shade approach: one owner for touch scrolling and
hardware Up/Down movement, row recycling, visible-row focus styling, and a
ported stand-alone trackpad press handler. Rows are 55dp tall, use the QS
light/dark palette and focus gradient, and contain text only until additional
UI design is specified. Pressing a row presently logs its name; it does
not navigate or shuffle because those actions are not yet designed.
No custom scrolling behavior, other music menus, or transport controls
are included.

**System bars:** BB Music targets SDK 35 and retains a persistent native
status bar. The first custom `WindowInsets` listener/window-controller change
was reported to crash on device. Until the exception is captured, it has been
replaced with a simpler outer `FrameLayout.setFitsSystemWindows(true)` that
reserves system-window insets for the inner ListView. No hardcoded status bar
height or custom scrolling behavior is used. Confirm the result on Q25; if
the app still crashes, collect the Android crash stack trace rather than
guessing the exception.

**Local device library (implemented):** `LocalMusicSource.java` reads indexed
music on attached device storage through Android's public `MediaStore`
content provider, with a background single-threaded scanner and immutable
song metadata snapshot. It uses the normal Android 13+ runtime permission
`READ_MEDIA_AUDIO`; no root, Vector, network access, file-system-wide access
or other music player is required.

On selecting one of the first four home menu entries, the Activity requests
permission if necessary and loads the local catalog asynchronously:

- **All Songs:** sorted indexed song titles, retaining MediaStore content URIs.
- **Artists:** grouped artists; selecting one shows its indexed songs.
- **Albums:** grouped by MediaStore album ID; selecting one shows songs ordered
  by the stored track number.
- **Genres:** grouped by the indexed genre metadata; selecting one shows songs.

The same `ListView` rows, blue selection, native trackpad handling and
system-bar-safe root container are used on these basic browse screens. System
Back returns to the previous category using Android's Back callback. This is
only functional scaffolding for the user-directed BBOS6 UI; no new music
header, album artwork, submenus, transport bar or media playback has been
introduced. `Playlists` and `Shuffle Songs` remain unimplemented. Android's
legacy MediaStore playlists API is deprecated since API 31; a future local
playlist system should use the app's own database or supported M3U import.
Audio with `IS_MUSIC != 0` and indexed by Android's media provider is included;
other app-private or cloud-only music is not exposed by this local source.
The in-memory snapshot refreshes on the next app launch, not automatically
whenever device storage changes.

**Sources:** `SymfoniumSource.java` remains an optional source adapter. It
uses public `MediaBrowser` and `MediaController` APIs and retains the
read-only catalog support established by the Q25 diagnostic. `MusicActivity`
does **not** automatically connect to Symfonium. The app can be opened
without it installed.

**Native player:** Not yet implemented. The required architecture is
first-party local playback using AndroidX Media3 ExoPlayer in a
`MediaSessionService`, so playback can continue in the background.
The Android `MediaStore` library adapter is implemented, but its metadata
catalog alone does **not** play audio. Media3 and the background session are
still needed to make BB Music an independent player. Do not describe the current empty UI as a working independent player.

**Cloud:** Direct OneDrive support is feasible through Microsoft Graph
with Microsoft account authorization, selected-folder enumeration, media
indexing, metadata caching and range-capable downloads. It is **not**
implemented. Symfonium can still be a user-selected provider for its
existing OneDrive-indexed catalog, but is never the mandatory player.

## Architecture target

```text
BB Music APK (dev.q25.bbmusic)
  MusicActivity
    Android ListView + BaseAdapter — six-entry BBOS6 Music home menu
             |
         Music library / controller (to be implemented)
             |
        +----+------------------------------+
        |                                   |
  Built-in library/playback              Optional source
  MediaStore (implemented)                   SymfoniumSource (retained)
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

Launch **BB Music**. It displays the six-entry BBOS6 Music home menu.
Choose All Songs, Artists, Albums or Genres. Android will request the music
permission the first time. The browse list then displays indexed local audio
and supports Up/Down, center/Enter and Back. Touch scrolling remains native.
Selecting an individual song does not play it yet. No Symfonium connection is
initiated.

## Source verification to date

On Q25, Symfonium 15.1.0 exposed a browsable `auto_root` under
`app.symfonik.music.player/app.symfonik.core.playback.service.PlayerService`.
Independent non-SystemUI diagnostics enumerated artists, albums, individual
track IDs, genres, folders, and subsequent album pages; search was advertised.
Playlists returned a `No results` placeholder. Actual playback commands,
cover-art access from the new APK and direct OneDrive access remain untested.

## Verification on Q25

1. Install this branch and open BB Music.
2. Select **All Songs**. Grant the Android **Music and audio** permission.
3. Check that local files indexed by the OS appear.
4. Use the trackpad to select successive rows; scroll with touch.
5. Press Back to return to the same six-entry home menu.
6. Open Artists, Albums or Genres, then one grouped entry.
7. Select a track: the UI should explicitly say playback is not implemented.
8. If no local audio is indexed, expect the `No local music found` row.

Logcat:

```sh
adb -s 202512003630 logcat -d -s 'BBMusic:I' 'BBMusic-Local:I' 'BBMusic-Local:W' '*:S'
```

No `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, notification-listener,
root, Vector or SystemUI permission is needed.
