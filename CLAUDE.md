# CLAUDE.md

Seiðr (ASCII: Seidr): a native Android player for one Navidrome server. Its
behaviour and layout follow classic (2016–2019) Spotify, but never its name,
logo, green, icons or code. Read "Principles" and "Rules that must hold"
before changing sync or playback.

## Principles

1. **Offline mirrors the server.** Tracks added to an offline playlist on the
   server download automatically; removed ones are deleted unless something
   else still claims them. Correctness never depends on a manual sync button.
2. **The server is the truth.** No local playlist copies, no import step, no
   offline-first mode. After an edit, re-fetch and reconcile.
3. **Full playlist management:** create, rename, add, remove, reorder and
   delete, written straight to the server.
4. **Local files first:** a downloaded track plays from disk, even online.
5. **Reliable playback:** gapless, lockscreen and notification controls, and
   background playback that survives OEM battery managers.
6. **Originals:** downloads are the original files (mostly FLAC), never
   transcoded by default.
7. **No Google Play services.** Distributed as an APK from GitHub Releases.

## Architecture (single `:app` module, package `eu.bradshaw.seidr`)

- **`Graph`**: hand-wired singletons, one per process (`Graph.get(context)`).
  No DI framework.
- **`data/api`**: Retrofit `SubsonicApi` and `SubsonicClient`. The client has
  two API instances:
  - `api` (lenient) is for screens. On a network failure it serves the
    last-seen response from `ResponseStore` (files in `filesDir/api`).
  - `strict` is for sync, edits, stars and scrobbles. It never falls back.

  Auth, the base URL and `f=json` are added by an interceptor; the Retrofit
  base URL is a placeholder. Writes are POST forms (OpenSubsonic `formPost`).
  Cover-art URLs use a fixed per-install salt, so image caches can key on them.
- **`data/db`**: Room. `offline_source` → `claim` → `download` (the claims
  model), plus `pending_edit` and `pending_scrobble`.
- **`sync/`**:
  - `SyncPlanner` (pure) and `SyncEngine` (pure apart from its interfaces)
    hold every rule.
  - `SubsonicSyncServer` and `RoomSyncStore` adapt them to the app.
  - `SyncCoordinator` owns the triggers and WorkManager: periodic sync every
    45 min, retry on reconnect, and the downloads queue.
  - `DownloadWorker` downloads with Range resume, pauses on low storage, and
    re-checks claims under `engine.lock` before committing a file.
  - `PlaylistEditor` handles all playlist writes. Pure helpers live in
    `EditResolver`.
- **`playback/`**:
  - `PlaybackService` is a `MediaSessionService` around ExoPlayer. Items use
    `seidr://song/<id>` URIs, which a `ResolvingDataSource` maps to the
    local file if one exists, else to `stream.view?format=raw`. The service
    also scrobbles, persists the queue to `queue.json`, and puts a like
    button in the notification. The session drives a `FadingPlayer`
    (a `ForwardingPlayer`), so play, pause and skips fade from every
    controller; the service's own calls use the ExoPlayer directly. It's a
    `MediaLibraryService`: `LibraryTree` is the Android Auto browse tree
    (tabs Recent / Playlists; playlists and albums play on tap
    rather than open). Car lists come from memory or `ResponseStore` at once
    and refresh behind; never make the car wait on the network.
    `CoverProvider` serves cover art as `content://` URIs, for browse items
    and queue items alike (the car can't load http or private file URIs).
    On TV, `PublicArt` swaps queue art for Navidrome's public signed cover
    links (`getAlbumInfo2`), so Cast/Home Assistant can show it.
  - `PlayerConnection` is the UI-side `MediaController` and implements the
    queue model: `[history][current][user "Next in queue"][context]`.
    Shuffle reorders the timeline itself and is undone via
    `QueueEntry.ctxIndex`, so ExoPlayer's shuffle is never used.
- **`data/LyricsOffsets`**: per-song lyrics timing nudges. Subsonic has no
  app-data storage, so each is a bookmark on the song (position 0, comment
  `Seidr lyrics offset: +650ms`), synced every pass. Comment-less bookmarks
  (resume points) may be replaced; any other comment is never touched.
- **`data/BadReports`** (opt-in, Settings → Reporting): reports songs to the
  server's `Bad: Songs` / `Bad: Art` / `Bad: Lyrics` playlists (created if
  two strict fetches agree one is missing), for whoever looks after the
  server to fix and remove. `Library.playlists` hides every "Bad:" playlist (`allPlaylists`
  doesn't). `PlaybackService` hides `Bad: Songs` entries from the queue and
  puts them back in place when they leave that list (`HiddenQueue`, saved in
  `queue.json`). The server's lists are the truth, plus unconfirmed edits (in
  flight or in `pending_edit`).
