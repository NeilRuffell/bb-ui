# BB Music — standalone Android application

BB Music is an ordinary Android APK (`dev.q25.bbmusic`). It does not require
BB UI, Vector, LSPosed, root, another player, a custom ROM or SystemUI access.
It has its own Gradle project under `music-app/`.

## UI and navigation

The BlackBerry OS 6-style home screen uses a native Android `ListView` and
`BaseAdapter`, with the same one-row-at-a-time hardware Up/Down navigation
and native touch scrolling model as the BB UI Quick Settings shade.
BB Music's library is always dark: its shared list background, outer
viewport and unfocused row backgrounds are pure black (`#000000`) in both
Android light and dark modes. Titles remain white, counts muted, and row
separators dark gray. **The original full-width, square-cornered blue focus
gradient is unchanged but has no outline or stroke**, including the color-selection
logic. The outer `FrameLayout.setFitsSystemWindows(true)` keeps the list
below system bars.

Home screen, in order:

1. All Songs
2. Artists
3. Albums
4. Genres
5. Playlists (not implemented yet)
6. Shuffle Songs (first-party shuffled playback)
7. Settings

**Settings** currently has exactly one action: **Add folders**.

### Original BBOS6 music menu icons

**Canonical icon reference:** [`assets/bbos6/icon-index.csv`](../assets/bbos6/icon-index.csv).
For every newly identified original BBOS6 icon, update that index in the
same change. The repository-wide rule is documented in [`AGENTS.md`](../AGENTS.md).
Do not rename original numbered PNGs or guess icon identities.

The first six home-menu rows now render the **original PNGs** from the
repository-wide `assets/bbos6/icons/` collection. They were matched
visually and by image comparison against screenshots of the BlackBerry
OS 6 Music home menu supplied for this project:

| Menu entry | Original asset | Android packaged resource |
| --- | --- | --- |
| All Songs | `350.png` | `bbos6_menu_all_songs.png` |
| Artists | `345.png` | `bbos6_menu_artists.png` |
| Albums | `351.png` | `bbos6_menu_albums.png` |
| Genres | `348.png` | `bbos6_menu_genres.png` |
| Playlists | `337.png` | `bbos6_menu_playlists.png` |
| Shuffle Songs | `339.png` | `bbos6_menu_shuffle_songs.png` |

Android requires referenced PNGs to be present in application resource
directories, so the exact original binary blobs are reused under
`music-app/app/src/main/res/drawable-nodpi/`. No artwork is redrawn or
modified. `MusicActivity.menuIconForRow` owns the menu-to-resource mapping;
`MusicListView` uses its existing left image slot to draw the selected
resource, so it does not introduce a second adapter or custom scrolling.

**Shared icon normalization:** The renderer loads each original `drawable-nodpi`
PNG once, finds the bounding rectangle of pixels with alpha greater than 16,
and creates an in-memory cropped bitmap. `FIT_CENTER` then scales that
visible region proportionally into a centered **52 × 52dp** area inside
the existing **64 × 64dp** image slot. This normalizes the apparent size
despite the original 65px/75px source dimensions and differing transparent
margins. The original PNG files remain byte-for-byte unchanged. Cached
bitmaps are reused by the shared list adapter. When a recycled row changes
from a home-menu icon to album artwork, its temporary 6dp inset is cleared;
album covers retain their full edge-to-edge 64dp presentation.

Only entries whose icons were present in the screenshot were mapped.
**Settings** remains text-only because the supplied references do not
identify its matching BlackBerry 6 icon. Album artwork on songs, albums,
artists and genres remains independent of these static home-menu icons.

**Missing album covers:** Original BBOS6 `543.png` is packaged as
`bbos6_album_no_art.png` and displayed when an **Albums** category entry
or an open album's information header has no embedded cover. Other library
artwork fallbacks and original audio files are unchanged.


### Shared list component

The list implementation is extracted into `MusicListView.java`. This class
extends Android's native `ListView` and owns one `BaseAdapter`, 64dp recycled
rows, the full-width square-cornered BBOS6 selection gradient, cached album
artwork binding, count/text presentation, and the same QS-derived Up/Down
trackpad behavior. Every menu and library page uses **the same instance**;
switching pages updates the adapter's data and preserves prior selection.

