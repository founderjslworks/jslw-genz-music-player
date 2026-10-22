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
            while (Current.EqGains.Count < 10) Current.EqGains.Add(0);
        }

        public static void Save()
        {
            try { File.WriteAllText(FilePath, Json.Write(Current)); } catch { }
        }
    }
}
