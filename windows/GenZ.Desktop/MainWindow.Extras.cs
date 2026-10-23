using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using GenZ.Controls;
using GenZ.Core;

namespace GenZ
{
    /// <summary>Sidebar space effects, scroll reveals, live volume, GEN-Z Home Theater and the optional online look-ups.</summary>
    public partial class MainWindow
    {
        void InitExtras()
        {
            SpaceFx.StarField(NavStars, Settings.Current.NavStarField);
            ThemeManager.Changed += () => SpaceFx.StarField(NavStars, Settings.Current.NavStarField);
            ScrollReveal.Attach(PageAnim, () => ScrollReveal.Leaves(AnimPanel));
            ScrollReveal.Attach(PageAbout, () => ScrollReveal.Leaves((Panel)PageAbout.Content));
            ScrollReveal.Attach(PageEq, () => ScrollReveal.Leaves((Panel)PageEq.Content));
            ScrollReveal.Attach(GridScroll, () => GridPanel.Children.OfType<FrameworkElement>().Concat(GridRows.Children.OfType<FrameworkElement>()));
            BuildTheater();
        }

        /// <summary>A new theme: everything built in code is rebuilt so no part keeps the old colours or fonts.</summary>
        void OnThemeChanged()
        {
            noTransition = true;
            try
            {
                if (view == View.Themes) RenderThemes(true);
                else ShowView(view, true);
            }
            finally { noTransition = false; }
            UpdateToggles();
            UpdateFav(Player.Current ?? shownSong);
            BuildEq();
            BuildTheater();
            BuildNavPlaylists();
            if (shownSong != null) LoadLyrics(shownSong);
            lastPlaying = null; OnPlayerState();
        }

        // ================================================================ sidebar: space click effects
        void OnNavClickFx(object sender, MouseButtonEventArgs e)
        {
            var fx = Core.Perf.Light ? "none" : Settings.Current.NavEffect;
            if (fx == "none") return;
            var layer = AdornerLayerCanvas();
            var at = e.GetPosition(layer);
            // start once the click's page change has been built and drawn – otherwise the first part of the
            // effect plays while the new page is being made and the effect looks like it never happened
            Dispatcher.BeginInvoke(new Action(() => SpaceFx.Play(layer, at, fx)), System.Windows.Threading.DispatcherPriority.ContextIdle);
        }

        // ================================================================ live volume: glow and speaker follow the music
        double volPulse;
        void VolumeTick()
        {
            double lvl = Settings.Current.LiveVolume && !Core.Perf.Light && Player.IsPlaying && !Settings.Current.ReduceMotion ? Player.Level * Math.Max(0.15, Player.Volume) : 0;
            volPulse = Math.Max(Math.Min(1, lvl * 1.3), volPulse * 0.86);
            Live(NpVolume, NpVolGlow, NpVolIcon);
            if (BarVolumeBox.Visibility == Visibility.Visible) Live(BarVolume, BarVolGlow, BarVolIcon);
        }

        /// <summary>Changing the volume always answers with a glow along the bar and a speaker pop – playing or not.</summary>
        void VolumeTouched()
        {
            if (!Settings.Current.LiveVolume || Settings.Current.ReduceMotion || Core.Perf.Light) return;
            volPulse = Math.Max(volPulse, 0.55);
            if (Player.IsPlaying) return;   // the per-frame tick takes it from here
            volPulse = 0;
            foreach (var (s, glow, icon) in new[] { (NpVolume, NpVolGlow, NpVolIcon), (BarVolume, BarVolGlow, BarVolIcon) })
            {
                if (!s.IsVisible) continue;
                Live(s, glow, icon);
                var dur = TimeSpan.FromMilliseconds(650);
                glow.BeginAnimation(OpacityProperty, new DoubleAnimation(0.9, 0, dur) { FillBehavior = FillBehavior.Stop });
                if (glow.RenderTransform is ScaleTransform gs)
                    gs.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(2.2, 1, dur) { FillBehavior = FillBehavior.Stop, EasingFunction = new CubicEase() });
                if (icon.RenderTransform is ScaleTransform st)
                {
                    var pop = new DoubleAnimation(1.3, 1, dur) { FillBehavior = FillBehavior.Stop, EasingFunction = new ElasticEase { Oscillations = 1, Springiness = 4 } };
                    st.BeginAnimation(ScaleTransform.ScaleXProperty, pop); st.BeginAnimation(ScaleTransform.ScaleYProperty, pop);
                }
            }
        }