`MusicActivity.java` owns only page data, album-art *selection policy*
(which album's cover belongs to each entry), library counts, Back history,
permissions, Settings and playback routing. It supplies platform-neutral
`MusicListView.RowData` to the component through a small `DataSource`
interface. No second list, independent adapter, separate selection style or
custom touch scroll engine is introduced. The view owns its artwork cache
lifecycle and releases it when the Activity is destroyed.

The current standalone implementation still does not depend on BB UI,
Vector/LSPosed, a custom ROM, or another music app.

### Album detail header (BBOS6 Music)

Opening an entry on the **Albums** page displays an 88dp-tall black
album header above its song list, based on the supplied BBOS6 screenshot.

- Album artwork occupies an 88dp square, aligned exactly to the left and
  top of the content area; there is no extra padding against the Android
  status-bar inset or the left screen edge.
- A full-width **1dp separator in `#515251`** sits immediately below the
  88dp header content. The header's total height, including this separator,
  is 89dp. The separator is not an inset and does not overlap the artwork.
- To its right are the album title, artist, a genre line in parentheses
  (or `(Unknown)` if no genre tag was found), and the album's singular or
  plural song count.
- Art comes from **the same album-keyed, selected-folder-only thumbnail
  cache** used by regular Music list rows. Albums without embedded art show
  the existing placeholder.
- The header is displayed only on song lists reached from an album.
  Artists and Genres retain their existing list navigation.
- It sits above the `MusicListView`, not in its adapter: row indices,
  full-width blue selections, physical-keyboard search, trackpad behavior,
  playback queues and Back-stack selection remain unchanged.
- Only when an **album** is open, its song rows replace the left-side cover
  with their tagged track number. The 64dp-wide column is centered; tracks
  with no valid track-number tag show exactly `--` (two hyphens), matching
  the supplied BBOS6 reference. Regular All Songs, Artist and Genre song
  lists continue to display album artwork. Filtering an open album retains
  its number column.
- Back restores the parent album list; returning to the album restores
  its header. The header stays visible while filtering an album's songs.

The four-line layout reflects fields currently available in the
selected-folder metadata index. The third line explicitly represents
genre; the proprietary BBOS6 screenshot does not establish what its
`(Unknown)` label originally referred to.

### Physical keyboard instant search

While a music list or main menu is visible, **simply type** on the Q25's
physical keyboard. No search button, menu command or trackpad click is needed.
The first printable character reveals a **54dp BBOS6 search bar** above
the same existing `MusicListView`. Further typing filters results immediately.
Shift and ALT characters work through Android key mapping.

`MusicSearchBarView.java` provides screenshot-matched search chrome while
reusing the existing native `EditText` and input handler:

- **1dp top separator:** `#5A5D63` at the outer top edge.
- **Panel gradient:** vertical, `#4B4E53` (top) to `#292C31` (bottom).
- **Input field:** inset approximately 6dp horizontally and 6dp above,
  white with a 5dp rounded dark outer rim and a gray-to-white inset
  highlight/shadow that reaches pure white within the first ~5dp.
  Approximately 43dp tall.
- **Typed text:** dark charcoal inside the white field, approximately
  12dp internal horizontal inset.
- No hint or `Search` description appears; the Q25 physical keyboard
  makes search discoverable without prompting. No on-screen keyboard opens.

This is purely visual: search matching, Back, trackpad focus, row
dimensions, album artwork and selected-folder restrictions do not change.

- **Music home:** Search the previously indexed **selected-folder songs**
  across song title, artist, album and genre. The results are playable songs;
  the original seven-entry home menu is unchanged when search is dismissed.
- **All Songs / nested song lists:** Filter that list's songs by song title,
  artist, album or genre.
- **Artists / Albums / Genres:** Filter only the currently displayed
  artist/album/genre entries by their visible name.
- **Settings:** Filter the existing Settings entries by label.

**Back** closes the active search first and restores the previous unfiltered
selection and scroll position; a second Back performs normal page navigation.
Back from Now Playing returns to the filtered search results if playback
was started during a search. Backspace edits the query; an empty query
shows the original page entries again. Trackpad Up/Down still moves one
list row; Enter opens the highlighted result. Standard Android Ctrl/Meta
keyboard shortcuts are not intercepted.

When a non-empty query has no matches, the search results adapter remains
empty. A separate, non-focusable white text label reading
**No search results found** appears horizontally centered at the top of
the results area, beneath the search bar (and any album header). It is
not a ListView item: no row selection gradient, divider, or activation.
The label disappears as soon as results return, the query is cleared,
or search closes. The scan-in-progress message remains unchanged.

The search operates solely on the in-memory catalog returned from
`MusicFolders.selectedTrees`; typing never scans other folders,
queries MediaStore, uses the network, or requests additional permissions.
If a selected-folder scan is underway when search starts, the view
displays a scan-in-progress message and refreshes when it finishes.
The search field does not automatically invoke a touch keyboard.

### Artwork in music browse lists

The shared row height is **64dp**, increased from 55dp to better match the
relative height of the BBOS6 Music category bands in the supplied screenshot
(approximately 113px per row across 640px content width). A single
`MusicListView.ROW_HEIGHT_DP` constant sizes both the rows and square artwork,
keeping the image flush with the selection's left, top and bottom edges.

Songs, Artists, Albums and Genres now display 64dp square album covers
flush with the left, top and bottom edges of the 64dp `ListView` selection
bounds, with zero artwork or row padding. Text labels keep their own internal
inset. The existing selected-row gradient stays full-width.
Home-menu entries and Settings are left unchanged.

The cover-selection rules are **BB Music project rules**, not a verified
claim about the proprietary BlackBerry OS 6 selection algorithm:

- **Songs:** Artwork for that song's album.
- **Artists:** Artwork of the artist's first album sorted alphabetically
  by album title (case-insensitive, deterministic ties).
- **Albums:** Artwork for the displayed album itself.
- **Genres:** Artwork of the first album sorted alphabetically within
  the genre (same deterministic tie-breaks).

All tracks in an album share the same cached cover. For an album, tracks are
checked in track-number/title order until an embedded cover is found;
if none has embedded art, the row shows a small music-note placeholder.
For Artists and Genres, **do not substitute another album** if the first
alphabetical album has no embedded artwork.

`MusicListArtworkLoader` reads embedded artwork using
`MediaMetadataRetriever`, accepts only `content://` song URIs returned
from the **user-selected SAF folder trees**, and does not query MediaStore,
scan unselected folders, access the network, or use Symfonium. Thumbnails
are decoded off the UI thread with two workers, bounded to approximately
160px per side, memory cached at 12 MiB and deduplicated by album.
When a row is recycled, its artwork binding key prevents earlier
asynchronous results from being displayed on the wrong entry.

**Current limitation:** External artwork files such as `folder.jpg` or
`cover.png` are not searched; the feature reads **embedded audio artwork**
only. The source repo and historical BlackBerry 6 documentation available
during implementation do not establish a verifiable proprietary selection
algorithm for artist/genre representative artwork.


**Child-item counters:** The main menu and browsable category entries use
two-line 64dp ListView rows, matching the BBOS6 Music example: a primary
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

## Native playback and the BB UI Now Playing port

BB Music now includes its own `MusicPlaybackService` with AndroidX
Media3 1.9.4 ExoPlayer and a MediaSessionService. The independent
`MusicPlaybackClient` controls it from the Activity. Neither Symfonium
nor the BB UI Vector module is needed for local playback.

Selecting a local track creates a queue from its current song list and
opens Now Playing. The queue's source URIs are the `content://` documents
obtained from **only the selected SAF folder trees**. Back returns to the
previous library page without stopping playback. The root **Shuffle Songs**
action starts a shuffled queue of selected-folder music.

The Now Playing screen is a standalone presentation port of BB UI master
`TwoColumnPanel.java` and `UnifiedShadeHost.java`, preserving:

- 40dp mode bar with Shuffle/Repeat, enabled state and selection styling.
- Full-height half-radial artwork/metadata background and 50/50 art/text split.
- 30dp seek strip, shared dark gradient and 2dp/silver divider system,
  BBOS6 white played rail and glossy 56dp seek capsule.
- 62dp edge-to-edge three-button Previous / Play-Pause / Next toolbar;
  charcoal gradient, exact button symbols, double gradient separators
  and fixed dark-blue selected-button gradient.
- Trackpad navigation: toolbar Up -> seek -> Shuffle/Repeat; Down returns.
  Seek only changes with Left/Right **after** pressing Enter/trackpad to
  enter seek-edit mode; another press exits. Touch-dragging works directly.
- An independent `MusicArtworkLoader` reads embedded album artwork only
  from the selected track's existing document URI on a background thread.

Media3 owns audio focus, noisy-headset handling and the media-session
notification, including background playback. The Activity disconnects its
controller on Stop, but does not stop the service. Standard Android media
buttons and system controls can operate the MediaSession.

**Status:** The implementation is committed but not yet compiled or run on
the Q25. The device build will download the new Media3 dependencies.
The shade's original code is unchanged; this is a copied presentation,
not a runtime dependency. There is no automatic music-service resumption
after reboot yet, and no separate playback mini-bar in the library.

`SymfoniumSource.java` remains an unused, optional adapter. Direct OneDrive
Microsoft Graph authentication, cloud indexing and downloads are future
features and are not required for local playback.

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
   artists/albums/genres and their singular/plural labels. Group rows show
   their direct number of child songs.
9. Confirm counts finish updating without resetting trackpad selection or
   changing touch scroll position.
10. Select a track: Now Playing opens and first-party audio starts.
11. Check Play/Pause, Previous/Next, duration, drag-to-seek and trackpad
    two-stage seek mode. Verify album art when embedded artwork exists.
12. Check Shuffle, Repeat All, Repeat One and Off, using touch and trackpad.
13. Back should return to the same music list without pausing playback.
14. Background the app and use system notification/headset media controls.
15. Start Shuffle Songs from the home menu, and verify the shuffled queue.
16. Confirm tracks from unselected folders do not appear or enter a queue.
17. Verify song and album rows display their own album cover; artist and
    genre rows select the first album in alphabetical album-title order.
18. Scroll quickly with touch, then select rows using the Q25 trackpad:
    artwork must not be cross-assigned by recycled ListView rows.
    After the shared-list refactor, repeat this on the home menu, Settings,
    All Songs, Artists, Albums, Genres and their nested song pages; Back must
    still restore the previous selection and scroll position.
19. Confirm coverless albums show a placeholder and that folder selection,
    existing counts, previous navigation, status bar and Now Playing
    remain functional.
20. On the home menu type a song title, artist, album or genre directly
    using the Q25 keyboard: a search field must appear and show matching
    selected-folder songs with the correct artwork.
21. Type on the Artists, Albums, Genres and nested song lists. Verify
    only the current page is filtered; all search results retain the
    regular 64dp borderless blue selector and trackpad navigation.
22. Enter a query with no matches on the main and nested pages.
    Confirm the empty adapter leaves no selectable rows and shows
    horizontally centered `No search results found` below the search bar,
    without the blue selection gradient or a row divider. Backspace to
    recover matches or clear the query; the message must disappear.
    Press Back once to dismiss search and restore the original list
    selection/scroll; a second Back must navigate to the parent page.
23. Play a song from filtered results, press Back, and verify search
    results return intact. Ensure typing on Now Playing does not open
    the library search and the background player is unaffected.
    Compare the search field against the BBOS6 reference: full-width top
    `#5A5D63` rule, dark gray vertical panel gradient and rounded white
    inset. Verify the field begins empty (no placeholder) and typed
    characters remain readable on white.
24. Open Albums and select an album. Check the 88dp artwork touches the
    left screen edge and begins immediately beneath the status bar,
    without white space. Its title, artist, genre and song count should
    appear alongside the cover.
25. Enter the song list with trackpad Up/Down and Enter. Check selected
    tracks, playback queues, instant search and Back navigation behave
    unchanged. Artist, genre, home and Settings pages must not show an
    album header. Coverless albums must show the normal placeholder.
26. In an album song list, verify each row's left edge shows the tagged
    track number rather than album artwork. Missing/zero track tags show
    `--`. Verify all other song lists retain artwork, row selection
    gradients have no stroke, and rapid scrolling never mixes row types.

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
  Native Media3 playback -> MediaSessionService + ExoPlayer (implemented)

  Now Playing view — ported BB UI visuals and trackpad seek model
  SymfoniumSource — optional/unused until explicitly selected

BB UI APK (dev.q25.trackpad) — separate, no runtime/build dependency
```
