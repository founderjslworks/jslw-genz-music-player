"""Builds a demo music library for the user-guide screenshots: silent FLAC files with tags, original cover art and one
synced-lyrics file. All artists, albums, songs, artwork and lyrics are invented for the guide."""
import json, math, os, random, sys
import numpy as np, soundfile as sf
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from mutagen.flac import FLAC, Picture

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
random.seed(42)

ALBUMS = [
    ("Monsoon Letters", "Aria Monsoon", 2024, "Indie Pop", ["Petrichor", "Paper Boats", "Chai at Midnight", "Window Seat", "Rain Check"], ((40, 80, 160), (120, 200, 230))),
    ("City of Lights", "The Neon Rickshaws", 2023, "Synthwave", ["Marine Drive '86", "Neon Rickshaw", "Night Shift", "Overdrive", "Afterglow"], ((255, 60, 160), (60, 20, 120))),
    ("Raag & Roots", "Kavya Rao", 2022, "Classical Fusion", ["Morning Raag", "Banyan Tree", "River Song", "Diya"], ((230, 140, 40), (120, 30, 20))),
    ("Side A", "Sunset Tapes", 2021, "Lo-Fi", ["Cassette Dreams", "Rewind", "Tape Hiss", "Slow Sundays", "Polaroid"], ((250, 190, 150), (90, 70, 120))),
    ("Echo Chamber", "Ishaan & The Echoes", 2025, "Rock", ["Thunderline", "Static Hearts", "Into the Blue", "Last Train"], ((20, 20, 24), (220, 40, 40))),
    ("Lantern Nights", "Lo-Fi Lantern", 2024, "Chill", ["Firefly", "Study Session", "Moonlit Terrace", "Soft Focus"], ((20, 40, 70), (250, 200, 90))),
    ("Mehfil Grooves", "Studio Saaz", 2020, "Bollywood", ["Dil Ki Dhun", "Baarish", "Safar", "Mehfil"], ((160, 20, 60), (250, 180, 60))),
    ("Hi-Res Sessions", "Kavya Rao", 2025, "Classical", ["Sitar at Dawn", "Tabla Rain", "Veena Lullaby"], ((10, 60, 60), (200, 230, 210))),
]

