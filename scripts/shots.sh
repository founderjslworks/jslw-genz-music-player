#!/usr/bin/env bash
# Captures GEN-Z screenshots on a running emulator (used by .github/workflows/screenshots.yml).
set -u
PKG=com.jslw.genz
OUT=shots
mkdir -p "$OUT"

adb wait-for-device
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard 2>/dev/null || true
# clean status bar for screenshots
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1630 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 86 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null

# demo music
adb shell mkdir -p /sdcard/Music/GENZ
adb push demo/. /sdcard/Music/GENZ/ >/dev/null
adb shell content call --method scan_volume --uri content://media --arg external_primary >/dev/null 2>&1 || true
for f in demo/*.mp3; do
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Music/GENZ/$(basename "$f" | sed 's/ /%20/g')" >/dev/null 2>&1
done
sleep 8

adb install -r -g app-debug.apk
adb shell pm grant $PKG android.permission.READ_MEDIA_AUDIO 2>/dev/null || true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow 2>/dev/null || true

# warm up once (first library scan)
adb shell am start -n $PKG/.ui.MainActivity --es shot home >/dev/null
sleep 12

shot() { # name screen theme [fx] [wait]
  local name=$1 screen=$2 theme=$3 fx=${4:-theme} wait=${5:-6}
  adb shell am force-stop $PKG
  adb shell am start -n $PKG/.ui.MainActivity --es shot "$screen" --es theme "$theme" --es fx "$fx" >/dev/null
  sleep "$wait"
  adb exec-out screencap -p > "$OUT/$name.png"
  echo "captured $name"
}

bounds() { # resource-id -> "cx cy w h"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  python3 - "$1" <<'PY'
import re, sys
xml = open('/tmp/ui.xml', encoding='utf-8').read()
m = re.search(r'resource-id="com\.jslw\.genz:id/%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % sys.argv[1], xml)
if not m:
    print("540 900 600 600")
else:
    x1, y1, x2, y2 = map(int, m.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2, x2 - x1, y2 - y1)
PY
}

# ---- core screens (default theme) ----
shot 01_home home vivid_genz
shot 02_album_flow albumflow vivid_genz theme 7
shot 03_now_playing nowplaying vivid_genz theme 9
shot 04_lyrics lyrics vivid_genz theme 10
shot 05_queue queue vivid_genz theme 10
shot 06_equalizer equalizer vivid_genz theme 7
shot 07_themes themes vivid_genz
shot 08_theme_editor editor vivid_genz
shot 09_settings settings vivid_genz
shot 10_search search vivid_genz
shot 11_albums albums vivid_genz
shot 12_songs songs vivid_genz theme 8
shot 13_playlists playlists vivid_genz
shot 14_lock_screen lock vivid_genz theme 9

# ---- themes: home + now playing ----
for t in dot_matrix glass_aurora soft_ui neo_brutal swiss_mono mesh_gradient clay_pastel midnight_minimal chrome_y2k \
         bollywood_nights bhakti_diya romantic_rose bhangra_beat lofi_chill edm_neon synthwave_84 hiphop_mixtape \
         rock_arena jazz_lounge classical_symphony kpop_candy ocean_ambient indie_acoustic arcade_8bit \
         modern_dark modern_light pure_black retro_clickwheel retro_amp cassette_84 vinyl_hifi glossy_2002 terminal_green; do
  shot "theme_${t}_home" home "$t" theme 5
  shot "theme_${t}_np" nowplaying "$t" theme 9
done

# ---- cover touch effects: slow upward drag on the cover, captured mid-effect ----
for fx in ripple stone lens_flare popping brilliant_ring ink watercolor tiles mosaic droplet bubbles blinds brilliant_cut \
          rect_traveller bouncing white_hole dewdrop hula hula2 soda particle light_particle pixelate crystal vector \
          circle_mosaic revolving_glass spring summer autumn winter; do
  adb shell am force-stop $PKG
  adb shell am start -n $PKG/.ui.MainActivity --es shot fx --es theme vivid_genz --es fx "$fx" >/dev/null
  sleep 9
  read cx cy w h < <(bounds npArtSquare)
  y1=$((cy + h / 4)); y2=$((cy - h / 4)); x2=$((cx + w / 8))
  adb shell input swipe "$((cx - w / 8))" "$y1" "$x2" "$y2" 1600 &
  sleep 1.0
  adb exec-out screencap -p > "$OUT/fx_${fx}.png"
  wait
  echo "captured fx_$fx"
done

# ---- favourite celebration ----
adb shell am force-stop $PKG
adb shell am start -n $PKG/.ui.MainActivity --es shot love --es theme vivid_genz >/dev/null
sleep 9
read cx cy w h < <(bounds npFav)
adb shell input tap "$cx" "$cy"
sleep 0.55
adb exec-out screencap -p > "$OUT/15_favourite_celebration.png"
echo "done"
ls "$OUT" | wc -l
