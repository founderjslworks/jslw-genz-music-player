using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Media;

namespace GenZ.Core
{
    /// <summary>A GEN-Z theme – the very same JSON files the Android app uses.</summary>
    public class Theme
    {
        public string Id, Name, Author, Font, DisplayFont, LcdFont, ProgressStyle, CoverEffect, NpControls;
        public bool Dark;
        public double CornerRadius;
        public Dictionary<string, Color> Colors = new Dictionary<string, Color>();
        public List<Color> ProgressColors = new List<Color>();

        public Color C(string key, Color def) => Colors.TryGetValue(key, out var c) ? c : def;
        public Color Bg => C("background", Color.FromRgb(11, 10, 22));
        public Color Header => C("header", Color.FromRgb(123, 47, 247));
        public Color HeaderEnd => C("headerEnd", Header);
        public Color Accent => C("accent", Color.FromRgb(255, 61, 154));

        public static Color ParseColor(string s)
        {
            try
            {
                s = s.Trim().TrimStart('#');
                if (s.Length == 6) s = "FF" + s;
                uint v = Convert.ToUInt32(s, 16);
                return Color.FromArgb((byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v);
            }
            catch { return Colors_Fallback; }
        }
        static readonly Color Colors_Fallback = Color.FromRgb(128, 128, 128);

        public static Theme Parse(string json)
        {
            var d = Json.Parse(json);
            var t = new Theme
            {
                Id = d.Str("id"), Name = d.Str("name", "Theme"), Author = d.Str("author", "JSL WORKS"),
                Dark = d.Bool("dark", true), Font = d.Str("font", "inter"), LcdFont = d.Str("lcdFont", "vt323"),
                ProgressStyle = d.Str("progressStyle", "sparkle"), CoverEffect = d.Str("coverEffect", "ripple"),
                NpControls = d.Str("npControls", "standard"), CornerRadius = d.Num("cornerRadius", 14),
            };
            t.DisplayFont = d.Str("displayFont", t.Font);
            var cols = d.Obj("colors");
            if (cols != null) foreach (var kv in cols) if (kv.Value != null) t.Colors[kv.Key] = ParseColor(kv.Value.ToString());
            if (d.TryGetValue("progressColors", out var pc) && pc is System.Collections.IEnumerable list && !(pc is string))
                foreach (var o in list) if (o != null) t.ProgressColors.Add(ParseColor(o.ToString()));
            return t;
        }
    }

    /// <summary>Loads the themes and applies one as dynamic application resources (every screen updates live).</summary>
    public static class ThemeManager
    {
        static readonly string[] Order = {
            "vivid_genz", "dot_matrix", "glass_aurora", "soft_ui", "neo_brutal", "swiss_mono", "mesh_gradient", "clay_pastel",
            "midnight_minimal", "chrome_y2k", "wheel_noir", "cream_wheel", "pocket_recorder", "turntable_rose", "retro_deck",
            "radio_deck", "tuner_orange", "analog_dial", "bollywood_nights", "bhakti_diya", "romantic_rose", "bhangra_beat",
            "lofi_chill", "edm_neon", "synthwave_84", "hiphop_mixtape", "rock_arena", "jazz_lounge", "classical_symphony",
            "kpop_candy", "ocean_ambient", "indie_acoustic", "arcade_8bit", "modern_dark", "modern_light", "pure_black",
            "retro_clickwheel", "retro_amp", "cassette_84", "vinyl_hifi", "glossy_2002", "terminal_green" };

        public static List<Theme> All { get; private set; } = new List<Theme>();
        public static Theme Current { get; private set; }
        public static event Action Changed;

        public static void Load()
        {
            var dir = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "skins");
            var list = new List<Theme>();
            if (Directory.Exists(dir))
                foreach (var f in Directory.GetFiles(dir, "*.json"))
                {
                    try { var t = Theme.Parse(File.ReadAllText(f)); if (string.IsNullOrEmpty(t.Id)) t.Id = Path.GetFileNameWithoutExtension(f); list.Add(t); }
                    catch { }
                }
            All = list.OrderBy(t => { int i = Array.IndexOf(Order, t.Id); return i < 0 ? 999 : i; }).ToList();
        }

