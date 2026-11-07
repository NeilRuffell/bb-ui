# Symfonium MediaBrowser probe (debug only)

This investigation checks whether BB UI's **ordinary app process**, not
SystemUI, can browse the library offered by Symfonium 15.1.0. It is not a
music player and it does not change Symfonium's OneDrive integration.

## Device discovery

Observed on Zinwa Q25 with Symfonium version 15.1.0:

```text
app.symfonik.music.player/app.symfonik.core.playback.service.PlayerService
```

The service resolves for the action
`android.media.browse.MediaBrowserService`. This only proves discovery;
it does not prove that a normal third-party app may connect or browse.

## Test

The probe exists in `app/src/debug/` only and is deliberately not in
release builds. Its activity has no launcher icon; ADB starts it explicitly.

```sh
cd ~/Projects/bb-ui
git fetch origin
git switch investigate/symfonium-media-browser
git pull --ff-only

gradle :app:assembleDebug :app:testDebugUnitTest

SERIAL=202512003630
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am start -n \
  dev.q25.trackpad/dev.q25.bb7pulldown.SymfoniumBrowserProbeActivity

# Allow the on-device probe to finish, then retrieve logs.
adb -s "$SERIAL" logcat -d -s 'BBUI-BrowserProbe:I' '*:S'
```

Read the screen or the logs for:

- `CONNECTED` / `CONNECTION_FAILED`: is the ordinary BB UI app accepted?
- `root=`: did Symfonium provide a browsable root?
- `CHILDREN`: browseable/playable items and sample metadata.
- `SUBSCRIPTION_ERROR` / `TIMEOUT`: categories Symfonium did not expose.

The probe requests the first 25 entries per category with Android's
standard pagination extras. It logs at most 18 items per category and probes
at most 14 nodes, to avoid enumerating a large personal library. Item IDs
are truncated in the log. It does not play, download, edit or delete music.

## Limitations

The app may restrict browser connections based on caller identity or root
hints. A successful connection does not guarantee full access to all albums,
songs or playlists. A rejected request should be recorded as a compatibility
limit, not bypassed through hidden app storage, APK reverse engineering,
signature spoofing or private permissions.

The debug manifest adds Symfonium package visibility only to debug builds.
The original notification shade, including its media UI, is unchanged.
