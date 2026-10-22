"""Generates royalty-free demo songs (synth tones) with original cover art and synced lyrics,
used only for app screenshots. Requires Pillow and ffmpeg."""
import math, os, random, subprocess, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = sys.argv[1] if len(sys.argv) > 1 else "demo"
os.makedirs(OUT, exist_ok=True)
FONT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "font")
random.seed(7)

ALBUMS = [
    ("Lotus Dawn", "Aarav & The Sunrise", 2026, ((255, 140, 170), (255, 214, 140)), "playfair_bold.ttf",
     ["Lotus Dawn", "Golden Ganga", "Morning Raga", "Petal Rain"]),
    ("Neon Monsoon", "Kavya Pulse", 2025, ((0, 240, 255), (255, 0, 229)), "orbitron_bold.ttf",
     ["Neon Monsoon", "City of Rain", "Midnight Metro"]),
    ("Synth Bazaar", "Retro Rasa", 2024, ((255, 46, 151), (122, 44, 245)), "audiowide_regular.ttf",
     ["Synth Bazaar", "Chai at 3 AM", "Arcade Heart"]),
    ("Velvet Jazz Club", "The Brass Lantern", 2023, ((232, 163, 61), (29, 45, 80)), "playfair_bold.ttf",
     ["Velvet Jazz Club", "Blue Smoke", "Last Train Home"]),
    ("Bhangra Sky", "Punjab Pixels", 2026, ((255, 184, 0), (34, 197, 94)), "bebas_neue_regular.ttf",
     ["Bhangra Sky", "Dhol Drop", "Harvest Moon"]),
    ("Lo-Fi Terrace", "Sleepy Sitar", 2025, ((142, 127, 184), (244, 169, 155)), "quicksand_bold.ttf",
     ["Lo-Fi Terrace", "Rooftop Rain", "Study Lamp"]),
    ("Pixel Quest", "8-Bit Yatra", 2024, ((57, 255, 20), (255, 32, 121)), "press_start_regular.ttf",
     ["Pixel Quest", "Boss Level", "Continue?"]),
    ("Symphony of Light", "JSL Chamber Orchestra", 2026, ((140, 28, 46), (232, 199, 122)), "cinzel_bold.ttf",
     ["Symphony of Light", "Adagio for Dusk", "Rondo in Gold"]),
]

LYRICS = """[00:00.50]Close your eyes, the morning's here
[00:04.00]A lotus opens, soft and clear
[00:07.50]Golden light on silver water
[00:11.00]Every note a little brighter
[00:14.50]Sing it slow, then sing it loud
[00:18.00]GEN-Z music, lift the crowd
[00:21.50]Hold this moment, let it play
[00:25.00]Music takes the night away"""


def font(name, size):
    try:
        return ImageFont.truetype(os.path.join(FONT_DIR, name), size)
    except Exception:
        return ImageFont.load_default()


def cover(title, artist, c1, c2, fname, path):
    s = 800
    img = Image.new("RGB", (s, s))
    d = ImageDraw.Draw(img)
    for y in range(s):
        t = y / s
        d.line([(0, y), (s, y)], fill=tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3)))
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    for _ in range(14):
        r = random.randint(60, 260)
        x, y = random.randint(-80, s), random.randint(-80, s)
        col = (255, 255, 255, random.randint(25, 70)) if random.random() < 0.5 else (0, 0, 0, random.randint(20, 50))
        ld.ellipse([x - r, y - r, x + r, y + r], fill=col)
    for k in range(6):  # sound-wave lines
        pts = [(x, s * 0.62 + math.sin(x / 55 + k) * (18 + k * 6) + k * 14) for x in range(0, s + 10, 10)]
        ld.line(pts, fill=(255, 255, 255, 90), width=3)
    layer = layer.filter(ImageFilter.GaussianBlur(2))
    img = Image.alpha_composite(img.convert("RGBA"), layer).convert("RGB")
    d = ImageDraw.Draw(img)
    f = font(fname, 86 if "press" not in fname else 46)
    words = title.split()
    lines, cur = [], ""
    for w in words:
        test = (cur + " " + w).strip()
        if d.textlength(test, font=f) > s - 120 and cur:
            lines.append(cur); cur = w
        else:
            cur = test
    lines.append(cur)
    y = 90
    for ln in lines:
        d.text((62, y + 4), ln, font=f, fill=(0, 0, 0, 120))
        d.text((60, y), ln, font=f, fill=(255, 255, 255))
        y += f.size + 18
    d.text((62, s - 110), artist.upper(), font=font("inter_semibold.ttf", 34), fill=(255, 255, 255))
    d.text((62, s - 64), "GEN-Z DEMO RECORDS", font=font("inter_regular.ttf", 22), fill=(255, 255, 255))
    img.save(path, quality=92)


def tone_expr(seed):
    rnd = random.Random(seed)
    base = rnd.choice([196, 220, 246.94, 261.63, 293.66])
    chord = [base, base * 1.25, base * 1.5, base * 2]
    parts = [f"0.18*sin(2*PI*{f:.2f}*t)*(0.6+0.4*sin(2*PI*{rnd.uniform(0.2, 0.6):.2f}*t))" for f in chord]
    return "+".join(parts)


track_no = 0
for ai, (album, artist, year, (c1, c2), fname, songs) in enumerate(ALBUMS):
    art = os.path.join(OUT, f"cover_{ai}.jpg")
    cover(album, artist, c1, c2, fname, art)
    for ti, song in enumerate(songs):
        track_no += 1
        dur = 40 + (track_no * 7) % 50
        out = os.path.join(OUT, f"{ai:02d}_{ti + 1:02d} {song}.mp3")
        meta = ["-metadata", f"title={song}", "-metadata", f"artist={artist}", "-metadata", f"album={album}",
                "-metadata", f"album_artist={artist}", "-metadata", f"date={year}", "-metadata", f"track={ti + 1}",
                "-metadata", f"genre={['Devotional', 'Electronic', 'Synthwave', 'Jazz', 'Bhangra', 'Lo-Fi', 'Chiptune', 'Classical'][ai]}"]
        if ai == 0 and ti == 0:
            meta += ["-metadata", "lyrics=" + LYRICS]
        cmd = ["ffmpeg", "-y", "-loglevel", "error",
               "-f", "lavfi", "-i", f"aevalsrc='{tone_expr(track_no)}':s=44100:d={dur}",
               "-i", art, "-map", "0:a", "-map", "1:v", "-c:a", "libmp3lame", "-b:a", "192k",
               "-c:v", "mjpeg", "-disposition:v", "attached_pic", "-id3v2_version", "3",
               "-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)"] + meta + [out]
        subprocess.run(cmd, check=True)
    os.remove(art)
print("demo tracks:", track_no)
