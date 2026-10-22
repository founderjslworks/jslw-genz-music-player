# JSLW "GEN-Z" Music Player

Offline music player for Android **4.4 KitKat → Android 15/16**, phones and tablets.
No internet permission. Package: `com.jslw.genz` · App name: **GEN-Z Music**.

## Build

1. Install **Android Studio** (Ladybug or newer) with Android SDK 35.
2. *File → Open* this folder. Let Gradle sync (needs internet once, for Gradle and the libraries).
3. Plug in a phone (USB debugging on) and press **Run**, or *Build → Build APK(s)*.
   Release: *Build → Generate Signed App Bundle/APK*.

> **Don't accept "upgrade dependency" suggestions.** `app/build.gradle.kts` pins the last
> AndroidX / Material versions that still support KitKat (minSdk 19). Newer ones need Android 5+
> and the build will fail with a *minSdk* manifest-merger error.
>
> If Android Studio asks to upgrade the Android Gradle Plugin, that is fine (AGP 8.7+).

## Features

**Playback & sound**
- Gapless playback, crossfade (2–12 s), fade-in on play / fade-out on pause
- Speed & pitch (Android 6+), sleep timer (with "finish current song"), shake to skip
- Equalizer: 10-band graphic EQ on Android 9+ (DynamicsProcessing), device EQ bands on older phones;
  built-in, device and your own presets; bass boost, 3D surround, loudness, reverb
- Clean sound: automatic pre-amp headroom so EQ boosts never clip, -0.3 dBFS limiter (Android 9+),
  ReplayGain normalisation (track / album), TPDF dither for 16-bit output on old phones
- Hi-Res: native-rate 32-bit float output for GEN-Z's own decoder (Android 5+), "FLAC · 24-bit · 96 kHz · HI-RES"
  badge, bit-perfect USB DAC mode on Android 14+
- Formats: MP3, AAC/M4A, FLAC, WAV (8–32-bit, float), OGG, Opus, MIDI, ALAC (most devices) via Android;
  **AIFF/AIFC and DSD (DSF/DFF, DSD64–256)** via GEN-Z's own decoder (DSD is converted to PCM)
- Audio focus, pause on headphone unplug, optional resume on plug-in

**Library**
- Classic drill-down: Music ▸ Artists ▸ Albums ▸ Songs, plus Genres, Folders, Playlists
- **Album Flow**: 3D tilted covers, reflections, fling, tap to flip and see tracks
- Playlists (create, rename, reorder by drag, swipe to remove), Favorites, Recently played,
  Most played, Recently added; queue with drag-reorder and swipe-to-remove; instant search
- Tag & cover editor (saved in-app for any file; written into MP3 files on request)
- "Add music folder" for files Android doesn't index (DSD, AIFF)

**Utilities**
- Lyrics: synced `.lrc` (next to the song or picked manually), embedded lyrics (MP3, FLAC, M4A);
  tap a line to jump; floating "desktop" lyrics over other apps
- Ringtone maker: MP3 / M4A / WAV cut without re-encoding, set as ringtone, notification or alarm
- Lock screen: media controls on every Android version + optional full-screen lock-screen player
- Widgets: wide 4×1, cover 2×2, large 4×2 — coloured by the active theme; "Add widget" button (Android 8+)
- "Open with GEN-Z" from file managers

**Look & feel**
- Skin packs: colours, Google Fonts or your own fonts, icon packs, background image — import,
  update, export, or build one in the on-phone Theme Editor. See **THEMES.md**
- 10 built-in themes incl. Retro Clickwheel, Retro Amp, Cassette '84, Vinyl, Glossy 2002, Terminal, Pure Black
- Gestures: swipe up mini player → Now Playing; swipe down to close it; swipe the cover to skip;
  double-tap cover to play/pause; swipe songs right = play next, left = add to queue; predictive back
- Smooth: highest refresh rate (90/120/144 Hz), vsync-driven progress & lyrics, allocation-free
  album carousel, wide-colour-gamut on capable screens
- Tablets: text and controls scale up on 7" and 10"+ screens; landscape Now Playing layout

**Battery**
- No background work when stopped; wake lock only while audio plays; sensors and frame updates stop
  when paused; player and notification released after a configurable idle time.

## Platform limits (honest notes)

- **WMA / APE** can't be decoded by Android's built-in decoders; those files are skipped with a message.
  Adding them would mean bundling FFmpeg (+15–25 MB, LGPL obligations).
- **DSD** is converted to high-rate PCM. Native DSD / DoP output isn't possible through Android's audio stack.
- **Bit-perfect** output needs Android 14+ and a USB DAC; Android bypasses effects in that mode.
- Below Android 14 the system mixer may resample hi-res audio to the device's output rate.
- The **full-screen lock-screen player** on Android 10+ needs "Display over other apps" (Android blocks
  apps from opening screens in the background otherwise). Standard lock-screen controls always work.
- Writing tags into files: MP3 only (other formats keep edits inside GEN-Z). Android 11+ asks permission per file.

## Project layout

```
app/src/main/java/com/jslw/genz/
  audio/     engines (MediaPlayer + own PCM/DSD decoder), effects, formats, tags, bit-perfect
  data/      MediaStore scanner, library, SQLite (playlists, favorites, stats), settings
  playback/  MusicService (queue, gapless, crossfade, session, notification), lyrics, shake, floating lyrics
  skin/      skin packs, fonts, icon packs, painter
  ui/        screens (main, Album Flow, Now Playing, EQ, settings, themes, editor, search, tags, ringtone, lock screen)
  widget/    home-screen widgets
app/src/main/assets/skins/   built-in theme files
app/src/main/res/font/       bundled Google Fonts (OFL)
```
