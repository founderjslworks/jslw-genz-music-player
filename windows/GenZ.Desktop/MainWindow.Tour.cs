using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using GenZ.Core;

namespace GenZ
{
    /// <summary>
    /// "--tour &lt;folder&gt;": visits every screen, view and theme and saves sharp pictures of the window for the user guide.
    /// Used only by the build machine with a demo library – never by users.
    /// </summary>
    public partial class MainWindow
    {
        string tourDir;
        const double TourScale = 1.25;

        public async void RunTour(string dir)
        {
            tourDir = dir; Directory.CreateDirectory(dir);
            var log = new List<string>();
            try
            {
                AllowLargeWindow = true;
                WindowState = WindowState.Normal; Left = 0; Top = 0; Width = 1440; Height = 900;
                await Task.Delay(500); log.Add($"window={ActualWidth}x{ActualHeight}");
                Settings.Current.ReduceMotion = false;
                // wait for the library
                for (int i = 0; i < 120 && (Library.Scanning || Library.Songs.Count == 0); i++) await Task.Delay(500);
                await Task.Delay(800);
                log.Add("songs=" + Library.Songs.Count);
                timer.Stop();

                // demo listening history, favourites and playlists
                var songs = Library.Songs;
                var rnd = new Random(3);
                foreach (var s in songs) Settings.Current.PlayCounts[s.Path] = rnd.Next(1, 40);
                Settings.Current.Recent = songs.OrderBy(_ => rnd.Next()).Take(14).Select(s => s.Path).ToList();
                Settings.Current.Favourites = songs.Where((s, i) => i % 5 == 0).Select(s => s.Path).ToList();
                if (Playlists.All.Count == 0)
                {
                    Playlists.Create("Road Trip", songs.Where((s, i) => i % 3 == 0).ToList());
                    Playlists.Create("Late Night Focus", songs.Where(s => (s.Genre ?? "").Contains("Chill") || (s.Genre ?? "").Contains("Lo-Fi")).ToList());
                    Playlists.Create("Monsoon Mood", songs.Where(s => s.Album == "Monsoon Letters").ToList());
                }

                // a song "playing" at 1:12 with its lyrics
                var song = songs.FirstOrDefault(s => s.Title == "Petrichor") ?? songs.First();
                var album = Library.Albums.First(a => a.Key == song.AlbumKey);
                Player.Restore(album.Songs.Concat(songs.Where(s => s.AlbumKey != song.AlbumKey).Take(10)).ToList(), album.Songs.IndexOf(song), TimeSpan.FromSeconds(72));
                ShowSong(song); LoadLyrics(song);
                await Task.Delay(1500);
                FakeProgress(song, 72);

                async Task Go(View v, string name, int wait = 1300, Action before = null)
                {
                    before?.Invoke();
                    ShowView(v);
                    await Task.Delay(wait);
                    FakeProgress(song, 72);
                    Snap(name);
                }
                void Mode(string page, string mode) => Settings.Current.ViewModes[page] = mode;

                // ---- songs in every look
                NavSongs.IsChecked = true;
                Mode("songlist", "list"); await Go(View.Songs, "songs_list");
                Mode("songlist", "compact"); await Go(View.Songs, "songs_compact");
                Mode("songlist", "covers"); await Go(View.Songs, "songs_covers");
                Mode("songlist", "details"); await Go(View.Songs, "songs_details");
                Mode("songlist", "covers");

                // ---- albums, artists, genres, folders
                Mode("albums", "grid"); await Go(View.Albums, "albums_grid");
                Mode("albums", "small"); await Go(View.Albums, "albums_small");
                Mode("albums", "list"); await Go(View.Albums, "albums_list");
                Mode("albums", "details"); await Go(View.Albums, "albums_details");
                Mode("albums", "grid");
                await Go(View.Artists, "artists");
                await Go(View.Genres, "genres");
                Mode("folders", "list"); await Go(View.Folders, "folders");
                view = View.Albums;
                OpenDetail(album.Title, album.Artist + " · " + album.Subtitle, album.Songs, album.First);
                await Task.Delay(1300); FakeProgress(song, 72); Snap("album_detail");

                // ---- your music
                await Go(View.Favourites, "favourites");
                await Go(View.Recent, "recent");
                await Go(View.Most, "most_played");
                await Go(View.Queue, "up_next");
                openPlaylist = Playlists.All.First(); await Go(View.Playlist, "playlist");

                // ---- search
                SearchBox.Text = "night"; await Task.Delay(1300); FakeProgress(song, 72); Snap("search");
                SearchBox.Text = "";

                // ---- right-click menu (composited onto the window)
                ShowView(View.Songs); await Task.Delay(1200);
                SongList.SelectedItem = song;
                var menu = SongsMenu(new List<Song> { song }, "list");
                menu.PlacementTarget = SongList; menu.Placement = PlacementMode.Relative; menu.HorizontalOffset = 360; menu.VerticalOffset = 70;
                menu.IsOpen = true; await Task.Delay(700);
                SnapWith("context_menu", menu, new Point(SongList.TranslatePoint(new Point(0, 0), (UIElement)Content).X + 360, SongList.TranslatePoint(new Point(0, 0), (UIElement)Content).Y + 70));
                menu.IsOpen = false;

                // ---- lyrics on Now Playing
                if (NpLyrics.Visibility != Visibility.Visible) OnLyricsToggle(this, null);
                await Task.Delay(900);
                int li = lyricsDoc.IndexAt(TimeSpan.FromSeconds(72));
                if (li >= 0 && li < lyricBlocks.Count) { lyricIdx = li; Highlight(lyricBlocks[li], true); CenterLyric(li); }
                await Task.Delay(900); Snap("lyrics");
                OnLyricsToggle(this, null); await Task.Delay(400);

                // ---- equalizer & Home Theater
                Settings.Current.TheaterOn = true; Settings.Current.TheaterProfile = "movie"; Settings.Current.EqEnabled = true;
                Settings.Current.EqPreset = "Rock"; Settings.Current.EqGains = Player.Presets["Rock"].ToList();
                BuildEq(); BuildTheater();
                await Go(View.Eq, "equalizer", 1800);
                PageEq.ScrollToVerticalOffset(380); await Task.Delay(900); Snap("equalizer_bands");
                PageEq.ScrollToVerticalOffset(0);

                // ---- themes page
                Mode("themes", "cards"); await Go(View.Themes, "themes_cards", 1600);
                Mode("themes", "list"); await Go(View.Themes, "themes_list", 1600);
                if (themeSearch != null) { themeSearch.Text = "retro"; await Task.Delay(900); Snap("themes_search"); themeSearch.Text = ""; }
                Mode("themes", "cards");

                // ---- animations, settings
                await Go(View.Anim, "animations", 1800);
                PageAnim.ScrollToVerticalOffset(900); await Task.Delay(1000); Snap("animations_more");
                await Go(View.About, "settings", 1800);
                PageAbout.ScrollToVerticalOffset(520); await Task.Delay(1000); Snap("settings_online");
                PageAbout.ScrollToVerticalOffset(5000); await Task.Delay(1000); Snap("settings_about");

                // ---- sleep timer menu
                ShowView(View.Songs); await Task.Delay(1000);
                OnSleep(this, null); await Task.Delay(700);
                SnapPopupMenu("sleep_timer", SleepBtn);
                CloseMenus();

                // ---- sidebar space effects
                foreach (var fx in new[] { "galaxy", "stars", "comets" })
                {
                    var layer = AdornerLayerCanvas();
                    var p = NavAlbums.TranslatePoint(new Point(120, 18), layer);
                    Controls.SpaceFx.Play(layer, p, fx);
                    await Task.Delay(fx == "galaxy" ? 520 : 330);
                    Snap("sidebar_" + fx);
                    await Task.Delay(1400);
                }

                // ---- like celebrations
                foreach (var (fx, at) in new[] { ("explosion", 520), ("frame", 1500), ("glitter", 1700), ("simple", 700) })
                {
                    Settings.Current.LikeEffect = fx;
                    Celebrate(true);
                    await Task.Delay(at);
                    Snap("like_" + fx);
                    await Task.Delay(4200);
                }

                // ---- Now Playing panel hidden, and 4K-style large window
                ShowView(View.Songs, true); NavSongs.IsChecked = true; await Task.Delay(600);
                SlidePanel(false); await Task.Delay(900); Snap("panel_hidden");

                // ---- Album Flow: in the centre with the panel hidden, then with Now Playing, then a flipped cover
                NavFlow.IsChecked = true; FlowShowPlaying(true); await Task.Delay(2200); Snap("album_flow_wide");
                FlowGo(flowTarget + 2); await Task.Delay(1500); Snap("album_flow_browse");
                FlowShowPlaying(false); await Task.Delay(1500);
                FlowFlip(true); await Task.Delay(1400); Snap("album_flow_tracks");
                FlowFlip(false); await Task.Delay(900);
                SlidePanel(true); await Task.Delay(1200); Snap("album_flow");
                NavSongs.IsChecked = true; await Task.Delay(900);

                // ---- mini player (both looks)
                foreach (var look in new[] { "compact", "cover" })
                {
                    Settings.Current.PipMode = look;
                    var mini = new MiniPlayer(this); mini.Show();
                    await Task.Delay(1500);
                    SnapElement("mini_" + look, (FrameworkElement)mini.Content);
                    mini.Close();
                }

                // ---- dialogs and windows
                var info = new SongInfoWindow(this, song) { WindowStartupLocation = WindowStartupLocation.Manual, Left = 0, Top = 0 };
                info.Show(); await Task.Delay(1500);
                SnapElement("song_info", (FrameworkElement)info.Content, info.Background);
                info.Close();
                var terms = new TermsWindow("Terms of use", TermsWindow.ReadEula(), true) { WindowStartupLocation = WindowStartupLocation.Manual, Left = 0, Top = 0 };
                terms.Show(); await Task.Delay(1000);
                SnapElement("terms", (FrameworkElement)terms.Content, terms.Background);
                terms.Close();

                // ---- every theme (Songs with covers + Now Playing)
                ShowView(View.Songs); await Task.Delay(800);
                foreach (var t in ThemeManager.All.ToList())
                {
                    ThemeManager.Apply(t);
                    await Task.Delay(1100);
                    FakeProgress(song, 72);
                    Snap("theme_" + t.Id, 1.0);
                }
                ThemeManager.Apply(ThemeManager.All.First(x => x.Id == "vivid_genz"));
                log.Add("done");
            }
            catch (Exception ex) { log.Add("ERROR " + ex); }
            File.WriteAllLines(Path.Combine(dir, "tour.log"), log);
            Application.Current.Shutdown();
        }