def font(size):
    for f in ["C:/Windows/Fonts/segoeuib.ttf", "C:/Windows/Fonts/arialbd.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]:
        if os.path.exists(f): return ImageFont.truetype(f, size)
    return ImageFont.load_default()

def grad(S, c1, c2, vertical=True):
    im = Image.new("RGB", (S, S)); d = ImageDraw.Draw(im)
    for i in range(S):
        t = i / S; col = tuple(int(c1[k] + (c2[k] - c1[k]) * t) for k in range(3))
        d.line([(0, i), (S, i)] if vertical else [(i, 0), (i, S)], fill=col)
    return im

def glow(im, xy, r, col, blur):
    g = Image.new("RGBA", im.size, (0, 0, 0, 0)); ImageDraw.Draw(g).ellipse((xy[0] - r, xy[1] - r, xy[0] + r, xy[1] + r), fill=col)
    return Image.alpha_composite(im.convert("RGBA"), g.filter(ImageFilter.GaussianBlur(blur)))

def motif(kind, S, c1, c2, r):
    if kind == 0:   # monsoon: clouds, rain and a paper boat
        im = grad(S, (36, 62, 110), (110, 170, 205)).convert("RGBA"); d = ImageDraw.Draw(im, "RGBA")
        for cx, cy, rr in [(200, 170, 130), (330, 140, 150), (480, 180, 120), (600, 150, 140)]: d.ellipse((cx - rr, cy - rr * 0.7, cx + rr, cy + rr * 0.7), fill=(225, 232, 240, 210))
        for _ in range(140):
            x, y = r.randint(0, S), r.randint(200, S); d.line([(x, y), (x - 18, y + 46)], fill=(220, 235, 255, 120), width=2)
        d.polygon([(250, 560), (550, 560), (500, 620), (300, 620)], fill=(250, 248, 240, 255)); d.polygon([(400, 470), (400, 560), (480, 560)], fill=(240, 236, 225, 255))
        return im
    if kind == 1:   # synthwave sun and grid
        im = grad(S, (40, 10, 70), (255, 60, 150)).convert("RGBA")
        im = glow(im, (400, 360), 230, (255, 120, 60, 160), 40); d = ImageDraw.Draw(im, "RGBA")
        d.ellipse((220, 180, 580, 540), fill=(255, 190, 80, 255))
        for i, y in enumerate(range(380, 540, 26)): d.rectangle((210, y, 590, y + 6 + i * 2), fill=(70, 15, 90, 255))
        d.rectangle((0, 540, S, S), fill=(25, 5, 45, 255))
        for x in range(-800, 1600, 80): d.line([(400, 540), (x, S)], fill=(255, 60, 200, 180), width=2)
        for i in range(8): y = 540 + int((i / 8) ** 1.8 * 260); d.line([(0, y), (S, y)], fill=(255, 60, 200, 160), width=2)
        return im
    if kind == 2:   # mandala of petals
        im = grad(S, (120, 25, 25), (50, 10, 20)).convert("RGBA"); d = ImageDraw.Draw(im, "RGBA")
        for ring, (n, rr, w) in enumerate([(24, 300, 60), (16, 220, 54), (12, 140, 46), (8, 70, 34)]):
            for k in range(n):
                a = 2 * math.pi * k / n + ring * 0.2; x, y = 400 + math.cos(a) * rr, 340 + math.sin(a) * rr
                d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), outline=(240, 180, 70, 255), width=4)
        d.ellipse((370, 310, 430, 370), fill=(250, 200, 90, 255))
        return glow(im, (400, 340), 120, (255, 200, 90, 90), 50)
    if kind == 3:   # cassette on pastel
        im = grad(S, (250, 200, 170), (150, 120, 190)).convert("RGBA"); d = ImageDraw.Draw(im, "RGBA")
        d.rounded_rectangle((150, 200, 650, 520), 30, fill=(40, 36, 52, 255)); d.rounded_rectangle((200, 240, 600, 380), 14, fill=(250, 235, 210, 255))
        for cx in (300, 500): d.ellipse((cx - 50, 300, cx + 50, 400), fill=(40, 36, 52, 255)); d.ellipse((cx - 22, 328, cx + 22, 372), fill=(230, 230, 230, 255))
        d.rectangle((300, 410, 500, 470), fill=(70, 64, 86, 255)); d.text((215, 250), "SIDE A", font=font(40), fill=(220, 90, 90))
        return im
    if kind == 4:   # sound waves, red on black
        im = Image.new("RGBA", (S, S), (14, 14, 18, 255)); d = ImageDraw.Draw(im, "RGBA")
        for i in range(12): rr = 40 + i * 32; d.ellipse((400 - rr, 340 - rr, 400 + rr, 340 + rr), outline=(230, 40, 40, 255 - i * 18), width=6)
        return glow(im, (400, 340), 60, (255, 60, 60, 200), 30)
    if kind == 5:   # lanterns in a night sky
        im = grad(S, (10, 20, 45), (30, 50, 90)).convert("RGBA"); d = ImageDraw.Draw(im, "RGBA")
        for _ in range(120): x, y = r.randint(0, S), r.randint(0, S); d.ellipse((x, y, x + 2, y + 2), fill=(255, 255, 255, r.randint(80, 200)))
        for cx, cy, sz in [(220, 260, 70), (420, 180, 90), (580, 330, 60), (330, 430, 50), (520, 520, 40)]:
            im = glow(im, (cx, cy), sz * 1.6, (255, 170, 60, 120), 30); d = ImageDraw.Draw(im, "RGBA")
            d.rounded_rectangle((cx - sz / 2, cy - sz * 0.7, cx + sz / 2, cy + sz * 0.7), int(sz * 0.3), fill=(255, 190, 90, 255))
        return im
    if kind == 6:   # festive radial pattern
        im = grad(S, (170, 20, 80), (90, 10, 60)).convert("RGBA"); d = ImageDraw.Draw(im, "RGBA")
        for k in range(36):
            a = 2 * math.pi * k / 36; d.line([(400, 340), (400 + math.cos(a) * 600, 340 + math.sin(a) * 600)], fill=(255, 190, 70, 60), width=14)
        for rr, col in [(200, (255, 190, 70, 255)), (150, (170, 20, 80, 255)), (100, (255, 220, 120, 255)), (50, (120, 10, 60, 255))]:
            d.ellipse((400 - rr, 340 - rr, 400 + rr, 340 + rr), fill=col)
        return im
    im = grad(S, (8, 50, 52), (20, 90, 90)).convert("RGBA"); d = ImageDraw.Draw(im, "RGBA")   # hi-res: waveform line
    pts = [(x, 330 + math.sin(x / 38) * 90 * math.exp(-((x - 400) / 260) ** 2) + math.sin(x / 9) * 14) for x in range(40, 760, 4)]
    d.line(pts, fill=(200, 240, 220, 255), width=5); d.text((60, 70), "24-BIT · 96 kHz", font=font(36), fill=(200, 240, 220))
    return im