        void Live(Slider s, Border glow, Path icon)
        {
            // only transforms change every frame – no layout work
            double frac = (s.Value - s.Minimum) / Math.Max(0.0001, s.Maximum - s.Minimum);
            if (Math.Abs(glow.Width - s.ActualWidth) > 0.5) glow.Width = Math.Max(0, s.ActualWidth);
            if (!(glow.RenderTransform is ScaleTransform gs) || gs.IsFrozen) { gs = new ScaleTransform(1, 1, 0, 3.5); glow.RenderTransform = gs; }
            gs.ScaleX = Math.Max(0.001, frac);
            gs.ScaleY = 1 + volPulse * 2.4;
            glow.Opacity = volPulse > 0.01 ? Math.Min(1, 0.4 + volPulse * 0.9) : 0;
            var st = (ScaleTransform)icon.RenderTransform;
            st.ScaleX = st.ScaleY = 1 + volPulse * 0.28;
        }

        // ================================================================ favourite: full-window heart celebration (as on Android)
        Controls.Celebration celebration;

        void Celebrate(bool on)
        {
            if (Settings.Current.ReduceMotion) return;
            if (celebration == null)
            {
                celebration = new Controls.Celebration();
                Grid.SetRowSpan(celebration, 4);
                Panel.SetZIndex(celebration, 1000);
                Root.Children.Add(celebration);
            }
            if (Application.Current.Resources["AccentColor"] is Color a) celebration.SetColors(a, ((SolidColorBrush)FindResource("NpTextBrush")).Color);
            FrameworkElement at = NpColumn.Width.Value > 0 && NpCoverHost.IsVisible ? (FrameworkElement)NpCoverHost : BarCover;
            var p = at.TranslatePoint(new Point(at.ActualWidth / 2, at.ActualHeight / 2), celebration);
            double size = Math.Max(at.ActualWidth, 160);
            celebration.Style = Core.Perf.Light ? "simple" : Settings.Current.LikeEffect;
            if (on) celebration.Celebrate(p, size); else celebration.Unlove(p, size);
        }