        static readonly Dictionary<string, string> FontNames = new Dictionary<string, string>
        {
            ["inter"] = "Inter", ["inter-black"] = "Inter ExtraBold, Inter", ["poppins"] = "Poppins", ["montserrat"] = "Montserrat",
            ["playfair"] = "Playfair Display", ["space-grotesk"] = "Space Grotesk", ["rubik"] = "Rubik", ["caveat"] = "Caveat, Caveat Medium",
            ["cinzel"] = "Cinzel, Cinzel Medium", ["teko"] = "Teko, Teko SemiBold", ["josefin"] = "Josefin Sans",
            ["quicksand"] = "Quicksand, Quicksand Medium", ["bebas-neue"] = "Bebas Neue", ["righteous"] = "Righteous",
            ["press-start"] = "Press Start 2P", ["monoton"] = "Monoton", ["audiowide"] = "Audiowide", ["doto"] = "Doto, Doto SemiBold",
            ["jersey"] = "Jersey 10", ["barlow-condensed"] = "Barlow Condensed, Barlow Condensed Medium", ["lora"] = "Lora",
            ["orbitron"] = "Orbitron, Orbitron Medium", ["vt323"] = "VT323",
        };

        public static FontFamily Font(string id)
        {
            if (id == null || !FontNames.TryGetValue(id, out var names)) names = "Inter";
            var spec = string.Join(", ", names.Split(',').Select(n => "./Assets/Fonts/#" + n.Trim())) + ", Segoe UI";
            return new FontFamily(new Uri("pack://application:,,,/"), spec);
        }

        static SolidColorBrush B(Color c) { var b = new SolidColorBrush(c); b.Freeze(); return b; }
        static LinearGradientBrush G(Color a, Color b, bool vertical = false)
        {
            var g = new LinearGradientBrush(a, b, vertical ? 90 : 0);
            g.Freeze(); return g;
        }
        public static Color WithAlpha(Color c, byte a) => Color.FromArgb(a, c.R, c.G, c.B);

        public static void Apply(Theme t)
        {
            if (t == null) return;
            Current = t;
            var r = Application.Current.Resources;
            var text = t.C("text", t.Dark ? Colors.White : Color.FromRgb(26, 23, 38));
            var text2 = t.C("textSecondary", WithAlpha(text, 180));
            r["BgBrush"] = B(t.Bg);
            r["SurfaceBrush"] = B(t.C("surface", t.Bg));
            r["SurfaceSoftBrush"] = B(WithAlpha(t.C("surface", t.Bg), 200));
            r["HeaderBrush"] = G(t.Header, t.HeaderEnd);
            r["HeaderTextBrush"] = B(t.C("headerText", Colors.White));
            r["TextBrush"] = B(text);
            r["Text2Brush"] = B(text2);
            r["AccentBrush"] = B(t.Accent);
            r["AccentSoftBrush"] = B(WithAlpha(t.Accent, 46));
            r["OnAccentBrush"] = B(t.C("onAccent", Colors.White));
            r["DividerBrush"] = B(t.C("divider", WithAlpha(text, 30)));
            r["RowHlBrush"] = G(t.C("rowHighlight", t.Accent), t.C("rowHighlightEnd", t.C("rowHighlight", t.Accent)));
            r["RowHlTextBrush"] = B(t.C("rowHighlightText", Colors.White));
            r["HoverBrush"] = B(WithAlpha(text, 18));
            r["NpBrush"] = G(t.C("npBackground", t.Bg), t.C("npBackgroundEnd", t.Bg), true);
            r["NpTextBrush"] = B(t.C("npText", text));
            r["NpText2Brush"] = B(t.C("npTextSecondary", text2));
            r["LcdBrush"] = B(t.C("lcdBackground", Colors.Black));
            r["LcdTextBrush"] = B(t.C("lcdText", Colors.Lime));
            var pcs = t.ProgressColors.Count > 0 ? t.ProgressColors : new List<Color> { t.Header, t.Accent, t.HeaderEnd };
            var pg = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 0) };
            for (int i = 0; i < pcs.Count; i++) pg.GradientStops.Add(new GradientStop(pcs[i], pcs.Count == 1 ? 0 : (double)i / (pcs.Count - 1)));
            pg.Freeze();
            r["ProgressBrush"] = pg;
            r["AccentColor"] = t.Accent;
            r["BodyFont"] = Font(t.Font);
            r["DisplayFont"] = Font(t.DisplayFont);
            r["LcdFont"] = Font(t.LcdFont);
            r["Corner"] = new CornerRadius(Math.Max(4, Math.Min(24, t.CornerRadius)));
            Settings.Current.ThemeId = t.Id;
            Changed?.Invoke();
        }
    }
}