- **`data/Kids`** (kid mode): a `ServerConfig` with `kidOf` is a kid's
  login, set only through Connect sign-in (a grown-up's device keeps kids'
  logins: Settings → Kids; Sign in a device asks who). `KidRules` (pure) come
  from the public playlists `Seidr: Kids Approved` / `Safe Titles` /
  `Safe Art`, trusted only when owned by `kidOf` (created, public, when a
  kid is added; hidden from everyone's browsing); missing counts as empty,
  so it fails closed. LiveEvents edits to them trigger a sync, which
  refetches them. Approved plays; Safe Titles/Art show disabled
  (`Song.locked`) with just that; anything else is hidden. `Library` filters
  everything it returns; `PlaybackService` hides unapproved queue entries
  and its data source refuses them; `SyncEngine`'s `TrackGate` claims only
  approved tracks, from lists fetched strictly that pass. Kid logins travel
  in `SignIn`/`Guest` (`kidOf`), sent only to hosts of `KIDS_VERSION`+.
- **`data/LiveEvents`**: while the app is on screen, listens to Navidrome's
  native `/api/events` stream (JWT from `/auth/login`). Events only trigger
  sync or screen reloads; on any failure it backs off and polling carries on.
- **`connect/`**: Connect (LAN only). Each install advertises `_seidr._tcp`
  over mDNS. Signed in, it's tagged from `ConnectKeys.account` (username +
  password, not the URL) and frames are AES-GCM under a per-connection key
  from it. `ConnectSession` (in `PlaybackService`) both hosts this device's
  player for controllers and, while controlling another device, swaps a
  `RemotePlayer` (a `SimpleBasePlayer` sending `RemoteOp`s) into the media
  session, so app, notification and volume keys drive the remote. One device
  plays: moving playback releases the old one, and its other controllers get
  `Moved`. A dropped link retries, then falls back here, paused; `Ended`
  means don't retry. Picker: `ui/player/DevicesDialog`.
  - **Guests**: signed out, a device advertises `g=1`, and anyone signed in
    can play on it or sign it in (Settings → Sign in a device). The channel
    keys from ECDH against the host's long-term key (`Connect.identity`) with
    a committed nonce exchange, and both screens show a six-digit code
    (`ConnectKeys.code`). The controller pins the key once the user confirms
    a match; signing in always shows the code again. Then it sends `Guest`
    (play with this login, held only in `Graph.guest`, which the client falls
    back to; sync never uses it) or `SignIn` (the host pings the server, then
    saves it; `data/Account`). Another user's `Guest` takes the device over.
    The guest ends on Disconnect, when playback moves away, or on sign-in.
    Its queue isn't saved and its scrobbles aren't queued for retry.
  - Signed out, the app is `ui/screens/SignedOutScreen`: an idle Now Playing
    with Log in, or a locked Now Playing (read-only queue, lyrics,
    visualiser) in the sender's colour (`portableTheme`).
- **`update/`**: self-update from GitHub Releases. `Updater` reads the latest
  release's `version.json` on resume (30 min throttle; off via Settings →
  About), downloads the APK it names, verifies sha256 and offers a dialog.
  Check for updates installs straight away. Builds without the release
  keystore (debug, forks) have an empty `UPDATE_URL` and never self-update.