        /// <summary>The heart button jumps, spins a little and settles (Android heartPop).</summary>
        static void HeartPop(FrameworkElement v, bool on)
        {
            var st = new ScaleTransform(1, 1); var rt = new RotateTransform();
            var g = new TransformGroup(); g.Children.Add(st); g.Children.Add(rt);
            v.RenderTransformOrigin = new Point(0.5, 0.5); v.RenderTransform = g;
            var up = new DoubleAnimationUsingKeyFrames { Duration = TimeSpan.FromMilliseconds(560) };
            up.KeyFrames.Add(new EasingDoubleKeyFrame(on ? 1.6 : 0.7, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(on ? 140 : 120))));
            up.KeyFrames.Add(new EasingDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(560)), new BackEase { Amplitude = 0.9, EasingMode = EasingMode.EaseOut }));
            st.BeginAnimation(ScaleTransform.ScaleXProperty, up); st.BeginAnimation(ScaleTransform.ScaleYProperty, up);
            if (on)
            {
                var spin = new DoubleAnimationUsingKeyFrames { Duration = TimeSpan.FromMilliseconds(560) };
                spin.KeyFrames.Add(new EasingDoubleKeyFrame(-12, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(140))));
                spin.KeyFrames.Add(new EasingDoubleKeyFrame(0, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(560)), new BackEase { Amplitude = 0.9, EasingMode = EasingMode.EaseOut }));
                rt.BeginAnimation(RotateTransform.AngleProperty, spin);
            }
        }

        // ================================================================ GEN-Z Home Theater
        readonly Dictionary<string, Slider> theaterSliders = new Dictionary<string, Slider>();
        bool theaterUpdating;

        void BuildTheater()
        {
            var c = Settings.Current;
            if (c.TheaterCustom == null) c.TheaterCustom = Theater.Profiles.First(p => p.Id == "custom").Copy();
            TheaterPanel.Children.Clear(); theaterSliders.Clear();

            var card = new Border { CornerRadius = new CornerRadius(18), Padding = new Thickness(20, 18, 20, 18) };
            card.SetResourceReference(Border.BackgroundProperty, "SurfaceBrush");
            var body = new StackPanel();
            card.Child = body;

            var head = new Grid();
            var ht = new StackPanel();
            ht.Children.Add(new TextBlock { Text = "GEN-Z HOME THEATER", FontSize = 11, FontWeight = FontWeights.Bold, Foreground = (Brush)FindResource("AccentBrush") });
            var sub = new TextBlock { Text = "Surround, clear dialogue and steady volume – works only inside GEN-Z and switches off when you close it.", FontSize = 12, Margin = new Thickness(0, 3, 0, 0), TextWrapping = TextWrapping.Wrap, MaxWidth = 520 };
            sub.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
            ht.Children.Add(sub);
            head.Children.Add(ht);
            var sw = new CheckBox { Style = (Style)FindResource("Switch"), IsChecked = c.TheaterOn, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center, ToolTip = "Home Theater on / off" };
            sw.Checked += (s, e) => { c.TheaterOn = true; Settings.Save(); Player.ApplyTheater(); ShowToast("Home Theater on · " + Theater.Profiles.First(p => p.Id == c.TheaterProfile).Name); };
            sw.Unchecked += (s, e) => { c.TheaterOn = false; Settings.Save(); Player.ApplyTheater(); ShowToast("Home Theater off"); };
            head.Children.Add(sw);
            body.Children.Add(head);

            // profile cards
            var cards = new WrapPanel { Margin = new Thickness(0, 14, 0, 0) };
            foreach (var p in Theater.Profiles)
            {
                var prof = p;
                bool on = p.Id == c.TheaterProfile;
                var b = new Border { Width = 128, Height = 92, CornerRadius = new CornerRadius(14), Margin = new Thickness(0, 0, 10, 10), Cursor = Cursors.Hand, Padding = new Thickness(12, 10, 12, 10), BorderThickness = new Thickness(on ? 2 : 1), ToolTip = p.Blurb };
                b.SetResourceReference(Border.BackgroundProperty, on ? "AccentSoftBrush" : "BgBrush");
                b.SetResourceReference(Border.BorderBrushProperty, on ? "AccentBrush" : "DividerBrush");
                var sp = new StackPanel();
                var ic = new Path { Data = (Geometry)FindResource(p.Icon), Width = 26, Height = 26, Stretch = Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Left };
                ic.SetResourceReference(Shape.FillProperty, "AccentBrush");
                sp.Children.Add(ic);
                sp.Children.Add(new TextBlock { Text = p.Name, FontSize = 15, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 8, 0, 0) });
                var bl = new TextBlock { Text = p.Blurb, FontSize = 10.5, TextTrimming = TextTrimming.CharacterEllipsis };
                bl.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
                sp.Children.Add(bl);
                b.Child = sp;
                b.MouseLeftButtonUp += (s, e) =>
                {
                    c.TheaterProfile = prof.Id;
                    if (!c.TheaterOn) c.TheaterOn = true;
                    Settings.Save(); Player.ApplyTheater();
                    ShowToast("Home Theater · " + prof.Name);
                    BuildTheater();
                };
                Bounce.Press(b);
                cards.Children.Add(b);
            }
            body.Children.Add(cards);

            // output: headphones or speakers
            var outRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 4, 0, 6) };
            foreach (var o in new[] { (true, "Headphones", "IcHeadphones"), (false, "Speakers", "IcSpeaker") })
            {
                var hp = o.Item1;
                bool on = c.TheaterHeadphones == hp;
                var btn = new Button { Style = (Style)FindResource(on ? "PillButton" : "GhostPill"), Margin = new Thickness(0, 0, 8, 0), Padding = new Thickness(14, 6, 14, 6) };
                var bsp = new StackPanel { Orientation = Orientation.Horizontal };
                var bi = new Path { Data = (Geometry)FindResource(o.Item3), Width = 14, Height = 14, Stretch = Stretch.Uniform, Margin = new Thickness(0, 0, 8, 0) };
                bi.SetResourceReference(Shape.FillProperty, on ? "OnAccentBrush" : "AccentBrush");
                var bt = new TextBlock { Text = o.Item2 };
                bt.SetResourceReference(TextBlock.ForegroundProperty, on ? "OnAccentBrush" : "AccentBrush");
                bsp.Children.Add(bi); bsp.Children.Add(bt); btn.Content = bsp;
                btn.Click += (s, e) => { c.TheaterHeadphones = hp; Settings.Save(); Player.ApplyTheater(); BuildTheater(); };
                outRow.Children.Add(btn);
            }
            body.Children.Add(outRow);

            // fine tuning (edits the Custom profile; touching a slider switches to Custom)
            var cur = c.TheaterProfile == "custom" ? c.TheaterCustom : Theater.Profiles.First(p => p.Id == c.TheaterProfile);
            var grid = new Grid { Margin = new Thickness(0, 8, 0, 0) };
            grid.ColumnDefinitions.Add(new ColumnDefinition()); grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(24) }); grid.ColumnDefinitions.Add(new ColumnDefinition());
            var specs = new (string key, string label, double min, double max, double val, Func<double, string> fmt)[]
            {
                ("width", "Surround width", 1, 2, cur.Width, v => v <= 1.01 ? "Off" : "+" + Math.Round((v - 1) * 100) + "%"),
                ("room", "Room ambience", 0, 1, cur.Room, v => Math.Round(v * 100) + "%"),
                ("dialogue", "Dialogue enhancer", 0, 8, cur.Dialogue, v => v < 0.1 ? "Off" : "+" + v.ToString("0.0") + " dB"),
                ("leveler", "Volume leveller", 0, 1, cur.Leveler, v => v < 0.01 ? "Off" : Math.Round(v * 100) + "%"),
                ("bass", "Bass", -6, 9, cur.Bass, v => (v > 0 ? "+" : "") + v.ToString("0.0") + " dB"),
                ("treble", "Treble", -6, 9, cur.Treble, v => (v > 0 ? "+" : "") + v.ToString("0.0") + " dB"),
            };
            for (int i = 0; i < specs.Length; i++)
            {
                var sp = specs[i];
                if (i % 2 == 0) grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                var cell = new StackPanel { Margin = new Thickness(0, 4, 0, 6) };
                var top = new Grid();
                top.Children.Add(new TextBlock { Text = sp.label, FontSize = 12.5 });
                var val = new TextBlock { Text = sp.fmt(sp.val), FontSize = 12, HorizontalAlignment = HorizontalAlignment.Right };
                val.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
                top.Children.Add(val);
                cell.Children.Add(top);
                var sl = new Slider { Style = (Style)FindResource("ThinSlider"), Minimum = sp.min, Maximum = sp.max, Value = sp.val, Margin = new Thickness(0, 4, 0, 0) };
                var key = sp.key; var fmt = sp.fmt;
                sl.ValueChanged += (s, e) =>
                {
                    val.Text = fmt(e.NewValue);
                    if (theaterUpdating) return;
                    if (c.TheaterProfile != "custom")
                    {
                        // start the custom mix from the profile you were on
                        var from = Theater.Profiles.First(p => p.Id == c.TheaterProfile).Copy();
                        from.Id = "custom"; from.Name = "Custom"; from.Icon = "IcSettings"; from.Blurb = "Your own mix – move the sliders";
                        c.TheaterCustom = from; c.TheaterProfile = "custom";
                    }
                    var t = c.TheaterCustom;
                    switch (key)
                    {
                        case "width": t.Width = e.NewValue; break;
                        case "room": t.Room = e.NewValue; break;
                        case "dialogue": t.Dialogue = e.NewValue; break;
                        case "leveler": t.Leveler = e.NewValue; break;
                        case "bass": t.Bass = e.NewValue; break;
                        case "treble": t.Treble = e.NewValue; break;
                    }
                    if (!c.TheaterOn) { c.TheaterOn = true; sw.IsChecked = true; }
                    Player.ApplyTheater();
                };
                sl.PreviewMouseLeftButtonUp += (s, e) => { Settings.Save(); if (cards.Children.Count > 0) RefreshTheaterCards(cards); };
                theaterSliders[key] = sl;
                cell.Children.Add(sl);
                Grid.SetRow(cell, i / 2); Grid.SetColumn(cell, (i % 2) * 2);
                grid.Children.Add(cell);
            }
            body.Children.Add(grid);
            TheaterPanel.Children.Add(card);
        }

        void RefreshTheaterCards(WrapPanel cards)
        {
            var c = Settings.Current;
            int i = 0;
            foreach (Border b in cards.Children)
            {
                bool on = Theater.Profiles[i++].Id == c.TheaterProfile;
                b.SetResourceReference(Border.BackgroundProperty, on ? "AccentSoftBrush" : "BgBrush");
                b.SetResourceReference(Border.BorderBrushProperty, on ? "AccentBrush" : "DividerBrush");
                b.BorderThickness = new Thickness(on ? 2 : 1);
            }
        }

        // ================================================================ online cover art, lyrics and track info (optional)
        int onlineTicket;

        /// <summary>After a song starts: fetch a missing (or better) cover and missing lyrics, if the user turned that on.</summary>
        async void AutoOnline(Song s)
        {
            var c = Settings.Current;
            if (s == null || (!c.OnlineCovers && !c.OnlineLyrics)) return;
            int ticket = ++onlineTicket;
            await Task.Delay(1500); // only for songs you actually listen to
            if (ticket != onlineTicket || s != Player.Current) return;
            try
            {
                if (c.OnlineCovers && !System.IO.File.Exists(Online.CoverFile(s)))
                {
                    int w = await Task.Run(() => Covers.EmbeddedWidth(s));
                    bool folderPic = w == 0 && await Task.Run(() => Covers.Load(s, 64) != null);
                    if ((w == 0 && !folderPic) || (c.OnlineHiResCovers && w > 0 && w < 600))
                    {
                        var file = await Online.FetchCover(s);
                        if (file != null && s == Player.Current) { Covers.Forget(s); LoadNpCover(s); ShowToast("Found high-quality cover art ♪"); }
                    }
                }
                if (c.OnlineLyrics && lyricsDoc.Empty && s == Player.Current)
                {
                    var text = await Online.FetchLyrics(s);
                    if (text != null && s == Player.Current) LoadLyrics(s);
                }
            }
            catch { }
        }

        /// <summary>Finds cover art and lyrics online for the chosen songs (menu: "Find cover & lyrics online").</summary>
        async void FetchOnline(List<Song> songs)
        {
            if (songs.Count == 0) return;
            ShowToast(songs.Count == 1 ? "Searching online for “" + songs[0].Title + "”…" : "Searching online for " + Songs(songs.Count) + "…");
            int covers = 0, lyrics = 0, done = 0;
            var albums = new HashSet<string>();
            foreach (var s in songs)
            {
                try
                {
                    if (albums.Add(s.AlbumKey + "|" + (string.IsNullOrEmpty(s.Album) ? s.Title : "")) && !System.IO.File.Exists(Online.CoverFile(s)))
                        if (await Online.FetchCover(s) != null) { covers++; Covers.Forget(s); }
                    if (!System.IO.File.Exists(Online.LyricsFile(s)) && await Online.FetchLyrics(s) != null) lyrics++;
                }
                catch { }
                done++;
                if (songs.Count > 3) ScanText.Text = $"Online: {done} of {songs.Count} songs · {covers} covers · {lyrics} lyrics";
            }
            UpdateScanText();
            ShowToast($"Online search done · {covers} new cover{(covers == 1 ? "" : "s")} · {lyrics} lyric{(lyrics == 1 ? "" : "s")}");
            var cur = Player.Current ?? shownSong;
            if (cur != null && songs.Contains(cur)) { LoadNpCover(cur); LoadLyrics(cur); }
            noTransition = true; try { ShowView(view, true); } finally { noTransition = false; }
        }

        void OnlinePrefs(Panel host)
        {
            var cur = Settings.Current;
            Section(host, "ONLINE – COVER ART, LYRICS & TRACK INFO (OPTIONAL)",
                "Off by default. When on, GEN-Z sends only a song's title, artist, album and length to Apple iTunes Search, MusicBrainz / Cover Art Archive and LRCLIB to find covers (up to 1200 × 1200), synced lyrics and track details. Nothing else leaves your PC.");
            host.Children.Add(Toggle("Find missing cover art online", "Downloads a high-quality cover when a song has none", cur.OnlineCovers, v => cur.OnlineCovers = v));
            host.Children.Add(Toggle("Replace small covers with high-quality ones", "Covers under 600 × 600 are upgraded (needs the option above)", cur.OnlineHiResCovers, v => cur.OnlineHiResCovers = v));
            host.Children.Add(Toggle("Find lyrics online", "Synced lyrics scroll with the music on Now Playing", cur.OnlineLyrics, v => cur.OnlineLyrics = v));
            var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 8, 0, 0) };
            var all = new Button { Style = (Style)FindResource("PillButton"), Content = "Find covers & lyrics for my whole library" };
            all.Click += (s, e) =>
            {
                if (!Dialogs.Confirm(this, "Search online", $"GEN-Z will look up {Library.Songs.Count} songs online (one album cover per album, lyrics per song). This runs in the background and can take a while – about 3 seconds per song.", "Start")) return;
                FetchOnline(Library.Songs.ToList());
            };
            var clear = new Button { Style = (Style)FindResource("GhostPill"), Content = "Delete downloaded covers & lyrics", Margin = new Thickness(10, 0, 0, 0) };
            clear.Click += (s, e) =>
            {
                if (!Dialogs.Confirm(this, "Delete downloads", "Delete all cover art and lyrics GEN-Z downloaded? Your music files are not touched.", "Delete")) return;
                try { foreach (var f in System.IO.Directory.GetFiles(Online.CoverDir)) System.IO.File.Delete(f); foreach (var f in System.IO.Directory.GetFiles(Online.LyricsDir)) System.IO.File.Delete(f); } catch { }
                foreach (var s2 in Library.Songs) Covers.Forget(s2);
                ShowToast("Downloaded covers and lyrics deleted");
                if (shownSong != null) { LoadNpCover(shownSong); LoadLyrics(shownSong); }
            };
            row.Children.Add(all); row.Children.Add(clear);
            host.Children.Add(row);
        }
    }
}
