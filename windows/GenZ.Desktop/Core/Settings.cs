using System;
using System.Collections.Generic;
using System.IO;

namespace GenZ.Core
{
    /// <summary>User settings, stored as JSON in %APPDATA%\GEN-Z Music\settings.json.</summary>
    public class Settings
    {
        public string ThemeId { get; set; } = "vivid_genz";
        public List<string> Folders { get; set; } = new List<string>();
        public double Volume { get; set; } = 0.8;
        public bool EqEnabled { get; set; } = false;
        public string EqPreset { get; set; } = "Flat";
        public List<double> EqGains { get; set; } = new List<double> { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
        public bool Shuffle { get; set; }
        public int Repeat { get; set; }
        public List<string> Favourites { get; set; } = new List<string>();
        public string LastSong { get; set; }
        public bool NowPlayingOpen { get; set; } = true;

        // interface size: 0 = automatic (fits the window), otherwise 0.7 – 1.5
        public double UiScale { get; set; } = 0;

        // animations
        public string PageTransition { get; set; } = "slide-up";   // slide-up, slide-left, fade, zoom, none
        public string ListAnimation { get; set; } = "bounce";      // bounce, cascade, fade, none (how a list arrives)
        public int ScrollAnim { get; set; } = 13;                  // list scroll animation, see Controls.ListAnim (0 = off)
        public int ScrollInterp { get; set; } = 1;                 // decelerate
        public int ScrollDuration { get; set; } = 380;
        public string Transition { get; set; } = "genz";           // screen transition, see Controls.Transitions
        public int AnimVersion { get; set; } = 0;
        public string NavEffect { get; set; } = "stars";           // sidebar click effect, see Controls.SpaceFx
        public bool NavStarField { get; set; } = true;             // drifting stars behind the sidebar
        public bool LiveVolume { get; set; } = true;               // volume sliders pulse with the music
        public string CoverEffect { get; set; } = "ripple";        // ripple, burst, none
        public bool Sparkles { get; set; } = true;
        public bool PlayMorph { get; set; } = true;
        public bool CoverBreathe { get; set; } = true;
        public double AnimSpeed { get; set; } = 1.0;               // 0.5 = slower, 2 = faster
        public bool ReduceMotion { get; set; } = false;

        // picture-in-picture mini player
        public bool AutoPip { get; set; } = false;
        public bool TermsAccepted { get; set; } = false;

        // playback
        public bool FadePlayPause { get; set; } = true;
        public bool RememberQueue { get; set; } = true;
        public bool LyricsOpen { get; set; } = false;
        public List<string> LastQueue { get; set; } = new List<string>();
        public int LastIndex { get; set; } = -1;
        public long LastPositionMs { get; set; }

        // how each page is shown (grid / small / list / details …) and sorted
        public Dictionary<string, string> ViewModes { get; set; } = new Dictionary<string, string>();
        public Dictionary<string, string> SortModes { get; set; } = new Dictionary<string, string>();
        public string Mode(string page, string def) => ViewModes != null && ViewModes.TryGetValue(page, out var v) ? v : def;
        public string Sort(string page, string def) => SortModes != null && SortModes.TryGetValue(page, out var v) ? v : def;

        // GEN-Z Home Theater (in-app sound engine)
        public bool TheaterOn { get; set; } = false;
        public string TheaterProfile { get; set; } = "music";
        public bool TheaterHeadphones { get; set; } = true;
        public TheaterProfile TheaterCustom { get; set; }

        // optional online features (off unless the user switches them on)
        public bool OnlineCovers { get; set; } = false;
        public bool OnlineLyrics { get; set; } = false;
        public bool OnlineHiResCovers { get; set; } = false;

        // listening history
        public Dictionary<string, int> PlayCounts { get; set; } = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        public List<string> Recent { get; set; } = new List<string>();

        public void Played(string path)
        {
            if (string.IsNullOrEmpty(path)) return;
            PlayCounts.TryGetValue(path, out var n); PlayCounts[path] = n + 1;
            Recent.RemoveAll(p => string.Equals(p, path, StringComparison.OrdinalIgnoreCase));
            Recent.Insert(0, path);
            if (Recent.Count > 200) Recent.RemoveRange(200, Recent.Count - 200);
        }
        public string PipMode { get; set; } = "compact";           // compact, cover
        public double PipLeft { get; set; } = -1;
        public double PipTop { get; set; } = -1;

        /// <summary>Duration scaled by the animation-speed setting (0 when motion is reduced).</summary>
        public static System.TimeSpan Ms(double ms)
            => Current.ReduceMotion ? System.TimeSpan.Zero : System.TimeSpan.FromMilliseconds(ms / System.Math.Max(0.25, Current.AnimSpeed));

        public static string Dir
        {
            get
            {
                var d = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GEN-Z Music");
                Directory.CreateDirectory(d);
                return d;
            }
        }

        static string FilePath => Path.Combine(Dir, "settings.json");
        public static Settings Current { get; private set; } = new Settings();

        public static void Load()
        {
            try
            {
                if (File.Exists(FilePath)) Current = Json.Read<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
            }
            catch { Current = new Settings(); }
            if (Current.Folders.Count == 0)
            {
                var music = Environment.GetFolderPath(Environment.SpecialFolder.MyMusic);
                if (!string.IsNullOrEmpty(music)) Current.Folders.Add(music);
            }
            if (Current.AnimVersion < 2)
            {
                // 1.3: the Android motion set arrives – start everyone on the Android defaults
                Current.AnimVersion = 2; Current.ListAnimation = "bounce"; Current.Transition = "genz";
                Current.ScrollAnim = 13; Current.ScrollInterp = 1; Current.ScrollDuration = 380;
            }
            while (Current.EqGains.Count < 10) Current.EqGains.Add(0);
            if (Current.PlayCounts == null) Current.PlayCounts = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
            else Current.PlayCounts = new Dictionary<string, int>(Current.PlayCounts, StringComparer.OrdinalIgnoreCase);
            if (Current.ViewModes == null) Current.ViewModes = new Dictionary<string, string>();
            if (Current.SortModes == null) Current.SortModes = new Dictionary<string, string>();
            if (Current.Recent == null) Current.Recent = new List<string>();
            if (Current.LastQueue == null) Current.LastQueue = new List<string>();
        }

        public static void Save()
        {
            try { File.WriteAllText(FilePath, Json.Write(Current)); } catch { }
        }
    }
}