- **`vis/`**: the MilkDrop visualiser (Now Playing's middle icon).
  - `VisEngine` drives libprojectM through `ProjectM` (JNI,
    `src/main/cpp/milkdrop_jni.cpp`): preset choice, the tuner, heavy presets.
    Presets change every 30 s or on a hard cut. projectM always draws to the
    default framebuffer.
  - `VisualiserActivity` (its own activity, full screen) runs it on a
    `GLSurfaceView`, paced to 60 fps by deadlines, not by time since the last
    frame (presets step per frame, so faster runs them fast; the Quest's
    panels are 72 Hz).
  - `VisOverlay` (Compose, over the GL surface; no frame cost): track card;
    playback by gesture (tap/select plays/pauses, sideways skips, each with
    a passing sign). Up/down turn through `VisPage`s (preset flag and heart,
    transport, track, lyrics, queue, logo; none at first); sideways or Back
    puts one away; pages never time out. They sit centred with no panels,
    just shadowed white text, and reuse Now Playing's pieces via a stand-in
    `AppActions`; the logo page is `LogoSprite`, drawn by `VisEngine` after
    projectM. The close cross shows only on a headset. `VisWarning` (flashing
    lights) shows once per device for each mode, from Now Playing's buttons.
    The first preset is a hard cut, so projectM's idle logo never shows.
    `VisPrefs` (in
    `Graph`) holds its settings and loved/hidden presets (per user, by
    `Graph.user`, so a guest's come back when they sign in) and the tuner's
    memory (per device); Settings → Visualiser
    (`VisualiserSettingsScreen`) lists them, previewing via
    `VisualiserActivity.preview`.
  - `TapSink` wraps ExoPlayer's audio sink and copies decoded PCM, with
    timestamps, into `VisAudio`, which hands the renderer what's *audible*
    (decoding runs ~0.5 s ahead). Never enable audio offload: the tap goes
    silent.
  - `Autotune` holds the frame rate by trading quality live (a finer
    mesh, then transitions, then render scale); it's pure and unit-tested. Per device it
    remembers its usual level, each preset's level, the presets too heavy to
    show, and a 30 fps last resort. A new preset starts at the median of
    where recent ones settled.
  - `xr/` (Quest only, opened from Now Playing beside the visualiser):
    `XrVisualiserActivity` is an immersive OpenXR activity in its own task
    (`src/main/cpp/xr_jni.cpp`, library `seidrxr`). It renders the same
    `VisEngine` into a pbuffer and copies each frame to an equirect2 layer
    (150° x 100°, at infinity). Controller input comes through OpenXR actions.
    `PlaybackService.onTaskRemoved` ignores that task.
  - Assets: the Cream of the Crop presets and texture pack under
    `assets/milkdrop`, indexed by `presets.txt` (`MilkdropAssetsTest` keeps it
    in step). Credits are in `README.md`.
- **`ui/`**:
  - Compose with Material 3, always dark. `ThemeChoice` (Settings → Colour)
    is System (Material You), Neutral (the original near-black look) or a
    seeded colour; `Palette.kt` builds tonal palettes in CIELAB LCh, so no
    material-color-utilities.
  - Now Playing overrides the theme with a scheme from the current cover
    (`ArtPalette.kt`; not under Neutral). Its thresholds were tuned on the
    real library, so check changes against many covers, not one.
  - Navigation is our own `Navigator` (a back stack per tab; each `Entry` owns
    a `ViewModelStore`), not navigation-compose.
  - `MainScreen` switches layouts at 600dp. Narrow: screens scroll under a
    floating slim nav bar, so scrolling screens end with
    `bottomContentPadding()` (`LocalBottomChrome`). Wide (Fold inner screen,
    TV): rail with Settings at its foot, rounded list + detail panes (nothing
    overlaps, so remote focus never hides). No mini player: both bar and rail
    end their tabs with a Now Playing item. Now Playing/Queue are full-screen
    on both. Search and Library
    use `TabHeader` (title, Offline pill, actions; on narrow, Library's
    actions end with the Settings cog). Home has no header.
  - `AppActions` (via `LocalActions`) is how screens navigate, play, toast
    and open the add-to-playlist dialog.
  - Android TV (leanback launcher, banner) is driven by remote; `ui/Focus.kt`
    holds the D-pad rules. Overlays trap focus (`overlayFocus`), screens open
    on their `primaryFocus` and Back returns via `rememberedFocus`, text
    fields use `selectToEdit`, filled buttons get a `focusRing`. A focusable
    must not sit inside another clickable (arrow keys can't reach it), and
    any custom gesture needs a key equivalent. `LocalIsTv` drops downloads,
    pull-to-refresh and battery settings on TV.

## Rules that must hold

- **Deletion only follows a complete, trusted fetch.**
  `SyncPlanner.isTrustworthy` checks that counts match, and an empty list
  needs a second signal before it's believed. Not-found must be confirmed:
  a playlist needs both the listing and a direct fetch; an album needs two
  fetches. Never let the lenient client or `ResponseStore` feed sync.
- Files are deleted only in `SyncEngine.filePass`, and only for tracks with
  zero claims that aren't currently playing (`NowPlaying.currentSongId`).
- Subsonic has no reorder call. Reorder sends the whole list, and only when
  a fresh fetch equals what the user saw (`EditResolver.reorder`); otherwise
  it's a `Conflict`. Removes resolve the index by song id against a fresh
  fetch. Never rewrite a whole playlist for any other edit.
- A playlist whose tracks are all downloaded by other sources shows as
  downloaded (badge, Downloaded filter) but its Download toggle stays off:
  the toggle means "I asked for this". `Library.warmPlaylists` keeps every
  playlist's track list in the display cache (Wi-Fi only) so it opens offline.
- Offline edits (add, remove, rename) are queued as intents and replayed
  first on each sync pass. Create, delete and reorder need the server.
- Ask before adding a library beyond the core stack (Compose/Material 3,
  Media3, Retrofit/OkHttp, kotlinx.serialization, Room, WorkManager, Coil).
  Extras so far:
  `material-icons-extended`, `lifecycle-process` (both AndroidX), the
  Khronos OpenXR loader (Quest visualiser, via prefab) and libprojectM (LGPL-2.1, vendored unmodified in
  `src/main/cpp/projectm` and shipped as its own `.so`; keep it that way).

## Sync edge cases (each one a `SyncEngineTest` case)

| Case | Behaviour |
| --- | --- |
| Track removed from a playlist but in an offline album | Stays: the album still claims it |
| Playlist deleted on the server | Source removed, claims dropped, unclaimed files deleted |
| Track deleted from the server library | Claims dropped, file deleted |
| Removed track is currently playing | Finishes playing, then gets deleted |
| Storage full | Downloads pause with a notification; claimed files are never evicted |
| Server unreachable | Nothing is deleted; playback continues from local files |

## Build, test, release

- Versions: AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, KSP 2.3.13,
  compileSdk/targetSdk 37, minSdk 34. Native: NDK 30.0.16248370 and CMake
  4.1.2 (in the SDK), ABIs arm64-v8a and armeabi-v7a (some TVs are 32-bit).
  Native builds are incremental (`app/.cxx`); a clean one adds ~25 s.
- Tests: `./gradlew :app:testDebugUnitTest`. `LiveServerTest` runs against a
  real server only when `~/.config/seidr/test-server.properties` exists; with
  `allowWrites=true` it creates, edits and deletes a scratch playlist.
- Version: `seidr.version` in `gradle.properties` (major.minor.patch);
  versionCode is derived from it. `seidr.repo` names the GitHub repo that
  releases and self-updates come from.
- Signing: `~/.config/seidr/release.jks` plus `signing.properties`, outside
  the repo. Without them release builds are unsigned. The debug build has the
  `.debug` suffix and installs alongside the release build.
- Release: bump `seidr.version`, commit on main, run `scripts/release.sh`. It
  builds `:app:dist` (signed APK + `version.json` in `build/dist/`), pushes,
  and publishes a GitHub release with both files; the tag is `v<version>`.
  It needs a token with Contents read/write (`GITHUB_TOKEN` or
  `~/.config/seidr/github-token`).
- Debug builds only: `scripts/debug-account.sh` signs in or adds a kid
  without typing (adb text input drops characters in password fields).
- Installing over adb: `adb install -r -i eu.bradshaw.seidr <apk>`. The `-i`
  makes the app its own installer of record, so later self-updates skip the
  confirmation prompt.
- Android 17 devices: `adb install -r` kills the app; `uiautomator dump`
  fails while playback is animating, so use screenshots.

## Gotchas

- Android 17: a LAN server address (e.g. 192.168.1.20) needs the runtime
  `ACCESS_LOCAL_NETWORK` permission. Login asks for it before the first ping,
  and first run asks too. Tailscale addresses don't need it.
  Without it, any mDNS use pops a system device picker, so Connect neither
  advertises nor discovers until it's granted and someone is signed in
  (`Connect.lanAllowed`, `onLanGranted`).
- Android Auto only shows sideloaded apps with "Unknown sources" enabled in
  Android Auto's developer settings.
- Samsung themes can leave the Android 14 role colours (`system_primary_dark`,
  which `dynamicDarkColorScheme` reads) out of step with the tonal palettes
  (`system_accent1_*`); a Galaxy lavender theme gives blue roles. The System
  theme therefore reads the palettes. Check with
  `adb shell cmd overlay lookup android android:color/<name>`.
- Navidrome smart playlists (`readonly`) bump `changed` on every read, so
  they're fully fetched on every pass. That's correct, just not cheap.
- `getPlaylists` song counts can differ from `getPlaylist` (missing files).
  Completeness is checked against the full fetch's own `songCount`.
