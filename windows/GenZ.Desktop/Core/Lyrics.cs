using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace GenZ.Core
{
    public class LyricLine
    {
        public TimeSpan Time { get; set; }
        public string Text { get; set; }
    }

    public class LyricsDoc
    {
        public List<LyricLine> Lines { get; set; } = new List<LyricLine>();
        public bool Synced { get; set; }
        public string Source { get; set; }
        public bool Empty => Lines.Count == 0;

        /// <summary>Index of the line being sung at this time (-1 before the first line).</summary>
        public int IndexAt(TimeSpan t)
        {
            if (!Synced) return -1;
            int lo = 0, hi = Lines.Count - 1, ans = -1;
            while (lo <= hi) { int mid = (lo + hi) / 2; if (Lines[mid].Time <= t) { ans = mid; lo = mid + 1; } else hi = mid - 1; }
            return ans;
        }
    }

    /// <summary>Synced (LRC) and plain lyrics: a .lrc / .txt file next to the song, or lyrics stored in the tags.</summary>
    public static class Lyrics
    {
        static readonly Regex Stamp = new Regex(@"\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)\]");
        static readonly Regex Offset = new Regex(@"\[offset:\s*([+-]?\d+)\]", RegexOptions.IgnoreCase);
        static readonly Regex Meta = new Regex(@"^\[(ar|ti|al|by|re|ve|length|au|#):.*\]$", RegexOptions.IgnoreCase);

        public static LyricsDoc Load(Song s)
        {
            if (s?.Path == null) return new LyricsDoc();
            try
            {
                var dir = Path.GetDirectoryName(s.Path);
                var name = Path.GetFileNameWithoutExtension(s.Path);
                foreach (var cand in new[] { Path.Combine(dir, name + ".lrc"), Path.Combine(dir, "Lyrics", name + ".lrc"), Path.Combine(dir, name + ".txt") })
                {
                    if (!File.Exists(cand)) continue;
                    var d = Parse(ReadText(cand)); d.Source = Path.GetFileName(cand);
                    if (!d.Empty) return d;
                }
            }
            catch { }
            try
            {
                var online = Online.LyricsFile(s);
                if (File.Exists(online)) { var d = Parse(File.ReadAllText(online)); d.Source = "LRCLIB (online)"; if (!d.Empty) return d; }
            }
            catch { }
            try
            {
                using (var f = TagLib.File.Create(s.Path))
                {
                    var text = f.Tag.Lyrics;
                    if (!string.IsNullOrWhiteSpace(text)) { var d = Parse(text); d.Source = "song tags"; return d; }
                }
            }
            catch { }
            return new LyricsDoc();
        }

        static string ReadText(string file)
        {
            var bytes = File.ReadAllBytes(file);
            // UTF-8 (with or without BOM) is most common; fall back to the system code page.
            try { return new UTF8Encoding(false, true).GetString(bytes).TrimStart('﻿'); }
            catch { return Encoding.Default.GetString(bytes); }
        }

        public static LyricsDoc Parse(string text)
        {
            var doc = new LyricsDoc();
            if (string.IsNullOrWhiteSpace(text)) return doc;
            double offsetMs = 0;
            var om = Offset.Match(text);
            if (om.Success) double.TryParse(om.Groups[1].Value, NumberStyles.Integer, CultureInfo.InvariantCulture, out offsetMs);
            var timed = new List<LyricLine>();
            var plain = new List<LyricLine>();
            foreach (var raw in text.Replace("\r", "").Split('\n'))
            {
                var line = raw.Trim();
                if (Meta.IsMatch(line) || Offset.IsMatch(line) && Stamp.Matches(line).Count == 0) continue;
                var ms = Stamp.Matches(line);
                if (ms.Count > 0)
                {
                    var words = Stamp.Replace(line, "").Trim();
                    words = Regex.Replace(words, @"<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>", ""); // enhanced-LRC word stamps
                    foreach (Match m in ms)
                    {
                        var min = int.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture);
                        var sec = double.Parse(m.Groups[2].Value.Replace(':', '.'), CultureInfo.InvariantCulture);
                        var t = TimeSpan.FromMilliseconds(Math.Max(0, (min * 60 + sec) * 1000 - offsetMs));
                        timed.Add(new LyricLine { Time = t, Text = words });
                    }
                }
                else plain.Add(new LyricLine { Text = line });
            }
            if (timed.Count > 0)
            {
                doc.Synced = true;
                doc.Lines = timed.OrderBy(l => l.Time).ToList();
            }
            else
            {
                // trim blank lines at both ends
                while (plain.Count > 0 && plain[0].Text.Length == 0) plain.RemoveAt(0);
                while (plain.Count > 0 && plain[plain.Count - 1].Text.Length == 0) plain.RemoveAt(plain.Count - 1);
                doc.Lines = plain;
            }
            return doc;
        }
    }
}
