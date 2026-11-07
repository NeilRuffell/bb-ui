# BB Music (standalone Android application)

BB Music is an **independent, ordinary Android APK**, built separately from
the BB UI Vector/LSPosed module. It does **not** require BB UI, root,
Vector/LSPosed, a custom ROM, SystemUI privileges or notification-listener
access. All of its UI and media integration code lives in `music-app/`.

This project resides in the same Git repository for now, but has its own
Gradle `settings.gradle`, build files, Java sources, package name and launcher
Activity. Building it does not compile or install the BB UI APK.

## Build and install

Requirements: globally installed Gradle, JDK 17 and Android SDK platform 35.

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch feat/standalone-bb-music
git pull --ff-only
cd music-app
gradle :app:assembleDebug
adb -s 202512003630 install -r app/build/outputs/apk/debug/app-debug.apk
```

The independent APK package is **`dev.q25.bbmusic`**. Its launcher label is
**BB Music**. The existing BB UI package remains **`dev.q25.trackpad`**.

The starting implementation is an intentionally small **media browsing and
playback integration prototype**, not the finished BlackBerry OS 6 UI. It
includes a keyboard/trackpad-navigable list of Symfonium's actual exposed
categories; nested artist/album/track navigation; 50-item page loading; and
basic transport commands. It does not have a new audio engine. The dedicated
BBOS6 Now Playing layout will be ported in a separate step rather than
bringing SystemUI code or Vector hooks into this APK.

## Source adapter: Symfonium

Current service, observed on the Q25 with Symfonium 15.1.0:

```text
app.symfonik.music.player/app.symfonik.core.playback.service.PlayerService
```

BB Music connects using ordinary
`android.media.browse.MediaBrowser`, obtains the exported session token,
and uses `android.media.session.MediaController` to request playback.
Symfonium remains responsible for OneDrive folder indexing, streaming,
caching, decoding, audio output, library data and supported playback state.

Browsing was demonstrated in the separate diagnostic using caller
`dev.q25.trackpad` (also an ordinary app process). **The independent
package `dev.q25.bbmusic` and its playback commands have not yet been
device-tested.** Connecting and reading are not equivalent to confirming
that Symfonium honors `playFromMediaId`; verify this on device.

From the diagnostic:
- Browse root `auto_root`: Home, Recent, Library, Favorites.
- Library: Album artists, Albums, Playlists, Files, Genres, All artists,
  Internet radios.
- Artists and albums expose nested browseable entries; album tracks have
  playable media IDs like `album_song/199/0`.
- Subsequent album pages returned results.
- Root advertises `android.media.browse.SEARCH_SUPPORTED=true`. Search is
  **not implemented in this first build**.
- Playlists currently return Symfonium's `No results` sentinel.
- Album/artist/song thumbnail URIs are exposed, but artwork decoding/display
  is **not implemented in this first build**.
- No universal all-songs node was returned by the tested root.
- The diagnostic did **not** test media-controller actions.

Media browsing is provider-dependent. Other music apps can be supported
through additional public service adapters, without modifying the main UI.

## Architecture boundary

```text
Standalone BB Music APK (dev.q25.bbmusic)
  Activity + focus/list UI
       | MediaBrowser / MediaController (Android public APIs)
       v
  Symfonium's exported MediaBrowserService
       |
       v
  Symfonium-managed local / OneDrive-backed library and audio playback

Separate BB UI APK (dev.q25.trackpad)
  SystemUI shade; no runtime or build dependency in either direction.
```

No app-to-app bridge to BB UI is necessary. The visual designs can be
**ported** into this project as standalone Java/drawable resources.
Do not directly import `TwoColumnPanel`, `NativeMediaSessions`,
`PullDownHook` or any Xposed/Vector classes.

## Verification procedure

1. Install BB Music without enabling or installing the BB UI Vector module.
2. Launch BB Music from its launcher icon.
3. Open Library > Albums, verify entries, then open an album and verify tracks.
4. Load an additional album page; return to Library with physical Back.
5. Tap one playable track and verify Symfonium actually starts it. The UI
   reports that playback was **requested**, not that the command succeeded.
6. Verify Play/Pause/Next/Previous against Symfonium's active session.
7. Close BB Music: any ongoing playback must remain owned by Symfonium.
8. Verify the existing BB UI shade remains unchanged if BB UI is installed.

For diagnostic output:

```sh
adb -s 202512003630 logcat -d -s 'BBMusic:I' 'BBMusic:W' '*:S'
```

## Next work

- Confirm that media-controller commands work under the new package UID.
- Implement artwork decoding from provider-granted content URIs.
- Port BBOS6 Now Playing presentation and trackpad focus states, as **copied
  standalone assets/logic**, not runtime references to the BB UI module.
- Implement provider-supported search and source discovery, then choose how
  to represent a true All Songs view where the provider has no such node.
- Implement compatible queue/shuffle/repeat functions only where advertised.
- Add other public media sources as independent adapters.
