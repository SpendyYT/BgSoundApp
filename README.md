# Background Sounds

Minimal offline Android app for playing looping background sounds (rain, forest,
campfire, ...), built per the attached spec.

## Changelog (fixes after first crash report)

1. **Fixed the `ForegroundServiceDidNotStartInTimeException` crash.** The
   service used to rely entirely on Media3's "auto-promote to foreground when
   a player event fires" mechanism. If the requested sound was already
   playing, no event fired, `startForeground()` never got called, and Android
   killed the process a few seconds later. `PlaybackService.onStartCommand`
   now calls `startForeground()` synchronously and unconditionally, every
   time, before doing anything else.
2. **Fixed the `Player is accessed on the wrong thread` crash.** The
   service's coroutine scope was `CoroutineScope(SupervisorJob())`, which
   defaults to `Dispatchers.Default` - a background thread pool. Any
   `launch { }` off that scope that touched the player (which `togglePlayPause`
   did) crashed. It's now `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`,
   with only the actual disk write (`settingsRepository.setLastSoundId`) explicitly
   pushed to `Dispatchers.IO`.
3. **Shade / lock-screen controls now actually work.** This was really the same
   root cause as #1: the "automatic" notification never reliably appeared, so
   there was nothing for the shade to show. `PlaybackService` now builds its
   own `NotificationCompat` + `MediaStyleNotificationHelper.MediaStyle`
   notification tied to the `MediaSession`, with a working play/pause action,
   and keeps it updated on every relevant player event.
4. **Playback progress bar.** `PlaybackService` now runs a ~500ms ticker while
   playing and publishes `positionMs`/`durationMs` into `PlaybackStateHolder`;
   `MainActivity` renders it as a plain animated `LinearProgressIndicator`
   under the play/pause button - no timestamps, just a bar, as requested.
5. **Second Quick Settings tile for picking a sound.** Android does not let a
   third-party app render its own expandable list *inline inside the shade*
   the way system tiles like Wi-Fi do - that's a System UI-only capability,
   not something exposed to regular apps, and the long-press-to-"App info"
   behavior you saw is standard Android for any non-system tile, not something
   this app can override. The closest legitimate equivalent: a **second,
   independent tile ("Pick Sound")** that on tap opens a small floating dialog
   (`SoundPickerActivity`) listing all sounds and collapses the shade
   (`startActivityAndCollapse`); tapping a sound in it starts that sound and
   the dialog closes itself immediately. The original tile keeps its simple
   job: tap toggles play/pause of the last selected sound. Add either or both
   tiles to your Quick Settings.
6. **Sleep timer.** Tap the clock icon in the bottom bar to pick 15/30/45/60/90
   minutes; it shows a live mm:ss countdown (also reflected in the
   notification's subtitle), and firing it does a *full* stop, not just
   pause - matches "let it fall asleep on its own." Tap the countdown again to
   change it or turn it off.
7. **Background footprint fixed.** Previously the service stayed pinned as an
   "active foreground service" (and its notification stayed "ongoing")
   indefinitely once started, even after you paused - which is what looked
   like "the app is still running" in the shade. Now:
   - Pausing demotes the service out of the foreground state right away
     (`stopForeground(STOP_FOREGROUND_DETACH)`); the notification stays but is
     dismissible.
   - If it then sits paused for 10 minutes with nothing happening, the
     service fully stops itself and the notification disappears.
   - Swiping the app away from Recents while nothing is playing stops the
     service immediately too (`onTaskRemoved`).
   - The notification now also has an explicit **Stop** action (not just
     Pause), which fully releases the player instead of just pausing.
8. **Status bar color.** It now follows the app's theme background
   (`MaterialTheme.colorScheme.background`) instead of the OS default, with
   light/dark status-bar icons switched automatically to stay legible.

## What's inside

- **Kotlin + Jetpack Compose** UI — one screen: list of sounds + a play/pause bar.
- **AndroidX Media3 / ExoPlayer**, driven from a **foreground `MediaSessionService`**
  (`playback/PlaybackService.kt`). The Activity never touches the player directly —
  it just sends intents (`ACTION_PLAY_SOUND`, `ACTION_TOGGLE_PLAY_PAUSE`).
- **Gapless loop**: `player.repeatMode = Player.REPEAT_MODE_ONE`, Media3's built-in
  infinite-repeat of the current item. No custom timers. If a specific file turns
  out to click at the loop point, that's the one spot to add a short overlap/crossfade —
  intentionally not done up front, per the spec.
- **System media notification / lock screen controls**: automatic, via the
  `MediaSession` — Media3 builds and manages the notification itself.
- **Quick Settings Tile** (`tile/SoundQsTileService.kt`): tap toggles the last
  selected sound on/off. Long-pressing the tile opens the app (standard Android
  behavior), which doubles as the "sound picker screen" from section 8 of the spec.
- **DataStore** (`data/SettingsRepository.kt`): remembers only the last selected
  sound id.
- **Sound list built from assets**: `data/SoundCatalog.kt` scans
  `assets/bgmusic/*_audio.m4a` (+ matching `*_cover.png`) at runtime, so the app
  needs no hardcoded list — the 20 sounds from `bgmusic.zip` are already copied
  into `app/src/main/assets/bgmusic/`.

No database, no accounts, no network, no analytics, no DI framework — matches
section 11 of the spec.

## Opening the project

1. Open the `BgSoundsApp` folder in **Android Studio** (Koala/2024.1 or newer).
2. `gradle/wrapper/gradle-wrapper.properties` pins **Gradle 8.10.2** (AGP is
   8.7.2, which requires Gradle 8.9+). The wrapper *jar* binary isn't included
   (this build environment has no internet access to fetch it), so on first
   open Android Studio will prompt to download/repair the wrapper - let it,
   and it will fetch exactly 8.10.2 as declared in that properties file. If
   you instead see it fall back to an older Gradle version and complain about
   needing 8.7+, that means the jar got regenerated with a stale default:
   delete `gradle/wrapper` and re-open, or, with any local Gradle installed,
   run `gradle wrapper --gradle-version 8.10.2` inside the project folder.
3. Sync Gradle (needs network access to download AGP/Kotlin/Media3/Compose from
   `google()` / `mavenCentral()` — this build environment has no internet access,
   so the project hasn't been compiled here).
4. Run on a device/emulator running **Android 8.0 (API 26) or newer**.

## Notes / things worth knowing

- `minSdk = 26`. Below that, `MediaSessionService` and adaptive icons aren't
  available; QS tiles themselves need API 24+ anyway, and Media3's modern
  session APIs are cleanest from 26 up.
- Icons (`ic_tile.xml`, launcher) are simple placeholder vectors — swap them for
  real artwork whenever you like.
- The 20 `*_audio.m4a` files add up to ~200 MB, which is why the APK will be
  fairly large; that's inherent to bundling all sounds for full offline use, per
  the spec.
- To add/remove a sound later: drop a `<name>_audio.m4a` (+ optional
  `<name>_cover.png`) into `app/src/main/assets/bgmusic/` — nothing else needs
  to change.
