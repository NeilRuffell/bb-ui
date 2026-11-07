# BB Music — standalone Android application

BB Music is an ordinary Android APK (`dev.q25.bbmusic`). It does not require
BB UI, Vector, LSPosed, root, another player, a custom ROM or SystemUI access.
It has its own Gradle project under `music-app/`.

## UI and navigation

The BlackBerry OS 6-style home screen uses a native Android `ListView` and
`BaseAdapter`, with the same one-row-at-a-time hardware Up/Down navigation
and native touch scrolling model as the BB UI Quick Settings shade.
Selected rows have a full-width, square-cornered blue gradient. The outer
`FrameLayout.setFitsSystemWindows(true)` keeps the list below system bars.

Home screen, in order:

1. All Songs
2. Artists
3. Albums
4. Genres
5. Playlists (not implemented yet)
6. Shuffle Songs (requires the future built-in playback engine)
7. Settings

**Settings** currently has exactly one action: **Add folders**.

**Child-item counters:** The main menu and browsable category entries use
two-line 55dp ListView rows, matching the BBOS6 Music example: a primary
label and a smaller muted count underneath. All Songs displays the number
of selected-folder audio tracks (e.g. `1 Song`, `2 Songs`); Artists, Albums
and Genres display distinct category entries (`1 Item`, `2 Items`).
Artist/album/genre groups display their direct number of songs when opened.
Settings displays `1 Item` (Add folders); Playlists displays `0 Items`
until the app supports playlists. Shuffle Songs is an action and has no
child count.

When the app opens, it loads counts from the user-selected folder trees
only. With no folders selected the counters are zero **and no storage scan
occurs**. While a scan runs, counters read `Scanning...`, and scan failures
show `Unavailable` instead of a false zero. Adding a folder invalidates the
previous snapshot and refreshes counts. Count updates rebind visible rows
without adapter invalidation, preserving trackpad selection and scroll offset.

## Selected-folder-only device library

**Privacy/access contract:** BB Music must **not scan any music outside the
directories explicitly selected by the user**. This includes app startup:
the app does not automatically enumerate storage, and with no saved
selections there are no media queries.

- Settings > Add folders opens Android's trusted
  `Intent.ACTION_OPEN_DOCUMENT_TREE` folder picker.
- Each folder chosen grants **read-only** access to its directory and its
  subfolders using Android's Storage Access Framework (SAF).
- The app persists the returned tree URI in `MusicFolders.java`, together
  with Android's durable read grant. Selecting another folder adds it to the
  allowlist rather than replacing earlier selections.
- `LocalMusicSource.java` enumerates **only** these selected and currently
  authorized trees via `DocumentsContract` child-document queries. It never
  queries device-wide `MediaStore` tables, scans arbitrary storage roots,
  or reads files outside the selected subtrees.
- The manifest does **not** request `READ_MEDIA_AUDIO`,
  `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, network access,
  or root permissions for this feature.
- The scanner reads audio metadata inside allowed trees on a background
  thread. Results are held in an in-memory snapshot. A newly selected folder
  invalidates the snapshot, requiring a fresh scan of the updated allowlist.
- All Songs, Artists, Albums and Genres use the same recycled list rows.
  Album/artist/genre grouping uses file metadata; untagged audio remains
  visible by filename with Unknown Artist/Album/Genre labels.
- Songs retain their `content://` document URIs for the future local audio
  engine. Selecting a track does not play it yet.
- The original Symfonium adapter is preserved but never automatically
  connects; it is optional and not needed for this local library.

**Android picker restrictions:** Starting in Android 11, Android does not
allow choosing the internal storage root, certain SD card volume roots or
the `Download` directory itself. Choose a folder such as `Music`, or
individual subfolders. The selected folder includes its subfolders.
Android's DocumentsProvider must actually expose the folders for SAF access.

**Current UI limitation:** There is no Remove folders or Manage folders
command yet because the user requested only Add folders. To reset all saved
folders before a removal UI exists, Android Settings > Apps > BB Music >
Storage > Clear storage will reset the app's preferences (and other app
data). Do not imply deleting an audio folder itself.

## Native playback and cloud sources (future phases)

The local catalog currently indexes audio files, not their playback.
A self-contained player will use AndroidX Media3 ExoPlayer inside a
`MediaSessionService` to play the stored document URIs, support background
playback, transport commands, and the BBOS6 Now Playing screen. This does
not require any third-party music application.

`SymfoniumSource.java` provides an optional Android
`MediaBrowser`/`MediaController` adapter. A prior Q25 diagnostic
connected to Symfonium 15.1.0 and browsed its catalog. Optional direct
OneDrive access would require Microsoft Graph authentication, indexing,
caching and download support; none is implemented here.

## Build and test on Q25

Requires the standalone Android SDK 35, JDK 17 and globally installed Gradle.

```sh
cd ~/Projects/bb-ui
git switch feat/standalone-bb-music
git pull --ff-only
cd music-app
gradle :app:assembleDebug
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Open BB Music > All Songs **before choosing any folders**. It must not
   enumerate device music; it displays the Add folders instruction.
2. Go Back > Settings > Add folders; pick a music-containing directory and
   grant read access through Android's picker.
3. Return to All Songs. Only audio under that directory and its descendants
   should be indexed. Confirm an unrelated music folder is not listed.
4. Return to Settings > Add folders; choose a **different** music folder.
5. Return to All Songs and verify the union of the two selected folder trees.
6. Browse Artists, Albums and Genres, including physical trackpad navigation
   and Back. The blue selection and status-bar boundaries should remain
   correct.
7. Restart BB Music to confirm both folder grants persist.
8. Confirm main-menu counters show the selected-folder track count, distinct
   artists/albums/genres and their proper singular/plural labels. The artist,
   album and genre group rows should display the number of child songs.
9. Confirm counts finish updating without moving the selected trackpad row
   or altering touch scroll position.
10. Selecting an individual track still reports playback not implemented.

For relevant logs:

```sh
adb -s 202512003630 logcat -d -s 'BBMusic:I' 'BBMusic-Local:I' 'BBMusic-Local:W' '*:S'
```

## Architecture

```text
BB Music APK (dev.q25.bbmusic)
  MusicActivity
    ListView + BaseAdapter, user-guided BBOS6 presentation
    Settings > Add folders -> Android ACTION_OPEN_DOCUMENT_TREE
        |
        v
  MusicFolders
    Explicit persisted folder-tree allowlist
        |
        v
  LocalMusicSource
    DocumentsContract enumeration, selected trees only
    Audio metadata -> songs/artists/albums/genres
        |
        v
  Native Media3 playback (not implemented yet)

  SymfoniumSource — optional/unused until explicitly selected

BB UI APK (dev.q25.trackpad) — separate, no runtime/build dependency
```
