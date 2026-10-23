using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;

namespace GenZ.Core
{
    /// <summary>Track details found online.</summary>
    public class OnlineTrack
    {
        public string Title, Artist, Album, Genre, CoverUrl, Source;
        public int Year, Track;
    }

    /// <summary>
    /// Optional online look-ups (only when the user switches them on, or asks for one):
    ///  • cover art and track info – Apple iTunes Search (covers up to 1200 × 1200), Cover Art Archive / MusicBrainz as backup
    ///  • synced or plain lyrics – LRCLIB (lrclib.net)
    /// Only the song's title, artist, album and length are sent. Results are cached in %APPDATA%\GEN-Z Music.
    /// </summary>
    public static class Online
    {
        static readonly HttpClient http;
        static readonly SemaphoreSlim gate = new SemaphoreSlim(2);
        static DateTime lastItunes = DateTime.MinValue, lastMb = DateTime.MinValue;
        static readonly object pace = new object();

        static Online()
        {
            // Windows 7 / 8 need TLS 1.2 switched on explicitly
            try { ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12; } catch { }
            http = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("GENZMusic/" + typeof(Online).Assembly.GetName().Version.ToString(3) + " (https://www.jslworks.in/)");
        }

        public static string CoverDir { get { var d = Path.Combine(Settings.Dir, "covers"); Directory.CreateDirectory(d); return d; } }
        public static string LyricsDir { get { var d = Path.Combine(Settings.Dir, "lyrics"); Directory.CreateDirectory(d); return d; } }

        static string Hash(string s)
        {
            using (var md5 = MD5.Create()) return BitConverter.ToString(md5.ComputeHash(Encoding.UTF8.GetBytes(s ?? ""))).Replace("-", "").ToLowerInvariant();
        }

        /// <summary>Where a downloaded cover for this song's album is kept.</summary>
        public static string CoverFile(Song s) => Path.Combine(CoverDir, Hash(s.AlbumKey + "|" + (string.IsNullOrEmpty(s.Album) ? s.Title : "")) + ".jpg");
        /// <summary>Where downloaded lyrics for this song are kept.</summary>
        public static string LyricsFile(Song s) => Path.Combine(LyricsDir, Hash(s.Path.ToLowerInvariant()) + ".lrc");

        static async Task Pace(bool musicBrainz)
        {
            // be polite: iTunes allows about 20 searches a minute, MusicBrainz one per second
            TimeSpan wait;
            lock (pace)
            {
                var now = DateTime.UtcNow;
                var last = musicBrainz ? lastMb : lastItunes;
                var gap = TimeSpan.FromMilliseconds(musicBrainz ? 1100 : 3100);
                var next = last + gap;
                wait = next > now ? next - now : TimeSpan.Zero;
                if (musicBrainz) lastMb = now + wait; else lastItunes = now + wait;
            }
            if (wait > TimeSpan.Zero) await Task.Delay(wait).ConfigureAwait(false);
        }

        static async Task<string> Get(string url)
        {
            await gate.WaitAsync().ConfigureAwait(false);
            try
            {
                using (var r = await http.GetAsync(url).ConfigureAwait(false))
                {
                    if (r.StatusCode == HttpStatusCode.NotFound) return null;
                    r.EnsureSuccessStatusCode();
                    return await r.Content.ReadAsStringAsync().ConfigureAwait(false);
                }
            }
            finally { gate.Release(); }
        }

        static string Clean(string s)
        {
            if (string.IsNullOrWhiteSpace(s)) return "";
            s = Regex.Replace(s, @"\(.*?\)|\[.*?\]|\{.*?\}", " ");                  // (Slowed + Reverb), [Official Video]…
            s = Regex.Replace(s, @"(?i)\b(official|video|audio|lyrics?|lyrical|hd|4k|full song|remastered)\b", " ");
            s = Regex.Replace(s, @"[#_|]+", " ");
            return Regex.Replace(s, @"\s+", " ").Trim();
        }

        static bool KnownArtist(string a) => !string.IsNullOrWhiteSpace(a) && !a.StartsWith("Unknown", StringComparison.OrdinalIgnoreCase);

        static double Similar(string a, string b)
        {
            a = Clean(a).ToLowerInvariant(); b = Clean(b).ToLowerInvariant();
            if (a.Length == 0 || b.Length == 0) return 0;
            if (a == b) return 1;
            if (a.Contains(b) || b.Contains(a)) return 0.8;
            var wa = new HashSet<string>(a.Split(' ')); var wb = new HashSet<string>(b.Split(' '));
            return (double)wa.Intersect(wb).Count() / Math.Max(1, wa.Union(wb).Count());
        }

        // ================================================================ track info + cover
        /// <summary>Best match for a song in the iTunes catalogue (title, artist, album, genre, year, track, 1200 px cover).</summary>
        public static async Task<OnlineTrack> FindTrack(Song s)
        {
            var term = Clean(s.Title) + (KnownArtist(s.Artist) ? " " + Clean(s.Artist) : "");
            if (term.Trim().Length < 2) return null;
            await Pace(false).ConfigureAwait(false);
            var json = await Get("https://itunes.apple.com/search?media=music&entity=song&limit=10&term=" + Uri.EscapeDataString(term)).ConfigureAwait(false);
            if (json == null) return null;
            var results = JObject.Parse(json)["results"] as JArray;
            if (results == null || results.Count == 0) return null;
            JToken best = null; double bestScore = 0;
            foreach (var r in results)
            {
                double sc = Similar((string)r["trackName"], s.Title) * 2
                          + (KnownArtist(s.Artist) ? Similar((string)r["artistName"], s.Artist) : 0.5)
                          + (string.IsNullOrEmpty(s.Album) ? 0.3 : Similar((string)r["collectionName"], s.Album) * 0.6);
                if (s.DurationMs > 0 && r["trackTimeMillis"] != null && Math.Abs((long)r["trackTimeMillis"] - s.DurationMs) < 8000) sc += 0.5;
                if (sc > bestScore) { bestScore = sc; best = r; }
            }
            if (best == null || bestScore < 1.4) return null;
            var t = new OnlineTrack
            {
                Title = (string)best["trackName"], Artist = (string)best["artistName"], Album = (string)best["collectionName"],
                Genre = (string)best["primaryGenreName"], Track = (int?)best["trackNumber"] ?? 0, Source = "iTunes",
                CoverUrl = ((string)best["artworkUrl100"])?.Replace("100x100bb", "1200x1200bb"),
            };
            if (DateTime.TryParse((string)best["releaseDate"], CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal, out var d)) t.Year = d.Year;
            return t;
        }