        void FakeProgress(Song s, double sec)
        {
            double dur = Math.Max(1, s.DurationMs / 1000.0);
            NpBar.Value = BarBar.Value = Math.Min(1, sec / dur);
            NpElapsed.Text = BarElapsed.Text = Library.Format((long)(sec * 1000));
            NpRemain.Text = "-" + Library.Format((long)((dur - sec) * 1000));
            BarTotal.Text = Library.Format(s.DurationMs);
            ScanText.Text = $"{Library.Songs.Count} songs · {Library.Albums.Count} albums";
        }

        void CloseMenus()
        {
            foreach (var p in FindPopups()) p.IsOpen = false;
        }

        IEnumerable<ContextMenu> FindPopups()
        {
            // open context menus live in their own popup windows
            foreach (PresentationSource src in PresentationSource.CurrentSources)
                if (src.RootVisual is FrameworkElement fe)
                {
                    var cm = FindChild<ContextMenu>(fe);
                    if (cm != null && cm.IsOpen) yield return cm;
                }
        }

        static T FindChild<T>(DependencyObject root) where T : DependencyObject
        {
            if (root is T t) return t;
            for (int i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
            {
                var r = FindChild<T>(VisualTreeHelper.GetChild(root, i));
                if (r != null) return r;
            }
            return null;
        }

        void SnapPopupMenu(string name, FrameworkElement anchor)
        {
            var cm = FindPopups().FirstOrDefault();
            if (cm == null) { Snap(name); return; }
            var root = (UIElement)Content;
            var p = anchor.TranslatePoint(new Point(anchor.ActualWidth - cm.ActualWidth + 8, anchor.ActualHeight), root);
            SnapWith(name, cm, p);
        }

        RenderTargetBitmap Render(FrameworkElement el, double scale)
        {
            el.UpdateLayout();
            double w = el.ActualWidth + el.Margin.Left + el.Margin.Right, h = el.ActualHeight + el.Margin.Top + el.Margin.Bottom;
            var rtb = new RenderTargetBitmap((int)Math.Ceiling(w * scale), (int)Math.Ceiling(h * scale), 96 * scale, 96 * scale, PixelFormats.Pbgra32);
            // through a VisualBrush, so the element's position in its window does not shift the picture (margins and shadows are kept)
            var dv = new DrawingVisual();
            using (var dc = dv.RenderOpen())
                dc.DrawRectangle(new VisualBrush(el) { Stretch = Stretch.None, AlignmentX = AlignmentX.Left, AlignmentY = AlignmentY.Top,
                                                       ViewboxUnits = BrushMappingMode.Absolute, Viewbox = new Rect(0, 0, el.ActualWidth, el.ActualHeight) },
                    null, new Rect(el.Margin.Left, el.Margin.Top, el.ActualWidth, el.ActualHeight));
            rtb.Render(dv);
            return rtb;
        }

        void Save(BitmapSource bmp, string name, bool jpeg = true)
        {
            BitmapEncoder enc = jpeg ? (BitmapEncoder)new JpegBitmapEncoder { QualityLevel = 92 } : new PngBitmapEncoder();
            BitmapSource src = bmp;
            if (jpeg)
            {
                // JPEG has no transparency: flatten onto the window background
                var dv = new DrawingVisual();
                using (var dc = dv.RenderOpen())
                {
                    dc.DrawRectangle((Brush)FindResource("BgBrush"), null, new Rect(0, 0, bmp.Width, bmp.Height));
                    dc.DrawImage(bmp, new Rect(0, 0, bmp.Width, bmp.Height));
                }
                var flat = new RenderTargetBitmap(bmp.PixelWidth, bmp.PixelHeight, bmp.DpiX, bmp.DpiY, PixelFormats.Pbgra32);
                flat.Render(dv); src = flat;
            }
            enc.Frames.Add(BitmapFrame.Create(src));
            using (var f = File.Create(Path.Combine(tourDir, name + (jpeg ? ".jpg" : ".png")))) enc.Save(f);
        }

        void Snap(string name, double scale = TourScale) => Save(Render((FrameworkElement)Content, scale), name);

        void SnapWith(string name, FrameworkElement overlay, Point at)
        {
            var root = (FrameworkElement)Content;
            var a = Render(root, TourScale); var b = Render(overlay, TourScale);
            var dv = new DrawingVisual();
            using (var dc = dv.RenderOpen())
            {
                dc.DrawImage(a, new Rect(0, 0, a.Width, a.Height));
                dc.DrawImage(b, new Rect(at.X, at.Y, b.Width, b.Height));
            }
            var outBmp = new RenderTargetBitmap(a.PixelWidth, a.PixelHeight, 96 * TourScale, 96 * TourScale, PixelFormats.Pbgra32);
            outBmp.Render(dv);
            Save(outBmp, name);
        }

        void SnapElement(string name, FrameworkElement el, Brush back = null)
        {
            var bmp = Render(el, TourScale);
            if (back == null) { Save(bmp, name, false); return; }
            var dv = new DrawingVisual();
            using (var dc = dv.RenderOpen())
            {
                dc.DrawRectangle(back, null, new Rect(0, 0, bmp.Width, bmp.Height));
                dc.DrawImage(bmp, new Rect(0, 0, bmp.Width, bmp.Height));
            }
            var o = new RenderTargetBitmap(bmp.PixelWidth, bmp.PixelHeight, bmp.DpiX, bmp.DpiY, PixelFormats.Pbgra32);
            o.Render(dv);
            Save(o, name);
        }
    }
}
