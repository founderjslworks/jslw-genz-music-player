using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Threading.Tasks;

namespace GenZ.Core
{
    public class Song : INotifyPropertyChanged
    {
        public string Path { get; set; }
        public string Title { get; set; }
        public string Artist { get; set; }
        public string Album { get; set; }
        public string AlbumArtist { get; set; }
        public string Genre { get; set; }
        public int Year { get; set; }
        public int Track { get; set; }
        public long DurationMs { get; set; }
        public int Bitrate { get; set; }
        public int SampleRate { get; set; }
        public int Bits { get; set; }
        public long Modified { get; set; }

        bool playing;
        public bool IsPlaying { get => playing; set { if (playing != value) { playing = value; PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(IsPlaying))); } } }
        public event PropertyChangedEventHandler PropertyChanged;

        public string Ext => System.IO.Path.GetExtension(Path ?? "").TrimStart('.').ToUpperInvariant();
        public string DurationText => Library.Format(DurationMs);
        public string AlbumKey => ((AlbumArtist ?? Artist ?? "") + "\u0001" + (Album ?? "")).ToLowerInvariant();
        public string YearText => Year > 0 ? Year.ToString() : "";
        public int Plays => Settings.Current.PlayCounts.TryGetValue(Path ?? "", out var n) ? n : 0;
        public string PlaysText => Plays > 0 ? Plays.ToString() : "";
        public string ShortFormat => (Ext == "M4A" ? "AAC" : Ext) + (Lossless && Bits > 0 ? " · " + Bits + "-bit" : Bitrate > 0 ? " · " + Bitrate : "");
        public string ArtistLine => string.IsNullOrEmpty(Album) ? Artist : Artist + " · " + Album;

        public bool Lossless => Ext == "FLAC" || Ext == "WAV" || Ext == "AIFF" || Ext == "AIF" || Ext == "ALAC" || Ext == "DSF" || Ext == "DFF";
        public bool HiRes => Lossless && (Bits > 16 || SampleRate > 48000);
        public string FormatBadge
        {
            get
            {
                var parts = new List<string> { Ext == "M4A" ? "AAC" : Ext };
                if (Lossless && Bits > 0) parts.Add(Bits + "-bit");
                else if (Bitrate > 0) parts.Add(Bitrate + " kbps");
                if (SampleRate > 0) parts.Add((SampleRate / 1000.0).ToString("0.#") + " kHz");
                if (HiRes) parts.Add("HI-RES");
                return string.Join(" · ", parts);
            }
        }
    }

    public class AlbumInfo
    {
        public string Key { get; set; }
        public string Title { get; set; }
        public string Artist { get; set; }
        public int Year { get; set; }
        public List<Song> Songs { get; set; } = new List<Song>();
        public string Subtitle => (Year > 0 ? Year + " · " : "") + Songs.Count + (Songs.Count == 1 ? " song" : " songs");
        public Song First => Songs.FirstOrDefault();
    }

    public class ArtistInfo
    {
        public string Name { get; set; }
        public List<Song> Songs { get; set; } = new List<Song>();
        public int AlbumCount => Songs.Select(s => s.Album).Distinct().Count();
        public string Subtitle => AlbumCount + (AlbumCount == 1 ? " album · " : " albums · ") + Songs.Count + (Songs.Count == 1 ? " song" : " songs");
        public Song First => Songs.FirstOrDefault();
    }

    /// <summary>A genre or a folder: a named group of songs.</summary>
    public class GroupInfo
    {
        public string Name { get; set; }
        public string Detail { get; set; }
        public List<Song> Songs { get; set; } = new List<Song>();
        public string Subtitle => Songs.Count + (Songs.Count == 1 ? " song" : " songs");
        public Song First => Songs.FirstOrDefault();
    }

    /// <summary>Scans the music folders, reads tags with TagLib and keeps a cache so the next start is instant.</summary>
    public static class Library
    {
        public static readonly string[] Extensions = { ".mp3", ".flac", ".wav", ".aiff", ".aif", ".m4a", ".aac", ".wma", ".mp4" };
        public static List<Song> Songs { get; private set; } = new List<Song>();
        public static List<AlbumInfo> Albums { get; private set; } = new List<AlbumInfo>();
        public static List<ArtistInfo> Artists { get; private set; } = new List<ArtistInfo>();
        public static List<GroupInfo> Genres { get; private set; } = new List<GroupInfo>();
        public static List<GroupInfo> Folders { get; private set; } = new List<GroupInfo>();
        public static Dictionary<string, Song> ByPath { get; private set; } = new Dictionary<string, Song>(StringComparer.OrdinalIgnoreCase);
        public static event Action Changed;
        public static bool Scanning { get; private set; }

        static string CachePath => System.IO.Path.Combine(Settings.Dir, "library.json");

        public static string Format(long ms)
        {
            if (ms < 0) ms = 0;
            var t = TimeSpan.FromMilliseconds(ms);
            return t.TotalHours >= 1 ? $"{(int)t.TotalHours}:{t.Minutes:00}:{t.Seconds:00}" : $"{t.Minutes}:{t.Seconds:00}";
        }

        public static void LoadCache()
        {
            try
            {
                if (File.Exists(CachePath)) Set(Json.Read<List<Song>>(File.ReadAllText(CachePath)) ?? new List<Song>());
            }
            catch { }
        }

        static void Set(List<Song> songs)
        {
            Songs = songs.OrderBy(s => s.Title, StringComparer.CurrentCultureIgnoreCase).ToList();
            Albums = Songs.GroupBy(s => s.AlbumKey).Select(g =>
            {
                var f = g.First();
                return new AlbumInfo
                {
                    Key = g.Key, Title = string.IsNullOrEmpty(f.Album) ? "Unknown album" : f.Album,
                    Artist = f.AlbumArtist ?? f.Artist, Year = g.Max(x => x.Year),
                    Songs = g.OrderBy(x => x.Track).ThenBy(x => x.Title).ToList()
                };
            }).OrderBy(a => a.Title, StringComparer.CurrentCultureIgnoreCase).ToList();
            Artists = Songs.GroupBy(s => (s.Artist ?? "Unknown artist").ToLowerInvariant()).Select(g => new ArtistInfo
            {
                Name = g.First().Artist ?? "Unknown artist",
                Songs = g.OrderBy(x => x.Album).ThenBy(x => x.Track).ToList()
            }).OrderBy(a => a.Name, StringComparer.CurrentCultureIgnoreCase).ToList();
            Genres = Songs.GroupBy(s => string.IsNullOrWhiteSpace(s.Genre) ? "Unknown genre" : s.Genre.Trim(), StringComparer.CurrentCultureIgnoreCase)
                .Select(g => new GroupInfo { Name = g.Key, Songs = g.OrderBy(x => x.Artist).ThenBy(x => x.Album).ThenBy(x => x.Track).ToList() })
                .OrderBy(g => g.Name == "Unknown genre").ThenBy(g => g.Name, StringComparer.CurrentCultureIgnoreCase).ToList();
            Folders = Songs.GroupBy(s => System.IO.Path.GetDirectoryName(s.Path) ?? "", StringComparer.OrdinalIgnoreCase)
                .Select(g => new GroupInfo { Name = System.IO.Path.GetFileName(g.Key.TrimEnd('\\')) is string n && n.Length > 0 ? n : g.Key, Detail = g.Key,
                    Songs = g.OrderBy(x => x.Track).ThenBy(x => System.IO.Path.GetFileName(x.Path), StringComparer.CurrentCultureIgnoreCase).ToList() })
                .OrderBy(g => g.Detail, StringComparer.CurrentCultureIgnoreCase).ToList();
            var map = new Dictionary<string, Song>(StringComparer.OrdinalIgnoreCase);
            foreach (var x in Songs) map[x.Path] = x;
            ByPath = map;
        }

        /// <summary>Regroup albums/artists/genres after tags were edited, and save the cache.</summary>
        public static void Refresh()
        {
            Set(Songs.ToList());
            try { File.WriteAllText(CachePath, Json.Write(Songs)); } catch { }
            Changed?.Invoke();
        }

        /// <summary>Audio files among the given files and folders (folders are searched recursively).</summary>
        public static List<string> AudioFiles(IEnumerable<string> paths)
        {
            var list = new List<string>();
            foreach (var p in paths)
            {
                if (Directory.Exists(p)) list.AddRange(SafeFiles(p).OrderBy(f => f, StringComparer.CurrentCultureIgnoreCase).Where(IsAudio));
                else if (File.Exists(p) && IsAudio(p)) list.Add(p);
            }
            return list;
        }
        public static bool IsAudio(string f) => Array.IndexOf(Extensions, System.IO.Path.GetExtension(f).ToLowerInvariant()) >= 0;

        /// <summary>A library song for this path, or its tags read on the fly.</summary>
        public static Song Get(string path) => ByPath.TryGetValue(path, out var s) ? s : Library.Read(path);

        /// <summary>Re-scans in the background; unchanged files reuse their cached tags.</summary>
        public static Task ScanAsync()
        {
            if (Scanning) return Task.FromResult(0);
            Scanning = true;
            var folders = Settings.Current.Folders.ToList();
            var cached = Songs.ToDictionary(s => s.Path, StringComparer.OrdinalIgnoreCase);
            return Task.Run(() =>
            {
                var result = new List<Song>();
                foreach (var folder in folders)
                {
                    if (!Directory.Exists(folder)) continue;
                    foreach (var f in SafeFiles(folder))
                    {
                        var ext = System.IO.Path.GetExtension(f).ToLowerInvariant();
                        if (Array.IndexOf(Extensions, ext) < 0) continue;
                        long mod;
                        try { mod = File.GetLastWriteTimeUtc(f).Ticks; } catch { continue; }
                        if (cached.TryGetValue(f, out var old) && old.Modified == mod) { result.Add(old); continue; }
                        var s = Read(f); s.Modified = mod;
                        if (s.DurationMs >= 15000 || s.DurationMs == 0) result.Add(s);
                    }
                }
                return result.GroupBy(s => s.Path, StringComparer.OrdinalIgnoreCase).Select(g => g.First()).ToList();
            }).ContinueWith(t =>
            {
                Scanning = false;
                if (t.Status == TaskStatus.RanToCompletion)
                {
                    Set(t.Result);
                    try { File.WriteAllText(CachePath, Json.Write(t.Result)); } catch { }
                }
                Changed?.Invoke();
            }, TaskScheduler.FromCurrentSynchronizationContext());
        }

        static IEnumerable<string> SafeFiles(string root)
        {
            var stack = new Stack<string>(); stack.Push(root);
            while (stack.Count > 0)
            {
                var dir = stack.Pop();
                string[] files = null, dirs = null;
                try { files = Directory.GetFiles(dir); dirs = Directory.GetDirectories(dir); } catch { }
                if (files != null) foreach (var f in files) yield return f;
                if (dirs != null) foreach (var d in dirs) stack.Push(d);
            }
        }

        public static Song Read(string path)
        {
            var s = new Song { Path = path, Title = System.IO.Path.GetFileNameWithoutExtension(path), Artist = "Unknown artist", Album = "" };
            try
            {
                using (var f = TagLib.File.Create(path))
                {
                    var t = f.Tag;
                    if (!string.IsNullOrWhiteSpace(t.Title)) s.Title = t.Title.Trim();
                    var artist = t.FirstPerformer ?? t.FirstAlbumArtist;
                    if (!string.IsNullOrWhiteSpace(artist)) s.Artist = artist.Trim();
                    s.AlbumArtist = string.IsNullOrWhiteSpace(t.FirstAlbumArtist) ? null : t.FirstAlbumArtist.Trim();
                    s.Album = t.Album ?? "";
                    s.Genre = t.FirstGenre;
                    s.Year = (int)t.Year;
                    s.Track = (int)t.Track;
                    var p = f.Properties;
                    if (p != null)
                    {
                        s.DurationMs = (long)p.Duration.TotalMilliseconds;
                        s.Bitrate = p.AudioBitrate;
                        s.SampleRate = p.AudioSampleRate;
                        s.Bits = p.BitsPerSample;
                    }
                }
            }
            catch { }
            return s;
        }

        public static IEnumerable<Song> Search(string q)
        {
            if (string.IsNullOrWhiteSpace(q)) return Songs;
            q = q.Trim();
            return Songs.Where(s => Has(s.Title, q) || Has(s.Artist, q) || Has(s.Album, q) || Has(s.Genre, q));
        }
        static bool Has(string a, string q) => a != null && a.IndexOf(q, StringComparison.CurrentCultureIgnoreCase) >= 0;
    }
}