def cover(title, artist, c1, c2, seed):
    S = 800; r = random.Random(seed)
    im = motif(seed, S, c1, c2, r).convert("RGBA")
    shade = Image.new("RGBA", (S, S), (0, 0, 0, 0)); sd = ImageDraw.Draw(shade)
    for y in range(S - 280, S): sd.line([(0, y), (S, y)], fill=(0, 0, 0, int(190 * (y - (S - 280)) / 280)))
    im = Image.alpha_composite(im, shade).convert("RGB")
    d = ImageDraw.Draw(im)
    d.text((48, S - 160), title.upper(), font=font(58), fill=(255, 255, 255))
    d.text((50, S - 84), artist, font=font(32), fill=(235, 235, 235))
    return im

LRC = """[ti:Petrichor]
[ar:Aria Monsoon]
[00:00.00] ♪
[00:12.40] Grey clouds gather over the old town square
[00:18.10] The first drop lands, there's music in the air
[00:24.60] Paper boats are racing down the lane
[00:30.20] Every window hums a little song of rain
[00:37.00] Oh, petrichor, you bring me home
[00:43.30] The scent of earth, I'm never alone
[00:49.80] Oh, petrichor, sing it again
[00:56.10] Hold me close in the monsoon rain
[01:03.40] Chai is steaming, the radio is low
[01:09.90] Puddles shining with a silver glow
[01:16.20] Letters written on a misty pane
[01:22.80] I'll keep dancing till the sun comes again
[01:29.50] Oh, petrichor, you bring me home
[01:35.90] The scent of earth, I'm never alone
[01:42.30] ♪
"""

n = 0
for ai, (album, artist, year, genre, titles, (c1, c2)) in enumerate(ALBUMS):
    d = os.path.join(out, artist, album); os.makedirs(d, exist_ok=True)
    art = cover(album, artist, c1, c2, ai)
    import io
    buf = io.BytesIO(); art.save(buf, "JPEG", quality=90)
    hires = album == "Hi-Res Sessions"
    for ti, title in enumerate(titles, 1):
        secs = random.randint(150, 330)
        rate = 96000 if hires else 44100
        path = os.path.join(d, f"{ti:02d} - {title.replace('/', '-')}.flac")
        data = np.zeros((rate * secs // 50, 2), dtype=np.int32)   # silent, written in small blocks
        with sf.SoundFile(path, "w", samplerate=rate, channels=2, subtype="PCM_24" if hires else "PCM_16", format="FLAC") as f:
            for _ in range(50): f.write(data)
        tag = FLAC(path)
        tag["title"] = title; tag["artist"] = artist; tag["albumartist"] = artist; tag["album"] = album
        tag["date"] = str(year); tag["genre"] = genre; tag["tracknumber"] = str(ti)
        pic = Picture(); pic.type = 3; pic.mime = "image/jpeg"; pic.data = buf.getvalue(); pic.width = pic.height = 800; pic.depth = 24
        tag.add_picture(pic); tag.save()
        if title == "Petrichor":
            with open(path[:-5] + ".lrc", "w", encoding="utf-8") as lf: lf.write(LRC)
        n += 1
print("demo songs:", n)
