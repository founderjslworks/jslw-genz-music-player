using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace GenZ.Core
{
    /// <summary>Album covers from the file's tags or a cover / folder picture beside it, decoded off the UI thread and cached.</summary>
    public static class Covers
    {
        static readonly Dictionary<string, BitmapSource> cache = new Dictionary<string, BitmapSource>();
        static readonly LinkedList<string> order = new LinkedList<string>();
        const int Max = 400;

        /// <summary>Forget cached pictures of this song's album (after a new cover was downloaded or saved).</summary>
        public static void Forget(Song s)
        {
            lock (cache)
            {
                var prefix = s.AlbumKey + "|";
                foreach (var k in cache.Keys.Where(k => k.StartsWith(prefix)).ToList()) { cache.Remove(k); order.Remove(k); }
            }
        }

        /// <summary>Pixel width of the song's own (embedded or folder) cover, 0 if none.</summary>
        public static int EmbeddedWidth(Song s)
        {
            try
            {
                using (var f = TagLib.File.Create(s.Path))
                {
                    var pic = f.Tag.Pictures?.FirstOrDefault();
                    if (pic == null) return 0;
                    var dec = BitmapDecoder.Create(new MemoryStream(pic.Data.Data), BitmapCreateOptions.DelayCreation, BitmapCacheOption.None);
                    return dec.Frames[0].PixelWidth;
                }
            }
            catch { return 0; }
        }

        public static BitmapSource Load(Song s, int size)
        {
            if (s == null) return null;
            var key = s.AlbumKey + "|" + size;
            lock (cache) if (cache.TryGetValue(key, out var hit)) return hit;
            BitmapSource bmp = null;
            try
            {
                byte[] data = null;
                // a high-quality cover downloaded online (only when the user turned that on) wins
                try { var online = Online.CoverFile(s); if (File.Exists(online)) data = File.ReadAllBytes(online); } catch { }
                if (data == null)
                using (var f = TagLib.File.Create(s.Path))
                {
                    var pic = f.Tag.Pictures?.FirstOrDefault(p => p.Type == TagLib.PictureType.FrontCover) ?? f.Tag.Pictures?.FirstOrDefault();
                    if (pic != null) data = pic.Data.Data;
                }
                if (data == null)
                {
                    var dir = Path.GetDirectoryName(s.Path);
                    foreach (var n in new[] { "cover.jpg", "folder.jpg", "front.jpg", "cover.png", "folder.png", "album.jpg", "AlbumArtSmall.jpg" })
                    {
                        var p = Path.Combine(dir, n);
                        if (File.Exists(p)) { data = File.ReadAllBytes(p); break; }
                    }
                }
                if (data != null)
                {
                    var bi = new BitmapImage();
                    bi.BeginInit();
                    bi.CacheOption = BitmapCacheOption.OnLoad;
                    bi.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
                    bi.DecodePixelWidth = size;
                    bi.StreamSource = new MemoryStream(data);
                    bi.EndInit();
                    bi.Freeze();
                    bmp = bi;
                }
            }
            catch { bmp = null; }
            lock (cache)
            {
                cache[key] = bmp;
                order.AddLast(key);
                while (order.Count > Max) { cache.Remove(order.First.Value); order.RemoveFirst(); }
            }
            return bmp;
        }

        /// <summary>A cover brush with high-quality (Fant) scaling, so big covers shrink without jagged edges.</summary>
        public static ImageBrush Brush(ImageSource img)
        {
            var b = new ImageBrush(img) { Stretch = Stretch.UniformToFill };
            System.Windows.Media.RenderOptions.SetBitmapScalingMode(b, Perf.Scaling);
            return b;
        }

        /// <summary>Device pixels per layout pixel (monitor DPI × interface size), so covers are decoded sharp on 4K screens.</summary>
        public static double PixelScale { get; set; } = 1;

        /// <summary>Loads a cover <paramref name="size"/> layout pixels wide, decoded at the screen's real pixel size.</summary>
        public static Task<BitmapSource> LoadAsync(Song s, int size)
        {
            int px = (int)Math.Min(Perf.Light ? 768 : 2048, Math.Ceiling(size * Math.Max(1, PixelScale) / 64.0) * 64);
            return Task.Run(() => Load(s, px));
        }

        /// <summary>Average colour of a cover (for glows and the progress bar).</summary>
        public static Color? Average(BitmapSource b)
        {
            if (b == null) return null;
            try
            {
                var small = new FormatConvertedBitmap(new TransformedBitmap(b, new ScaleTransform(16.0 / b.PixelWidth, 16.0 / b.PixelHeight)), PixelFormats.Bgra32, null, 0);
                int w = small.PixelWidth, h = small.PixelHeight;
                var px = new byte[w * h * 4];
                small.CopyPixels(px, w * 4, 0);
                long r = 0, g = 0, bl = 0; int n = 0;
                for (int i = 0; i < px.Length; i += 4)
                {
                    int max = Math.Max(px[i], Math.Max(px[i + 1], px[i + 2])), min = Math.Min(px[i], Math.Min(px[i + 1], px[i + 2]));
                    if (max - min < 25) continue; // skip greys: we want the cover's colour
                    bl += px[i]; g += px[i + 1]; r += px[i + 2]; n++;
                }
                if (n == 0) return null;
                return Color.FromRgb((byte)(r / n), (byte)(g / n), (byte)(bl / n));
            }
            catch { return null; }
        }
    }
}
