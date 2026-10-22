# GEN-Z skin packs & theme files

GEN-Z never downloads anything. Themes are files you **import manually**
(Themes & skin packs → *Import pack*). You can also build one on the phone
(*Create theme* → Theme Editor) and *Export as skin pack* to share or modify it on a PC.

## Two formats

| Format | What it can change |
|---|---|
| `my-theme.json` | Colours, bundled Google Font, LCD font, corners, retro options |
| `my-pack.genzskin.zip` | Everything above **plus** your own fonts, icon set and background picture |

### Skin pack layout (.zip)

```
my-pack.genzskin.zip
├── theme.json            (required)
├── fonts/                (optional) .ttf / .otf
│   └── MyFont.ttf
├── icons/                (optional) .png / .webp, named after the icon
│   ├── play.png
│   ├── pause.png
│   └── …
└── images/               (optional) background picture
    └── bg.jpg
```

Limits: 25 MB per pack; only the folders above are read (anything else is ignored).

### Updating a pack

Importing a pack whose `"id"` is already installed **replaces** it, as long as its
`"version"` is the same or higher. Bump `"version"` every time you publish a change.
Older versions are refused, so users never downgrade by accident.

## theme.json reference

```json
{
  "format": 1,
  "id": "neon_nights",                 // unique, letters/digits/_/- only
  "name": "Neon Nights",
  "author": "You",
  "version": 3,                        // bump to update an installed pack
  "dark": true,                        // dark or light system dialogs & bars

  "font": "inter",                     // bundled: inter, inter-black, lora, vt323, barlow-condensed, orbitron,
                                       //          sans, serif, monospace   – or a pack file: "fonts/MyFont.ttf"
  "lcdFont": "orbitron",               // font of the retro LCD strip, same choices
  "cornerRadius": 16,                  // dp
  "colorfulIcons": true,               // iOS-style coloured icon tiles in the menu
  "lcd": false,                        // retro LCD strip on Now Playing
  "npDynamicColors": true,             // Now Playing takes its colours from the album art
  "reflection": true,                  // mirror reflections in Album Flow
  "chevrons": true,                    // classic › arrows on rows
  "rowDividers": false,                // hairlines between rows
  "tintIcons": true,                   // tint pack icons with theme colours (false = full-colour PNGs)
  "backgroundImage": "images/bg.jpg",  // optional, pack only

  "colors": {
    "background": "#0B0A16",   "surface": "#16142B",
    "header": "#7B2FF7",       "headerEnd": "#F107A3",   "headerText": "#FFFFFF",
    "text": "#F7F5FF",         "textSecondary": "#B2ACD6",
    "accent": "#FF3D9A",       "onAccent": "#FFFFFF",    "divider": "#272449",
    "rowHighlight": "#7B2FF7", "rowHighlightEnd": "#F107A3", "rowHighlightText": "#FFFFFF",
    "npBackground": "#2A0E5C", "npBackgroundEnd": "#0B0A16",
    "npText": "#FFFFFF",       "npTextSecondary": "#C9C3EE",
    "lcdBackground": "#0B0A16","lcdText": "#00F5D4",
    "flowBackground": "#05040C","flowBackgroundEnd": "#2A0E5C","flowText": "#FFFFFF"
  }
}
```

(Comments above are for explanation only — real JSON files can't contain `//` comments.)
Every key is optional; missing colours are derived from the others. Colours are `#RRGGBB`
or `#AARRGGBB`.

## Icon names

Put PNGs (96×96 px or larger; white-on-transparent if `tintIcons` is true) in `icons/`:

```
play  pause  next  prev  shuffle  repeat  repeat_one  queue  lyrics  timer  equalizer
favorite  favorite_border  more  chevron  back  collapse  search  settings  close  add  drag
album  artist  genre  folder  playlist  song  album_flow  themes  ringtone  edit  speed  delete
recent  most_played  playlist_add  check  play_next  info  share  import
```

Any icon you leave out keeps the built-in one. An exported pack contains `icons/README.txt`
with this list.

## Built-in themes

GEN-Z Vivid (default) · Modern Dark · Modern Light · Pure Black (AMOLED) · Retro Clickwheel ·
Retro Amp · Cassette '84 · Vinyl Hi-Fi · Glossy 2002 · Terminal

Export any of them as a pack to use as a starting point.

## Fonts

Bundled fonts are Google Fonts under the SIL Open Font License 1.1: Inter, Lora, VT323,
Barlow Condensed, Orbitron. Only put fonts in a pack that you are allowed to redistribute.

## Hardware-style controls (v1.6)

A theme can replace the Now Playing transport row with a hardware control:

```json
"npControls": "wheel",            // "standard" (default), "wheel" or "keypad"
"wheel": { "panelTop": "#F4F4F6", "panelBottom": "#D8D9DE", "face": "#FFFFFF", "faceEdge": "#ECEDF1",
           "centerTop": "#F1F2F5", "centerBottom": "#C6C9D0", "glyph": "#8E929B", "accent": "#2B2E35", "label": "#8E929B" }
```

* **wheel** – click wheel: top = shuffle, left/right = previous/next, bottom or centre = play/pause; circle a finger around the ring to scrub (5 s per step).
* **keypad** – pocket-recorder speaker grille with three keys (previous · play/pause · next). Use a `"keypad"` colour block.

Built-in examples: Wheel Noir, Cream Wheel, Pocket Recorder.

### More control styles (v1.7)

`"npControls"` can also be:

* **deck** – radio deck: LCD read-out, chrome previous / next buttons, a seek knob (turn to scrub, tap to play / pause) and a status LED (`"led"` colour).
* **tuner** – radio tuner: big pixel read-out, a glowing tuning band (drag it to move through the song) and a ribbed dial.
* **gauge** – analog dial with a minute scale and a red needle; drag the needle to jump anywhere, tap the centre to play / pause.
* **pucks** – a row of round 3D buttons (previous · play / pause · next · queue) with an elapsed / total read-out.
* **keypad** now accepts `"grille": false` to hide the speaker grille.

### Vinyl cover

`"npCover": "vinyl"` shows the album art as the label of a spinning record with a tonearm that lifts on pause.
Optional colours: `"vinyl": { "plinthTop", "plinthBottom", "label", "arm", "head", "well": true }`.

Built-in examples: Turntable Rose, Retro Deck, Radio Deck, Tuner Orange, Analog Dial.
