using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

namespace GenZ.Core
{
    public class Playlist
    {
        public string Name { get; set; }
        public List<string> Paths { get; set; } = new List<string>();
        public long Created { get; set; } = DateTime.UtcNow.Ticks;
    }

    /// <summary>User playlists, stored in %APPDATA%\GEN-Z Music\playlists.json. M3U / M3U8 import and export.</summary>
    public static class Playlists
    {
        public static List<Playlist> All { get; private set; } = new List<Playlist>();
        public static event Action Changed;
        static string FilePath => Path.Combine(Settings.Dir, "playlists.json");

        public static void Load()
        {
            try { if (File.Exists(FilePath)) All = Json.Read<List<Playlist>>(File.ReadAllText(FilePath)) ?? new List<Playlist>(); }
            catch { All = new List<Playlist>(); }
        }

        public static void Save()
        {
            try { File.WriteAllText(FilePath, Json.Write(All)); } catch { }
            Changed?.Invoke();
        }

        public static string UniqueName(string name)
        {
            name = string.IsNullOrWhiteSpace(name) ? "New playlist" : name.Trim();
            var n = name; int i = 2;
            while (All.Any(p => string.Equals(p.Name, n, StringComparison.CurrentCultureIgnoreCase))) n = name + " " + i++;
            return n;
        }

        public static Playlist Create(string name, IEnumerable<Song> songs = null)
        {
            var p = new Playlist { Name = UniqueName(name) };
            if (songs != null) p.Paths.AddRange(songs.Select(s => s.Path));
            All.Add(p); Save();
            return p;
        }

        public static void Rename(Playlist p, string name)
        {
            if (string.IsNullOrWhiteSpace(name) || name.Trim() == p.Name) return;
            p.Name = UniqueName(name); Save();
        }

        public static void Delete(Playlist p) { All.Remove(p); Save(); }

        public static int Add(Playlist p, IEnumerable<Song> songs)
        {
            int n = 0;
            foreach (var s in songs) { p.Paths.Add(s.Path); n++; }
            Save();
            return n;
        }

        public static void RemoveAt(Playlist p, IEnumerable<int> indexes)
        {
            foreach (var i in indexes.Where(i => i >= 0 && i < p.Paths.Count).OrderByDescending(i => i)) p.Paths.RemoveAt(i);
            Save();
        }

        public static void Move(Playlist p, int from, int to)
        {
            if (from < 0 || from >= p.Paths.Count) return;
            var x = p.Paths[from]; p.Paths.RemoveAt(from);
            to = Math.Max(0, Math.Min(to, p.Paths.Count));
            p.Paths.Insert(to, x); Save();
        }

        /// <summary>The playlist's songs; files outside the library are read on the fly, missing files are skipped.</summary>
        public static List<Song> Songs(Playlist p)
        {
            var map = Library.ByPath;
            var list = new List<Song>();
            foreach (var path in p.Paths)
            {
                if (map.TryGetValue(path, out var s)) list.Add(s);
                else if (File.Exists(path)) { var r = Library.Read(path); list.Add(r); }
            }
            return list;
        }

        public static void ExportM3u(Playlist p, string file)
        {
            var sb = new StringBuilder("#EXTM3U\r\n");
            foreach (var s in Songs(p))
            {
                sb.Append("#EXTINF:").Append(s.DurationMs / 1000).Append(',').Append(s.Artist).Append(" - ").Append(s.Title).Append("\r\n");
                sb.Append(s.Path).Append("\r\n");
            }
            File.WriteAllText(file, sb.ToString(), new UTF8Encoding(false));
        }

        public static Playlist ImportM3u(string file)
        {
            var dir = Path.GetDirectoryName(file);
            var paths = new List<string>();
            foreach (var raw in File.ReadAllLines(file, Encoding.UTF8))
            {
                var line = raw.Trim();
                if (line.Length == 0 || line.StartsWith("#")) continue;
                if (line.StartsWith("file:///", StringComparison.OrdinalIgnoreCase)) { try { line = new Uri(line).LocalPath; } catch { continue; } }
                string full;
                try { full = Path.IsPathRooted(line) ? line : Path.GetFullPath(Path.Combine(dir, line)); } catch { continue; }
                if (File.Exists(full)) paths.Add(full);
            }
            var p = new Playlist { Name = UniqueName(Path.GetFileNameWithoutExtension(file)), Paths = paths };
            All.Add(p); Save();
            return p;
        }
    }
}