        /// <summary>Front cover from the Cover Art Archive via a MusicBrainz release search (backup source).</summary>
        static async Task<string> FindCoverArchive(Song s)
        {
            if (!KnownArtist(s.Artist)) return null;
            var q = "artist:\"" + Clean(s.Artist) + "\"" + (string.IsNullOrEmpty(s.Album) ? " AND recording:\"" + Clean(s.Title) + "\"" : " AND release:\"" + Clean(s.Album) + "\"");
            await Pace(true).ConfigureAwait(false);
            var url = string.IsNullOrEmpty(s.Album)
                ? "https://musicbrainz.org/ws/2/recording/?fmt=json&limit=3&query=" + Uri.EscapeDataString(q)
                : "https://musicbrainz.org/ws/2/release/?fmt=json&limit=3&query=" + Uri.EscapeDataString(q);
            var json = await Get(url).ConfigureAwait(false);
            if (json == null) return null;
            var o = JObject.Parse(json);
            string id = string.IsNullOrEmpty(s.Album) ? (string)o["recordings"]?.FirstOrDefault()?["releases"]?.FirstOrDefault()?["id"] : (string)o["releases"]?.FirstOrDefault()?["id"];
            return id == null ? null : "https://coverartarchive.org/release/" + id + "/front-1200";
        }

        /// <summary>Downloads a high-quality cover for the song's album into the cache. Returns the file, or null.</summary>
        public static async Task<string> FetchCover(Song s, OnlineTrack known = null)
        {
            var file = CoverFile(s);
            if (File.Exists(file)) return file;
            var urls = new List<string>();
            var t = known ?? await FindTrack(s).ConfigureAwait(false);
            if (t?.CoverUrl != null) urls.Add(t.CoverUrl);
            if (urls.Count == 0) { var a = await FindCoverArchive(s).ConfigureAwait(false); if (a != null) urls.Add(a); }
            foreach (var u in urls)
            {
                try
                {
                    var bytes = await http.GetByteArrayAsync(u).ConfigureAwait(false);
                    if (bytes.Length > 2000) { File.WriteAllBytes(file, bytes); return file; }
                }
                catch { }
            }
            return null;
        }

        // ================================================================ lyrics
        /// <summary>Synced (preferred) or plain lyrics from LRCLIB, saved to the cache. Returns the text, or null.</summary>
        public static async Task<string> FetchLyrics(Song s)
        {
            var file = LyricsFile(s);
            if (File.Exists(file)) return File.ReadAllText(file);
            string title = Clean(s.Title), artist = KnownArtist(s.Artist) ? Clean(s.Artist) : "";
            string text = null;
            if (artist.Length > 0)
            {
                var url = "https://lrclib.net/api/get?track_name=" + Uri.EscapeDataString(title) + "&artist_name=" + Uri.EscapeDataString(artist)
                        + (string.IsNullOrEmpty(s.Album) ? "" : "&album_name=" + Uri.EscapeDataString(Clean(s.Album)))
                        + (s.DurationMs > 0 ? "&duration=" + (s.DurationMs / 1000) : "");
                try { var j = await Get(url).ConfigureAwait(false); if (j != null) text = Pick(JObject.Parse(j)); } catch { }
            }
            if (text == null)
            {
                var url = "https://lrclib.net/api/search?q=" + Uri.EscapeDataString((title + " " + artist).Trim());
                var j = await Get(url).ConfigureAwait(false);
                if (j != null)
                {
                    var arr = JArray.Parse(j);
                    var best = arr.OrderByDescending(r => (r["syncedLyrics"]?.Type == JTokenType.String ? 1 : 0) + Similar((string)r["trackName"], s.Title)
                                                        + (s.DurationMs > 0 && r["duration"] != null && Math.Abs((double)r["duration"] * 1000 - s.DurationMs) < 6000 ? 1 : 0))
                                  .FirstOrDefault(r => Similar((string)r["trackName"], s.Title) >= 0.5);
                    if (best != null) text = Pick((JObject)best);
                }
            }
            if (!string.IsNullOrWhiteSpace(text)) File.WriteAllText(file, text, new UTF8Encoding(false));
            return text;
        }

        static string Pick(JObject o)
        {
            if (o["instrumental"]?.Type == JTokenType.Boolean && (bool)o["instrumental"]) return "[00:00.00] ♪ Instrumental ♪";
            var synced = o["syncedLyrics"]?.Type == JTokenType.String ? (string)o["syncedLyrics"] : null;
            var plain = o["plainLyrics"]?.Type == JTokenType.String ? (string)o["plainLyrics"] : null;
            return !string.IsNullOrWhiteSpace(synced) ? synced : !string.IsNullOrWhiteSpace(plain) ? plain : null;
        }
    }
}
