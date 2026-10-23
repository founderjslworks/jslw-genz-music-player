using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Shell;
using System.Windows.Threading;
using GenZ.Core;

namespace GenZ
{
    public partial class MainWindow : Window
    {
        enum View { Songs, Albums, Artists, Favourites, Queue, Themes, Anim, Eq, About, Search, Detail, Genres, Folders, Recent, Most, Playlist, Flow }

        View view = View.Songs;
        List<Song> listed = new List<Song>();
        readonly DispatcherTimer timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(40) };
        bool seeking;
        bool? lastPlaying;
        Song shownSong;
        bool quietNav;
        Image transOld;

        public MainWindow()
        {
            InitializeComponent();
            NpVolume.Value = Settings.Current.Volume;
            BarVolume.Value = Settings.Current.Volume;
            if (!Settings.Current.NowPlayingOpen) NpColumn.Width = new GridLength(0);
            AboutTitle.Text = "GEN-Z Music for Windows " + typeof(MainWindow).Assembly.GetName().Version.ToString(3);

            foreach (var bar in new[] { NpBar, BarBar })
            {
                bar.Scrubbing += f => { seeking = true; ShowTimes(TimeSpan.FromMilliseconds(f * Player.Duration.TotalMilliseconds)); };
                bar.Seek += f => { Player.Position = TimeSpan.FromMilliseconds(f * Player.Duration.TotalMilliseconds); seeking = false; };
            }

            Player.TrackChanged += OnTrackChanged;
            Player.StateChanged += OnPlayerState;
            Library.Changed += OnLibraryChanged;
            ThemeManager.Changed += OnThemeChanged;
            // progress, lyrics and live effects update every frame (smooth on 120 / 144 / 240 Hz screens) while music plays,
            // and a few times a second when paused
            timer.Interval = TimeSpan.FromMilliseconds(250);
            timer.Tick += (s, e) => { if (!frameHooked) Tick(); };
            timer.Start();
            Player.StateChanged += HookFrames;

            StateChanged += (s, e) =>
            {
                Root.Margin = new Thickness(0);
                HookFrames();
                MaxBtn.Tag = FindResource(WindowState == WindowState.Maximized ? "TbRestore" : "TbMax");
                MaxBtn.ToolTip = WindowState == WindowState.Maximized ? "Restore" : "Maximize";
                if (WindowState == WindowState.Minimized && Settings.Current.AutoPip && Player.IsPlaying && pip == null) EnterPip(false);
                else if (WindowState != WindowState.Minimized && pip != null) { pip.Close(); pip = null; }
            };
            SizeChanged += (s, e) => ApplyScale();
            NpPanel.SizeChanged += (s, e) => FitCover();
            NpInfo.SizeChanged += (s, e) => FitCover();
            NpControls.SizeChanged += (s, e) => FitCover();
            Loaded += (s, e) => { ApplyScale(); FitCover(); };
            FitWindowToScreen();
            PreviewKeyDown += OnKeys;
            PreviewMouseDown += (s, e) => { if (e.ChangedButton == MouseButton.XButton1 && view == View.Detail) { OnBack(this, null); e.Handled = true; } };
            SourceInitialized += (s, e) => RegisterMediaKeys();
            Closing += (s, e) => { Settings.Current.NowPlayingOpen = NpColumn.Width.Value > 0; SaveSession(); };

            BuildEq();
            SetThumbIcons();
            Library.LoadCache();
            InitFeatures();
            InitExtras();
            NavSongs.IsChecked = true;
            ShowView(View.Songs);
            UpdateScanText();
            UpdateToggles();
            // the window appears first; opening the audio device and re-scanning the folders happen right after
            ContentRendered += (s, e) =>
            {
                Dispatcher.BeginInvoke(new Action(() =>
                {
                    // restore the last session (queue, song and position, paused) so pressing play continues where you left off
                    if (!RestoreSession())
                    {
                        var last = Library.Songs.FirstOrDefault(x => x.Path == Settings.Current.LastSong);
                        if (last != null) { ShowSong(last); LoadLyrics(last); }
                    }
                    UpdateToggles();
                }), DispatcherPriority.ApplicationIdle);
                var later = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1200) };
                later.Tick += (o, a) => { later.Stop(); Library.ScanAsync(); UpdateScanText(); };
                later.Start();
            };
        }

        // ================================================================ library & navigation
        void OnLibraryChanged()
        {
            UpdateScanText();
            if (view == View.Songs || view == View.Albums || view == View.Artists || view == View.Favourites || view == View.Genres
                || view == View.Folders || view == View.Recent || view == View.Most || view == View.Playlist) ShowView(view, true);
        }

        void UpdateScanText()
        {
            ScanText.Text = Library.Scanning ? "Scanning your music…" :
                $"{Library.Songs.Count} songs · {Library.Albums.Count} albums";
        }

        void OnNav(object sender, RoutedEventArgs e)
        {
            if (!IsInitialized) return;
            if (sender == NavSongs) ShowView(View.Songs);
            else if (sender == NavAlbums) ShowView(View.Albums);
            else if (sender == NavFlow) { ShowView(View.Flow); FlowShowPlaying(true); }
            else if (sender == NavArtists) ShowView(View.Artists);
            else if (sender == NavFav) ShowView(View.Favourites);
            else if (sender == NavGenres) ShowView(View.Genres);
            else if (sender == NavFolders) ShowView(View.Folders);
            else if (sender == NavRecent) ShowView(View.Recent);
            else if (sender == NavMost) ShowView(View.Most);
            else if (sender == NavQueue) ShowView(View.Queue);
            else if (sender == NavThemes) ShowView(View.Themes);
            else if (sender == NavAnim) ShowView(View.Anim);
            else if (sender == NavEq) ShowView(View.Eq);
            else if (sender == NavAbout) ShowView(View.About);
        }

        /// <summary>Shows a page with the chosen Android-style screen transition (the old screen animates out).</summary>
        void ShowPage(FrameworkElement page)
        {
            var host = (Panel)Pages.Parent;
            if (!quietNav && !noTransition && transOld == null)
            {
                var vis = Pages.Children.OfType<FrameworkElement>().FirstOrDefault(p => p.Visibility == Visibility.Visible);
                if (vis != null && vis != page) transOld = Controls.Transitions.Capture(Pages, host);
            }
            foreach (FrameworkElement p in Pages.Children) p.Visibility = p == page ? Visibility.Visible : Visibility.Collapsed;
            var old = transOld; transOld = null;
            if (quietNav || noTransition) { if (old != null) host.Children.Remove(old); return; }
            Pages.ClipToBounds = true;
            Controls.Transitions.Run(Settings.Current.Transition, page, old, host, Pages);
            if (page is ScrollViewer sv && sv.Content is Panel form) Controls.Stagger.Enter(form);
        }

        void ShowView(View v, bool quiet = false)
        {
            if (Pages == null) return;
            quietNav = quiet;
            // capture the screen first: the song list page is reused for songs, favourites, playlists…
            if (!quiet && !noTransition && transOld == null && Pages.IsVisible) transOld = Controls.Transitions.Capture(Pages, (Panel)Pages.Parent);
            view = v;
            try
            {
                switch (v)
                {
                    case View.Songs: ShowList("Songs", $"{Library.Songs.Count} songs", SortSongs(Library.Songs, "songs", "title"), null); break;
                    case View.Search:
                        var found = Library.Search(SearchBox.Text).ToList();
                        ShowList("Search", $"{found.Count} results for “{SearchBox.Text.Trim()}”", SortSongs(found, "search", "title"), null); break;
                    case View.Detail: if (reshowDetail != null) reshowDetail(); else ShowView(View.Songs, quiet); break;
                    case View.Favourites:
                        var fav = Library.Songs.Where(s => Settings.Current.Favourites.Contains(s.Path)).ToList();
                        ShowList("Favourites", fav.Count == 0 ? "Click the heart on Now Playing, or right-click a song › Add to Favourites" : $"{fav.Count} songs", SortSongs(fav, "favs", "title"), null); break;
                    case View.Queue: ShowList("Up next", Player.Queue.Count == 0 ? "Nothing queued yet – right-click songs › Play next / Add to Up next" : $"{Player.Queue.Count} songs · drag to reorder", Player.Queue, null); break;
                    case View.Recent:
                        var rec = RecentSongs();
                        ShowList("Recently played", rec.Count == 0 ? "Songs you play show up here" : Songs(rec.Count), rec, null); break;
                    case View.Most:
                        var most = MostPlayed();
                        ShowList("Most played", most.Count == 0 ? "Your favourite songs, counted as you listen" : "Your top " + Songs(most.Count), most, null); break;
                    case View.Playlist: ShowPlaylist(openPlaylist); break;
                    case View.Albums: ShowAlbums(); break;
                    case View.Flow: ShowFlow(); break;
                    case View.Artists: ShowArtists(); break;
                    case View.Genres: ShowGroups("Genres", Library.Genres, false); break;
                    case View.Folders: ShowGroups("Folders", Library.Folders, true); break;
                    case View.Themes: ShowThemes(); break;
                    case View.Anim: BuildAnimPage(); ShowPage(PageAnim); break;
                    case View.Eq: ShowPage(PageEq); if (!quiet && !noTransition) EqIntro(); break;
                    case View.About: ShowAbout(); break;
                }
            }
            finally
            {
                quietNav = false;
                if (transOld != null) { ((Panel)Pages.Parent).Children.Remove(transOld); transOld = null; }
            }
        }

        void ShowList(string title, string sub, List<Song> songs, Song coverOf)
        {
            var sv = quietNav ? Controls.ListAnim.FindChild<ScrollViewer>(SongList) : null;
            double keep = sv?.VerticalOffset ?? 0;
            listed = songs;
            ListTitle.Text = title; ListSub.Text = sub;
            if (quietNav && ReferenceEquals(SongList.ItemsSource, songs)) SongList.Items.Refresh();
            else { SongList.ItemsSource = null; SongList.ItemsSource = songs; }
            if (sv != null && keep > 0) Dispatcher.BeginInvoke(new Action(() => sv.ScrollToVerticalOffset(keep)), DispatcherPriority.Loaded);
            ListEmpty.Text = songs.Count == 0 ? sub : "";
            ListEmpty.Visibility = songs.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            ListMore.Visibility = view == View.Queue || view == View.Playlist ? Visibility.Visible : Visibility.Collapsed;
            ApplySongMode();
            if (coverOf != null)
            {
                ListCover.Visibility = Visibility.Visible;
                ListCover.Background = (Brush)FindResource("SurfaceBrush");
                SetCoverAsync(ListCover, coverOf, 200);
            }
            else ListCover.Visibility = Visibility.Collapsed;
            ShowPage(PageList);
            if (!quietNav) CascadeList();
        }

        void OnSearch(object sender, TextChangedEventArgs e)
        {
            SearchHint.Visibility = string.IsNullOrEmpty(SearchBox.Text) ? Visibility.Visible : Visibility.Collapsed;
            if (string.IsNullOrWhiteSpace(SearchBox.Text)) { if (view == View.Search) ShowView(View.Songs, true); return; }
            bool first = view != View.Search;
            ShowView(View.Search, !first);
        }

        void OnSongDoubleClick(object sender, MouseButtonEventArgs e) { }

        /// <summary>One click on a song plays it (like a tap on the phone).</summary>
        void OnSongClick(object sender, MouseButtonEventArgs e)
        {
            ReleasePress();
            if (dragged || (Keyboard.Modifiers & (ModifierKeys.Control | ModifierKeys.Shift)) != 0) return;
            var el = e.OriginalSource as DependencyObject;
            while (el != null && !(el is ListBoxItem)) el = el is Visual ? VisualTreeHelper.GetParent(el) : LogicalTreeHelper.GetParent(el);
            if (el is ListBoxItem item && item.DataContext is Song s)
            {
                SongList.SelectedItem = s;
                PlayFromList(s);
            }
        }

        void OnSongKey(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter && SongList.SelectedItem is Song s) { PlayFromList(s); e.Handled = true; }
            else if (e.Key == Key.Delete && (view == View.Queue || view == View.Playlist)) { RemoveSelected(); e.Handled = true; }
        }

        void PlayFromList(Song s)
        {
            if (view == View.Queue) { Player.PlayAt(Player.Queue.IndexOf(s)); return; }
            Player.PlayList(listed, listed.IndexOf(s));
        }

        void OnPlayAll(object sender, RoutedEventArgs e)
        {
            if (listed.Count == 0) return;
            if (Settings.Current.Shuffle) Player.SetShuffle(false);
            Player.PlayList(listed, 0);
        }

        void OnShuffleAll(object sender, RoutedEventArgs e)
        {
            if (listed.Count == 0) return;
            Settings.Current.Shuffle = true;
            Player.PlayList(listed, new Random().Next(listed.Count));
            UpdateToggles();
        }

        // ---------------------------------------------------------------- album / artist grids
        void ShowGrid(string title, string sub)
        {
            GridTitle.Text = title; GridSub.Text = sub;
            GridPanel.Children.Clear(); GridRows.Children.Clear();
            GridHeader.Children.Clear(); GridHeader.ColumnDefinitions.Clear(); GridHeader.Visibility = Visibility.Collapsed;
            GridTools.Children.Clear();
            ShowPage(PageGrid);
        }

        FrameworkElement Tile(string title, string sub, Song coverOf, bool round, int size = 168)
        {
            var art = new Border { Width = size, Height = size, CornerRadius = new CornerRadius(round ? size / 2.0 : (size < 140 ? 12 : 16)), Background = (Brush)FindResource("SurfaceBrush") };
            art.Child = new Path { Data = (Geometry)FindResource(round ? "IcPerson" : "IcAlbum"), Fill = (Brush)FindResource("AccentBrush"), Width = size * 0.27, Height = size * 0.27, Stretch = Stretch.Uniform, Opacity = 0.7 };
            SetCoverAsync(art, coverOf, size * 2);
            var t1 = new TextBlock { Text = title, TextTrimming = TextTrimming.CharacterEllipsis, FontSize = size < 140 ? 13 : 14, FontWeight = FontWeights.SemiBold, Margin = new Thickness(2, 10, 2, 0), HorizontalAlignment = round ? HorizontalAlignment.Center : HorizontalAlignment.Left };
            var t2 = new TextBlock { Text = sub, TextTrimming = TextTrimming.CharacterEllipsis, FontSize = size < 140 ? 11.5 : 12, Margin = new Thickness(2, 2, 2, 0), Foreground = (Brush)FindResource("Text2Brush"), HorizontalAlignment = round ? HorizontalAlignment.Center : HorizontalAlignment.Left };

            var sp = new StackPanel { Width = size, Margin = new Thickness(size < 140 ? 9 : 12, 8, size < 140 ? 9 : 12, 14), Cursor = Cursors.Hand, Background = Brushes.Transparent };
            sp.Children.Add(art); sp.Children.Add(t1); sp.Children.Add(t2);
            var st = new ScaleTransform(1, 1); art.RenderTransformOrigin = new Point(0.5, 0.5); art.RenderTransform = st;
            sp.MouseEnter += (s, e) => { Spring(st, 1.04); };
            sp.MouseLeave += (s, e) => { Spring(st, 1.0); };
            Controls.Bounce.Press(sp);
            return sp;
        }

        static void Spring(ScaleTransform st, double to)
        {
            var a = new DoubleAnimation(to, TimeSpan.FromMilliseconds(260)) { EasingFunction = new BackEase { Amplitude = 0.4, EasingMode = EasingMode.EaseOut } };
            st.BeginAnimation(ScaleTransform.ScaleXProperty, a);
            st.BeginAnimation(ScaleTransform.ScaleYProperty, a);
        }

        async void SetCoverAsync(Border target, Song s, int size)
        {
            if (s == null) return;
            var bmp = await Covers.LoadAsync(s, size);
            if (bmp == null) return;
            target.Child = null;
            target.Background = Covers.Brush(bmp);
            target.Opacity = 0;
            target.BeginAnimation(OpacityProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(220)));
        }

        static string Cap(string s) => string.IsNullOrEmpty(s) ? "" : char.ToUpper(s[0]) + s.Substring(1);

        // ---------------------------------------------------------------- equalizer
        readonly List<Slider> eqSliders = new List<Slider>();
        readonly List<TextBlock> eqValues = new List<TextBlock>();
        bool eqUpdating;

        void BuildEq()
        {
            EqOn.IsChecked = Settings.Current.EqEnabled;
            EqBands.ColumnDefinitions.Clear(); EqBands.Children.Clear();
            eqSliders.Clear(); eqValues.Clear();
            for (int i = 0; i < 10; i++)
            {
                EqBands.ColumnDefinitions.Add(new ColumnDefinition());
                var col = new Grid();
                col.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                col.RowDefinitions.Add(new RowDefinition());
                col.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                var val = new TextBlock { FontSize = 11, HorizontalAlignment = HorizontalAlignment.Center, Foreground = (Brush)FindResource("Text2Brush") };
                var sl = new Slider { Style = (Style)FindResource("VSlider"), Minimum = -12, Maximum = 12, Value = Settings.Current.EqGains[i], Margin = new Thickness(0, 6, 0, 6), HorizontalAlignment = HorizontalAlignment.Center, Width = 30 };
                var f = Player.Bands[i];
                var lab = new TextBlock { Text = f >= 1000 ? (f / 1000) + "k" : f.ToString(), FontSize = 11, HorizontalAlignment = HorizontalAlignment.Center, Foreground = (Brush)FindResource("Text2Brush") };
                Grid.SetRow(sl, 1); Grid.SetRow(lab, 2);
                col.Children.Add(val); col.Children.Add(sl); col.Children.Add(lab);
                Grid.SetColumn(col, i);
                EqBands.Children.Add(col);
                int band = i;
                sl.ValueChanged += (s, e) =>
                {
                    val.Text = (e.NewValue > 0 ? "+" : "") + e.NewValue.ToString("0");
                    if (eqUpdating) return;
                    Settings.Current.EqGains[band] = Math.Round(e.NewValue, 1);
                    Settings.Current.EqPreset = "Custom";
                    if (!Settings.Current.EqEnabled) EqOn.IsChecked = true;
                    Player.ApplyEq(); BuildPresets();
                };
                val.Text = (sl.Value > 0 ? "+" : "") + sl.Value.ToString("0");
                eqSliders.Add(sl); eqValues.Add(val);
            }
            BuildPresets();
        }

        void BuildPresets()
        {
            EqPresets.Children.Clear();
            foreach (var kv in Player.Presets)
            {
                var name = kv.Key; var gains = kv.Value;
                var b = new Button { Content = name, Style = (Style)FindResource(name == Settings.Current.EqPreset ? "PillButton" : "GhostPill"), Margin = new Thickness(0, 0, 8, 8), Padding = new Thickness(14, 6, 14, 6) };
                b.Click += (s, e) =>
                {
                    eqUpdating = true;
                    for (int i = 0; i < 10; i++) { Settings.Current.EqGains[i] = gains[i]; AnimateSlider(eqSliders[i], gains[i]); }
                    eqUpdating = false;
                    Settings.Current.EqPreset = name;
                    EqOn.IsChecked = true; Settings.Current.EqEnabled = true;
                    Player.ApplyEq(); BuildPresets();
                };
                EqPresets.Children.Add(b);
            }
        }

        static void AnimateSlider(Slider s, double to)
        {
            s.BeginAnimation(RangeBase_Value, null);
            var a = new DoubleAnimation(to, TimeSpan.FromMilliseconds(420)) { EasingFunction = new BackEase { Amplitude = 0.35, EasingMode = EasingMode.EaseOut }, FillBehavior = FillBehavior.Stop };
            a.Completed += (o, e) => s.Value = to;
            s.BeginAnimation(RangeBase_Value, a);
        }
        static readonly DependencyProperty RangeBase_Value = System.Windows.Controls.Primitives.RangeBase.ValueProperty;

        void OnEqToggle(object sender, RoutedEventArgs e)
        {
            Settings.Current.EqEnabled = EqOn.IsChecked == true;
            Player.ApplyEq();
        }

        // ---------------------------------------------------------------- settings & about
        void ShowAbout()
        {
            BuildPrefs();
            FolderList.Children.Clear();
            foreach (var f in Settings.Current.Folders.ToList())
            {
                var row = new Border { CornerRadius = new CornerRadius(12), Background = (Brush)FindResource("SurfaceBrush"), Padding = new Thickness(14, 8, 8, 8), Margin = new Thickness(0, 0, 0, 6) };
                var g = new Grid();
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                g.ColumnDefinitions.Add(new ColumnDefinition());
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                g.Children.Add(new Path { Data = (Geometry)FindResource("IcFolder"), Fill = (Brush)FindResource("AccentBrush"), Width = 18, Height = 18, Stretch = Stretch.Uniform });
                var t = new TextBlock { Text = f, Margin = new Thickness(12, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center, FontSize = 13 };
                Grid.SetColumn(t, 1); g.Children.Add(t);
                var rm = new Button { Style = (Style)FindResource("IconButton"), Tag = FindResource("IcClose"), ToolTip = "Remove", Width = 32, Height = 32 };
                var folder = f;
                rm.Click += (s, e) => { Settings.Current.Folders.Remove(folder); Settings.Save(); ShowAbout(); Library.ScanAsync(); UpdateScanText(); };
                Grid.SetColumn(rm, 2); g.Children.Add(rm);
                row.Child = g;
                FolderList.Children.Add(row);
            }
            ShowPage(PageAbout);
        }

        void OnAddFolder(object sender, RoutedEventArgs e)
        {
            using (var d = new System.Windows.Forms.FolderBrowserDialog { Description = "Choose a folder with your music", ShowNewFolderButton = false })
            {
                if (d.ShowDialog() != System.Windows.Forms.DialogResult.OK) return;
                if (!Settings.Current.Folders.Contains(d.SelectedPath, StringComparer.OrdinalIgnoreCase)) Settings.Current.Folders.Add(d.SelectedPath);
                Settings.Save();
                Library.ScanAsync(); UpdateScanText();
                if (view == View.About) ShowAbout();
            }
        }

        void OnRescan(object sender, RoutedEventArgs e) { Library.ScanAsync(); UpdateScanText(); }
        void OnWebsite(object sender, RoutedEventArgs e) => Open("https://www.jslworks.in/");
        void OnUpdates(object sender, RoutedEventArgs e) => Open("https://www.jslworks.in/android-devlopment");
        static void Open(string url) { try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); } catch { } }

        void OnLicences(object sender, RoutedEventArgs e) =>
            new TermsWindow("Open-source licences", TermsWindow.ReadNotices(), false) { Owner = this, WindowStartupLocation = WindowStartupLocation.CenterOwner }.ShowDialog();
        void OnTerms(object sender, RoutedEventArgs e) =>
            new TermsWindow("Terms of use", TermsWindow.ReadEula(), false) { Owner = this, WindowStartupLocation = WindowStartupLocation.CenterOwner }.ShowDialog();

        // ================================================================ playback UI
        void OnPlay(object sender, EventArgs e)
        {
            if (sender is FrameworkElement b) Ring(b);
            Player.Toggle();
        }
        void OnNext(object sender, EventArgs e)
        {
            Controls.Fx.Pop(sender as FrameworkElement ?? NpNext, 1); Ring(sender as FrameworkElement ?? NpNext);
            Skip(true); Player.Next();
        }
        void OnPrev(object sender, EventArgs e)
        {
            Controls.Fx.Pop(sender as FrameworkElement ?? NpPrev, -1); Ring(sender as FrameworkElement ?? NpPrev);
            Skip(false); Player.Prev();
        }

        /// <summary>A ring ripples out from a button.</summary>
        void Ring(FrameworkElement b)
        {
            if (b == null || Settings.Current.ReduceMotion || !b.IsVisible) return;
            var layer = AdornerLayerCanvas();
            var size = Math.Max(b.ActualWidth, b.ActualHeight);
            if (size <= 0) return;
            var c = b.TranslatePoint(new Point(b.ActualWidth / 2, b.ActualHeight / 2), layer);
            for (int k = 0; k < 2; k++)
            {
                var ring = new Ellipse { Width = size, Height = size, Stroke = (Brush)FindResource("AccentBrush"), StrokeThickness = 2.5 - k, IsHitTestVisible = false, Opacity = 0 };
                Canvas.SetLeft(ring, c.X - size / 2); Canvas.SetTop(ring, c.Y - size / 2);
                var st = new ScaleTransform(0.6, 0.6); ring.RenderTransformOrigin = new Point(0.5, 0.5); ring.RenderTransform = st;
                layer.Children.Add(ring);
                var delay = Settings.Ms(k * 120);
                var grow = new DoubleAnimation(0.6, 2.0, Settings.Ms(600)) { BeginTime = delay, EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } };
                st.BeginAnimation(ScaleTransform.ScaleXProperty, grow); st.BeginAnimation(ScaleTransform.ScaleYProperty, grow);
                var fade = new DoubleAnimation(0.85, 0, Settings.Ms(600)) { BeginTime = delay };
                var r = ring; fade.Completed += (o, a) => layer.Children.Remove(r);
                ring.BeginAnimation(OpacityProperty, fade);
            }
        }
        void OnShuffle(object sender, RoutedEventArgs e) { Player.SetShuffle(!Settings.Current.Shuffle); UpdateToggles(); }
        void OnRepeat(object sender, RoutedEventArgs e) { Player.CycleRepeat(); UpdateToggles(); }

        void OnVolume(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (!IsInitialized) return;
            Player.Volume = e.NewValue;
            if (sender != NpVolume && Math.Abs(NpVolume.Value - e.NewValue) > 0.001) NpVolume.Value = e.NewValue;
            if (sender != BarVolume && Math.Abs(BarVolume.Value - e.NewValue) > 0.001) BarVolume.Value = e.NewValue;
            VolumeTouched();
        }

        void OnFav(object sender, RoutedEventArgs e)
        {
            var s = Player.Current ?? shownSong;
            if (s == null) return;
            var favs = Settings.Current.Favourites;
            bool on = !favs.Contains(s.Path);
            if (on) favs.Add(s.Path); else favs.Remove(s.Path);
            Settings.Save();
            UpdateFav(s);
            HeartPop(NpFav, on);
            Celebrate(on);
            if (view == View.Favourites) ShowView(View.Favourites);
        }

        void UpdateFav(Song s)
        {
            bool on = s != null && Settings.Current.Favourites.Contains(s.Path);
            NpFav.Tag = FindResource(on ? "IcHeart" : "IcHeartOutline");
            NpFav.Foreground = on ? (Brush)FindResource("AccentBrush") : (Brush)FindResource("NpTextBrush");
        }

        int lastShownIndex = -1;
        void OnTrackChanged()
        {
            var s = Player.Current;
            if (Player.Error != null) ScanText.Text = Player.Error;
            bool forward = !(lastShownIndex >= 0 && Player.Index == lastShownIndex - 1);
            bool changed = s != shownSong;
            lastShownIndex = Player.Index;
            ShowSong(s);
            if (changed) { AnimateBar(forward); LoadLyrics(s); AutoOnline(s); }
            if (view == View.Queue) ShowView(View.Queue, true);
            SaveSession();
        }

        void ShowSong(Song s)
        {
            shownSong = s;
            NpTitle.Text = s?.Title ?? "Nothing playing";
            NpArtist.Text = s == null ? "Pick a song to start" : s.ArtistLine;
            BarTitle.Text = s?.Title ?? "GEN-Z Music";
            BarArtist.Text = s?.Artist ?? "Made for music lovers";
            Title = s == null ? "GEN-Z Music" : s.Title + " – " + s.Artist + " · GEN-Z Music";
            Taskbar.Description = Title;
            if (s != null)
            {
                NpBadgeText.Text = s.FormatBadge;
                NpBadge.Visibility = Visibility.Visible;
            }
            UpdateFav(s);
            LoadNpCover(s);
            BarTotal.Text = Library.Format((long)Player.Duration.TotalMilliseconds);
        }

        async void LoadNpCover(Song s)
        {
            NpCover.Background = (Brush)FindResource("SurfaceBrush");
            NpCover.Child = NpCoverNote;
            BarCover.Background = (Brush)FindResource("BgBrush");
            if (s == null) return;
            var big = await Covers.LoadAsync(s, 640);
            if (s != shownSong) return;
            if (big != null)
            {
                NpCover.Child = null;
                NpCover.Background = Covers.Brush(big);
                BarCover.Background = Covers.Brush(big);
                var avg = Covers.Average(big);
                NpGlow.Background = avg.HasValue ? new SolidColorBrush(avg.Value) : (Brush)FindResource("AccentBrush");
            }
            else NpGlow.Background = (Brush)FindResource("AccentBrush");
        }

        void RefreshCoverGlow() { if (shownSong != null) LoadNpCover(shownSong); }

        void OnPlayerState()
        {
            bool playing = Player.IsPlaying;
            if (lastPlaying != playing)
            {
                bool animate = lastPlaying.HasValue;
                lastPlaying = playing;
                var geo = (Geometry)FindResource(playing ? "IcPause" : "IcPlay");
                BarPlay.Tag = geo;
                if (animate) { Controls.Fx.Pop(BarPlay); Ring(BarPlay); }
                if (animate && Settings.Current.PlayMorph && !Settings.Current.ReduceMotion) MorphPlay(geo, playing); else NpPlayIcon.Data = geo;
                Taskbar.ProgressState = playing ? TaskbarItemProgressState.Normal : TaskbarItemProgressState.Paused;
                SetThumbIcons();
                // the cover "breathes": a little smaller while paused
                var st = (ScaleTransform)((TransformGroup)NpCover.RenderTransform).Children[0];
                var a = new DoubleAnimation(playing || !Settings.Current.CoverBreathe ? 1 : 0.93, Settings.Ms(420)) { EasingFunction = new BackEase { Amplitude = 0.3, EasingMode = EasingMode.EaseOut } };
                st.BeginAnimation(ScaleTransform.ScaleXProperty, a); st.BeginAnimation(ScaleTransform.ScaleYProperty, a);
            }
            NpBar.Playing = playing && Settings.Current.Sparkles && !Settings.Current.ReduceMotion && !Core.Perf.Light;
            BarBar.Playing = NpBar.Playing;
            UpdateToggles();
        }

        void UpdateToggles()
        {
            var on = (Brush)FindResource("AccentBrush");
            var off = (Brush)FindResource("NpTextBrush");
            NpShuffle.Foreground = Settings.Current.Shuffle ? on : off;
            NpShuffle.Opacity = Settings.Current.Shuffle ? 1 : 0.7;
            NpRepeat.Tag = FindResource(Settings.Current.Repeat == 2 ? "IcRepeatOne" : "IcRepeat");
            NpRepeat.Foreground = Settings.Current.Repeat > 0 ? on : off;
            NpRepeat.Opacity = Settings.Current.Repeat > 0 ? 1 : 0.7;
        }

        /// <summary>Play ↔ pause: the icon spins out and springs back as the other symbol; a ring bursts from the button.</summary>
        void MorphPlay(Geometry geo, bool playing)
        {
            var group = (TransformGroup)NpPlayIcon.RenderTransform;
            var sc = (ScaleTransform)group.Children[0];
            var rot = (RotateTransform)group.Children[1];
            var outA = new DoubleAnimation(0.2, TimeSpan.FromMilliseconds(110));
            outA.Completed += (s, e) =>
            {
                NpPlayIcon.Data = geo;
                var inA = new DoubleAnimation(0.2, 1, TimeSpan.FromMilliseconds(460)) { EasingFunction = new BackEase { Amplitude = 0.6, EasingMode = EasingMode.EaseOut } };
                sc.BeginAnimation(ScaleTransform.ScaleXProperty, inA); sc.BeginAnimation(ScaleTransform.ScaleYProperty, inA);
                rot.BeginAnimation(RotateTransform.AngleProperty, new DoubleAnimation(playing ? -90 : 90, 0, TimeSpan.FromMilliseconds(460)) { EasingFunction = new BackEase { Amplitude = 0.5, EasingMode = EasingMode.EaseOut } });
            };
            sc.BeginAnimation(ScaleTransform.ScaleXProperty, outA); sc.BeginAnimation(ScaleTransform.ScaleYProperty, outA);
            rot.BeginAnimation(RotateTransform.AngleProperty, new DoubleAnimation(playing ? 90 : -90, TimeSpan.FromMilliseconds(110)));
            // heartbeat of the whole button
            var bs = (ScaleTransform)NpPlay.RenderTransform;
            var beat = new DoubleAnimationUsingKeyFrames { Duration = TimeSpan.FromMilliseconds(620) };
            beat.KeyFrames.Add(new EasingDoubleKeyFrame(1.14, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(140))));
            beat.KeyFrames.Add(new EasingDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(620)), new ElasticEase { Oscillations = 1, Springiness = 5 }));
            bs.BeginAnimation(ScaleTransform.ScaleXProperty, beat); bs.BeginAnimation(ScaleTransform.ScaleYProperty, beat);
            // ring burst
            var ring = (ScaleTransform)NpRing.RenderTransform;
            var grow = new DoubleAnimation(playing ? 0.9 : 1.7, playing ? 1.75 : 0.95, TimeSpan.FromMilliseconds(650)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } };
            ring.BeginAnimation(ScaleTransform.ScaleXProperty, grow); ring.BeginAnimation(ScaleTransform.ScaleYProperty, grow);
            NpRing.BeginAnimation(OpacityProperty, new DoubleAnimation(0.9, 0, TimeSpan.FromMilliseconds(650)));
        }

        /// <summary>Next / previous: the cover flies out in that direction and the next one swings in.</summary>
        void Skip(bool forward)
        {
            if (Settings.Current.ReduceMotion) return;
            var tt = (TranslateTransform)((TransformGroup)NpCover.RenderTransform).Children[1];
            double d = forward ? -1 : 1;
            var outA = new DoubleAnimation(d * 160, TimeSpan.FromMilliseconds(150)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseIn } };
            outA.Completed += (s, e) =>
            {
                tt.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(-d * 160, 0, TimeSpan.FromMilliseconds(460)) { EasingFunction = new BackEase { Amplitude = 0.4, EasingMode = EasingMode.EaseOut } });
                NpCover.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(300)));
            };
            tt.BeginAnimation(TranslateTransform.XProperty, outA);
            NpCover.BeginAnimation(OpacityProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(150)));
            Burst(forward ? NpNext : NpPrev, 8);
        }

        /// <summary>Little sparkle burst around a control (favourite, skip).</summary>
        void Burst(FrameworkElement around, int count, Point? at = null)
        {
            if (Settings.Current.ReduceMotion) return;
            var layer = AdornerLayerCanvas();
            if (layer == null) return;
            var p = around.TranslatePoint(at ?? new Point(around.ActualWidth / 2, around.ActualHeight / 2), layer);
            var rnd = new Random();
            var accent = (Brush)FindResource("AccentBrush");
            for (int i = 0; i < count; i++)
            {
                double ang = rnd.NextDouble() * Math.PI * 2, dist = 30 + rnd.NextDouble() * 40;
                var dot = new Ellipse { Width = 6, Height = 6, Fill = i % 3 == 0 ? Brushes.White : accent, IsHitTestVisible = false };
                Canvas.SetLeft(dot, p.X - 3); Canvas.SetTop(dot, p.Y - 3);
                var tt = new TranslateTransform(); dot.RenderTransform = tt;
                layer.Children.Add(dot);
                var dur = TimeSpan.FromMilliseconds(600 + rnd.Next(300));
                tt.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(Math.Cos(ang) * dist, dur) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
                tt.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(Math.Sin(ang) * dist, dur) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
                var fade = new DoubleAnimation(1, 0, dur);
                fade.Completed += (s, e) => layer.Children.Remove(dot);
                dot.BeginAnimation(OpacityProperty, fade);
            }
        }

        Canvas fxLayer;
        Canvas AdornerLayerCanvas()
        {
            if (fxLayer != null) return fxLayer;
            fxLayer = new Canvas { IsHitTestVisible = false };
            Grid.SetRowSpan(fxLayer, 4);
            Panel.SetZIndex(fxLayer, 900);
            Root.Children.Add(fxLayer);
            return fxLayer;
        }

        bool frameHooked;
        TimeSpan smoothPos, lastRaw = TimeSpan.MinValue;
        DateTime smoothAt;
        int taskbarSkip;
        string lastElapsed, lastRemain, lastTotal;

        void HookFrames()
        {
            bool want = Player.IsPlaying && WindowState != WindowState.Minimized;
            if (want && !frameHooked) { CompositionTarget.Rendering += OnFrame; frameHooked = true; }
            else if (!want && frameHooked) { CompositionTarget.Rendering -= OnFrame; frameHooked = false; Tick(); }
        }

        void OnFrame(object sender, EventArgs e) => Tick();

        /// <summary>The audio position only moves in buffer-sized steps; glide between them so the bar moves every frame.</summary>
        TimeSpan SmoothPosition()
        {
            var raw = Player.Position;
            var now = DateTime.UtcNow;
            if (!Player.IsPlaying) { smoothPos = raw; smoothAt = now; lastRaw = raw; return raw; }
            var guess = smoothPos + (now - smoothAt);
            if (raw != lastRaw) { lastRaw = raw; if (Math.Abs((guess - raw).TotalMilliseconds) > 160) guess = raw; }
            if (guess < raw - TimeSpan.FromMilliseconds(160)) guess = raw;
            smoothPos = guess; smoothAt = now;
            return guess;
        }

        void Tick()
        {
            SleepTick();
            VolumeTick();
            var cur = Player.Current;
            if (cur == null) return;
            LyricsTick();
            var dur = Player.Duration; var pos = SmoothPosition();
            if (pos > dur && dur > TimeSpan.Zero) pos = dur;
            double f = dur.TotalMilliseconds > 0 ? pos.TotalMilliseconds / dur.TotalMilliseconds : 0;
            if (!seeking)
            {
                NpBar.Value = f; BarBar.Value = f;
                ShowTimes(pos);
            }
            if (Player.IsPlaying && ++taskbarSkip % 15 == 0) Taskbar.ProgressValue = f;
        }

        void ShowTimes(TimeSpan pos)
        {
            var dur = Player.Duration;
            // only touch the text when the second changes (text changes cost a layout pass)
            var el = Library.Format((long)pos.TotalMilliseconds);
            var rm = "-" + Library.Format((long)(dur - pos).TotalMilliseconds);
            var tt = Library.Format((long)dur.TotalMilliseconds);
            if (el != lastElapsed) { lastElapsed = el; NpElapsed.Text = BarElapsed.Text = el; }
            if (rm != lastRemain) { lastRemain = rm; NpRemain.Text = rm; }
            if (tt != lastTotal) { lastTotal = tt; BarTotal.Text = tt; }
        }

        // ================================================================ window, keys, taskbar
        void OnMin(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
        void OnMax(object sender, RoutedEventArgs e) => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
        void OnClose(object sender, RoutedEventArgs e) => Close();


        void OnKeys(object sender, KeyEventArgs e)
        {
            bool ctrl = (Keyboard.Modifiers & ModifierKeys.Control) != 0;
            if (FlowKeys(e)) { e.Handled = true; return; }
            if (e.Key == Key.F && ctrl) { SearchBox.Focus(); SearchBox.SelectAll(); e.Handled = true; }
            else if (e.Key == Key.P && ctrl) { OnPip(this, null); e.Handled = true; }
            else if ((e.Key == Key.Left && (Keyboard.Modifiers & ModifierKeys.Alt) != 0) || (e.SystemKey == Key.Left && (Keyboard.Modifiers & ModifierKeys.Alt) != 0)) { OnBack(this, null); e.Handled = true; }
            else if (e.Key == Key.L && ctrl) { OnLyricsToggle(this, null); e.Handled = true; }
            else if (e.Key == Key.I && ctrl) { EditSong(SongList.IsKeyboardFocusWithin && SongList.SelectedItem is Song sel ? sel : Player.Current ?? shownSong); e.Handled = true; }
            else if (e.Key == Key.N && ctrl) { OnNewPlaylist(this, null); e.Handled = true; }
            else if (SearchBox.IsKeyboardFocusWithin) { if (e.Key == Key.Escape) { SearchBox.Text = ""; SongList.Focus(); } }
            else if (e.Key == Key.Space) { Player.Toggle(); e.Handled = true; }
            else if (ctrl && e.Key == Key.Right) { OnNext(this, EventArgs.Empty); e.Handled = true; }
            else if (ctrl && e.Key == Key.Left) { OnPrev(this, EventArgs.Empty); e.Handled = true; }
            else if (e.Key == Key.MediaPlayPause) { Player.Toggle(); e.Handled = true; }
            else if (e.Key == Key.MediaNextTrack) { OnNext(this, EventArgs.Empty); e.Handled = true; }
            else if (e.Key == Key.MediaPreviousTrack) { OnPrev(this, EventArgs.Empty); e.Handled = true; }
        }

        void SetThumbIcons()
        {
            ImageSource Icon(string key)
            {
                var g = new GeometryDrawing(Brushes.White, null, (Geometry)FindResource(key));
                var group = new DrawingGroup();
                group.Children.Add(new GeometryDrawing(Brushes.Transparent, null, new RectangleGeometry(new Rect(0, 0, 24, 24))));
                group.Children.Add(g);
                var img = new DrawingImage(group); img.Freeze(); return img;
            }
            TbPrev.ImageSource = Icon("IcPrev");
            TbNext.ImageSource = Icon("IcNext");
            TbPlay.ImageSource = Icon(Player.IsPlaying ? "IcPause" : "IcPlay");
        }

        // global media keys (work while GEN-Z is in the background)
        [DllImport("user32.dll")] static extern bool RegisterHotKey(IntPtr hWnd, int id, uint mods, uint vk);
        [DllImport("user32.dll")] static extern bool UnregisterHotKey(IntPtr hWnd, int id);
        const int WM_HOTKEY = 0x0312;
        const int WM_GETMINMAXINFO = 0x0024;

        [StructLayout(LayoutKind.Sequential)] struct POINT { public int x, y; }
        [StructLayout(LayoutKind.Sequential)] struct MINMAXINFO { public POINT ptReserved, ptMaxSize, ptMaxPosition, ptMinTrackSize, ptMaxTrackSize; }
        [StructLayout(LayoutKind.Sequential)] struct RECT { public int left, top, right, bottom; }
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
        class MONITORINFO { public int cbSize = Marshal.SizeOf(typeof(MONITORINFO)); public RECT rcMonitor; public RECT rcWork; public int dwFlags; }
        [DllImport("user32.dll")] static extern IntPtr MonitorFromWindow(IntPtr hwnd, int flags);
        [DllImport("user32.dll", CharSet = CharSet.Auto)] static extern bool GetMonitorInfo(IntPtr hMonitor, [In, Out] MONITORINFO info);

        /// <summary>Maximized = exactly the screen's usable area (never under the taskbar), on any monitor.</summary>
        /// <summary>Set by the screenshot tour: lets the window be larger than a small build-machine screen.</summary>
        public static bool AllowLargeWindow;

        static void FitMaximized(IntPtr hwnd, IntPtr lParam)
        {
            var mmi = (MINMAXINFO)Marshal.PtrToStructure(lParam, typeof(MINMAXINFO));
            var mon = MonitorFromWindow(hwnd, 2 /* MONITOR_DEFAULTTONEAREST */);
            if (mon != IntPtr.Zero)
            {
                var info = new MONITORINFO();
                if (GetMonitorInfo(mon, info))
                {
                    RECT work = info.rcWork, area = info.rcMonitor;
                    mmi.ptMaxPosition.x = Math.Abs(work.left - area.left);
                    mmi.ptMaxPosition.y = Math.Abs(work.top - area.top);
                    mmi.ptMaxSize.x = Math.Abs(work.right - work.left);
                    mmi.ptMaxSize.y = Math.Abs(work.bottom - work.top);
                    mmi.ptMaxTrackSize = mmi.ptMaxSize;
                    if (AllowLargeWindow) { mmi.ptMaxTrackSize.x = 4000; mmi.ptMaxTrackSize.y = 3000; }
                }
            }
            Marshal.StructureToPtr(mmi, lParam, true);
        }

        void RegisterMediaKeys()
        {
            var h = new WindowInteropHelper(this).Handle;
            RegisterHotKey(h, 1, 0, 0xB3); // play / pause
            RegisterHotKey(h, 2, 0, 0xB0); // next
            RegisterHotKey(h, 3, 0, 0xB1); // previous
            RegisterHotKey(h, 4, 0, 0xB2); // stop
            HwndSource.FromHwnd(h)?.AddHook(WndProc);
            Closed += (s, e) => { for (int i = 1; i <= 4; i++) UnregisterHotKey(h, i); };
        }

        IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
        {
            if (msg == WM_GETMINMAXINFO) { FitMaximized(hwnd, lParam); handled = true; return IntPtr.Zero; }
            if (msg == WM_HOTKEY)
            {
                switch (wParam.ToInt32())
                {
                    case 1: Player.Toggle(); break;
                    case 2: OnNext(this, EventArgs.Empty); break;
                    case 3: OnPrev(this, EventArgs.Empty); break;
                    case 4: if (Player.IsPlaying) Player.Toggle(); break;
                }
                handled = true;
            }
            return IntPtr.Zero;
        }
    
        // ================================================================ scaling (fits small screens and high-DPI displays)
        void FitWindowToScreen()
        {
            var wa = SystemParameters.WorkArea;
            Width = Math.Min(Width, wa.Width * 0.92);
            Height = Math.Min(Height, wa.Height * 0.92);
            if (Width < MinWidth) MinWidth = Math.Max(640, Width);
            if (Height < MinHeight) MinHeight = Math.Max(480, Height);
        }

        double scale = 1;
        void ApplyScale()
        {
            double w = ActualWidth, h = ActualHeight;
            if (w <= 0 || h <= 0) return;
            double fit = Math.Min(w / 1240.0, h / 790.0);
            // automatic: shrink to fit small windows; on big screens (e.g. 4K at 100 %) grow, a little gentler, up to 175 %
            double s = Settings.Current.UiScale > 0 ? Settings.Current.UiScale
                : fit < 1 ? Math.Max(0.66, fit) : Math.Min(1.75, 1 + (fit - 1) * 0.55);
            var dpi = VisualTreeHelper.GetDpi(this);
            Covers.PixelScale = dpi.DpiScaleX * s;
            if (Math.Abs(s - scale) < 0.004 && Root.LayoutTransform is ScaleTransform) return;
            bool grew = s > scale + 0.05;
            scale = s;
            Root.LayoutTransform = Math.Abs(s - 1) < 0.004 ? Transform.Identity : new ScaleTransform(s, s);
            var chrome = WindowChrome.GetWindowChrome(this);
            if (chrome != null)
            {
                if (chrome.IsFrozen) { chrome = (WindowChrome)chrome.Clone(); chrome.CaptionHeight = 56 * s; WindowChrome.SetWindowChrome(this, chrome); }
                else chrome.CaptionHeight = 56 * s;
            }
            // covers decoded for a smaller size are reloaded at the new, sharper size
            if (grew && shownSong != null) LoadNpCover(shownSong);
        }

        protected override void OnDpiChanged(DpiScale oldDpi, DpiScale newDpi)
        {
            base.OnDpiChanged(oldDpi, newDpi);
            Covers.PixelScale = newDpi.DpiScaleX * scale;
            if (shownSong != null) LoadNpCover(shownSong);
        }

        /// <summary>The Now Playing cover grows and shrinks so the controls always fit.</summary>
        void FitCover()
        {
            // the cover gets whatever the title, progress and controls leave free – they are always fully visible
            double used = 26 + 18 + NpInfo.ActualHeight + NpInfo.Margin.Top + NpControls.ActualHeight + NpControls.Margin.Top + 8;
            double w = NpPanel.ActualWidth - 56, h = NpPanel.ActualHeight - used;
            double size = Math.Max(90, Math.Min(460, Math.Min(w, h)));
            if (Math.Abs(NpCoverHost.Width - size) > 0.5) NpCoverHost.Width = NpCoverHost.Height = size;
        }

        // ================================================================ animations
        void AnimateIn(FrameworkElement page, string kind)
        {
            page.BeginAnimation(OpacityProperty, null); page.Opacity = 1;
            page.RenderTransform = Transform.Identity;
            if (Settings.Current.ReduceMotion || kind == "none") return;
            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            page.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, Settings.Ms(260)));
            switch (kind)
            {
                case "slide-left":
                {
                    var tt = new TranslateTransform(36, 0); page.RenderTransform = tt;
                    tt.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(0, Settings.Ms(340)) { EasingFunction = ease });
                    break;
                }
                case "zoom":
                {
                    var st = new ScaleTransform(0.94, 0.94); page.RenderTransformOrigin = new Point(0.5, 0.4); page.RenderTransform = st;
                    var a = new DoubleAnimation(1, Settings.Ms(360)) { EasingFunction = new BackEase { Amplitude = 0.3, EasingMode = EasingMode.EaseOut } };
                    st.BeginAnimation(ScaleTransform.ScaleXProperty, a); st.BeginAnimation(ScaleTransform.ScaleYProperty, a);
                    break;
                }
                case "fade": break;
                default:
                {
                    var tt = new TranslateTransform(0, 18); page.RenderTransform = tt;
                    tt.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(0, Settings.Ms(340)) { EasingFunction = ease });
                    break;
                }
            }
        }

        /// <summary>Tiles bounce in one after another (Android Bounce.enter).</summary>
        void Cascade(Panel panel)
        {
            var items = panel.Children.OfType<FrameworkElement>().ToList();
            if (quietNav) { foreach (var i in items) i.Opacity = 1; return; }
            Controls.Bounce.Enter(items, 30, 40);
        }

        /// <summary>Song rows bounce in one after another (only the rows on screen).</summary>
        void CascadeList()
        {
            if (Settings.Current.ReduceMotion || Settings.Current.ListAnimation == "none") return;
            SongList.UpdateLayout();
            var rows = new List<FrameworkElement>();
            for (int i = 0; i < 26; i++)
            {
                if (!(SongList.ItemContainerGenerator.ContainerFromIndex(i) is FrameworkElement row)) break;
                rows.Add(row);
            }
            Controls.Bounce.Enter(rows, 41, 26);
        }

        // cover touch effects on Now Playing
        Canvas coverFx;
        void OnCoverClick(object sender, MouseButtonEventArgs e)
        {
            if (NpLyrics.Visibility == Visibility.Visible) return;
            if (Settings.Current.ReduceMotion || Settings.Current.CoverEffect == "none") return;
            if (coverFx == null)
            {
                coverFx = new Canvas { IsHitTestVisible = false, ClipToBounds = true };
                NpCoverHost.Children.Add(coverFx);
            }
            coverFx.Clip = new RectangleGeometry(new Rect(0, 0, NpCoverHost.ActualWidth, NpCoverHost.ActualHeight), 22, 22);
            var p = e.GetPosition(NpCoverHost);
            if (Settings.Current.CoverEffect == "burst") { Burst(NpCoverHost, 22, p); return; }
            var accent = (Color)FindResource("AccentColor");
            for (int k = 0; k < 4; k++)
            {
                var ring = new Ellipse { Width = 20, Height = 20, Stroke = new SolidColorBrush(k % 2 == 0 ? Colors.White : accent), StrokeThickness = 3 - k * 0.5 };
                Canvas.SetLeft(ring, p.X - 10); Canvas.SetTop(ring, p.Y - 10);
                var st = new ScaleTransform(1, 1); ring.RenderTransformOrigin = new Point(0.5, 0.5); ring.RenderTransform = st;
                coverFx.Children.Add(ring);
                var delay = Settings.Ms(k * 140);
                var grow = new DoubleAnimation(1, 14 + k * 2, Settings.Ms(1100)) { BeginTime = delay, EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } };
                st.BeginAnimation(ScaleTransform.ScaleXProperty, grow); st.BeginAnimation(ScaleTransform.ScaleYProperty, grow);
                var fade = new DoubleAnimation(0.9, 0, Settings.Ms(1100)) { BeginTime = delay };
                var r = ring;
                fade.Completed += (o, a) => coverFx.Children.Remove(r);
                ring.Opacity = 0;
                ring.BeginAnimation(OpacityProperty, fade);
            }
            // the cover gives a little squish
            var cs = (ScaleTransform)((TransformGroup)NpCover.RenderTransform).Children[0];
            var squish = new DoubleAnimation(0.96, 1, Settings.Ms(420)) { EasingFunction = new ElasticEase { Oscillations = 1, Springiness = 4 } };
            cs.BeginAnimation(ScaleTransform.ScaleXProperty, squish); cs.BeginAnimation(ScaleTransform.ScaleYProperty, squish);
        }

        // ================================================================ Animations page
        void BuildAnimPage()
        {
            var cur = Settings.Current;
            AnimPanel.Children.Clear();
            AnimPanel.Children.Add(new TextBlock { Text = "Animations", FontSize = 30, FontWeight = FontWeights.Bold, FontFamily = (FontFamily)FindResource("DisplayFont") });
            AnimPanel.Children.Add(new TextBlock { Text = "Choose how GEN-Z moves. Changes apply instantly.", Margin = new Thickness(0, 2, 0, 6), FontSize = 13, Foreground = (Brush)FindResource("Text2Brush") });

            Section(AnimPanel, "SCREEN TRANSITION", "How one screen changes to the next – the same set as GEN-Z on Android.");
            AnimPanel.Children.Add(Choices(Controls.Transitions.Styles.Concat(new[] { ("none", "None") }).ToArray(),
                cur.Transition, v => { cur.Transition = v; PreviewTransition(); }));

            Section(AnimPanel, "LISTS & GRIDS ARRIVING", "Songs, albums and theme cards when a page opens.");
            AnimPanel.Children.Add(Choices(new[] { ("bounce", "Bounce in"), ("cascade", "Cascade"), ("fade", "Fade in"), ("none", "None") },
                cur.ListAnimation, v => cur.ListAnimation = v));

            Section(AnimPanel, "LIST SCROLL ANIMATION", "Rows animate in as you scroll – the XuiMod set from GEN-Z on Android.");
            var names = Controls.ListAnim.Names.Select((n, i) => (i.ToString(), n)).ToArray();
            AnimPanel.Children.Add(Choices(names, cur.ScrollAnim.ToString(), v => { cur.ScrollAnim = int.Parse(v); DemoScroll(); }));
            Section(AnimPanel, "SCROLL ANIMATION CURVE", null);
            var curves = Controls.ListAnim.Interpolators.Select((n, i) => (i.ToString(), n)).ToArray();
            AnimPanel.Children.Add(Choices(curves, cur.ScrollInterp.ToString(), v => { cur.ScrollInterp = int.Parse(v); DemoScroll(); }));
            Section(AnimPanel, "SCROLL ANIMATION LENGTH", null);
            var drow = new Grid { MaxWidth = 520, Width = 520, HorizontalAlignment = HorizontalAlignment.Left };
            drow.ColumnDefinitions.Add(new ColumnDefinition()); drow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(70) });
            var dsl = new Slider { Style = (Style)FindResource("ThinSlider"), Minimum = 150, Maximum = 900, Value = cur.ScrollDuration, Margin = new Thickness(0, 0, 12, 0) };
            var dlab = new TextBlock { Text = cur.ScrollDuration + " ms", FontSize = 12, Foreground = (Brush)FindResource("Text2Brush"), VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(dlab, 1); drow.Children.Add(dsl); drow.Children.Add(dlab);
            dsl.ValueChanged += (s2, e2) => { cur.ScrollDuration = (int)(Math.Round(e2.NewValue / 10) * 10); dlab.Text = cur.ScrollDuration + " ms"; };
            dsl.PreviewMouseLeftButtonUp += (s2, e2) => { Settings.Save(); DemoScroll(); };
            AnimPanel.Children.Add(drow);
            demoHost = new StackPanel { Margin = new Thickness(0, 14, 0, 0), MaxWidth = 520, HorizontalAlignment = HorizontalAlignment.Left, Width = 520, ClipToBounds = true };
            AnimPanel.Children.Add(demoHost);
            DemoScroll();

            Section(AnimPanel, "COVER TOUCH EFFECT", "Click the album cover on Now Playing.");
            AnimPanel.Children.Add(Choices(new[] { ("ripple", "Water ripple"), ("burst", "Sparkle burst"), ("none", "None") },
                cur.CoverEffect, v => cur.CoverEffect = v));

            Section(AnimPanel, "LIKE CELEBRATION", "When you love a song: a heart bursts with confetti and firecrackers, plus…");
            AnimPanel.Children.Add(Choices(new[] { ("explosion", "Particle explosion"), ("frame", "3D heart frame"), ("glitter", "Magic glitter heart"), ("all", "All together"), ("simple", "Simple burst") },
                cur.LikeEffect, v => { cur.LikeEffect = v; Celebrate(true); }));

            Section(AnimPanel, "SIDEBAR CLICK EFFECT", "What happens when you click in the left panel.");
            AnimPanel.Children.Add(Choices(Controls.SpaceFx.Effects, cur.NavEffect, v => { cur.NavEffect = v; }));
            AnimPanel.Children.Add(Toggle("Moving stars behind the sidebar", "A slow, twinkling star field drifts behind the menu", cur.NavStarField, v => { cur.NavStarField = v; Controls.SpaceFx.StarField(NavStars, v); }));

            Section(AnimPanel, "EFFECTS", null);
            AnimPanel.Children.Add(Toggle("Live volume sliders", "The volume bar glows and the speaker pulses with the music", cur.LiveVolume, v => cur.LiveVolume = v));
            AnimPanel.Children.Add(Toggle("Sparkling progress bar", "Stars drift off the progress bar while music plays", cur.Sparkles, v => { cur.Sparkles = v; OnPlayerState(); }));
            AnimPanel.Children.Add(Toggle("Play / pause morph", "The icon spins, the button beats and a ring bursts", cur.PlayMorph, v => cur.PlayMorph = v));
            AnimPanel.Children.Add(Toggle("Cover breathes", "The cover shrinks a little while paused", cur.CoverBreathe, v => { cur.CoverBreathe = v; lastPlaying = null; OnPlayerState(); }));
            AnimPanel.Children.Add(Toggle("Reduce motion", "Turn off movement everywhere (fades only)", cur.ReduceMotion, v => { cur.ReduceMotion = v; OnPlayerState(); }));

            Section(AnimPanel, "SPEED", null);
            var row = new Grid { Margin = new Thickness(0, 4, 0, 0), MaxWidth = 520, HorizontalAlignment = HorizontalAlignment.Left, Width = 520 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition());
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.Children.Add(new TextBlock { Text = "Slower", FontSize = 12, Foreground = (Brush)FindResource("Text2Brush"), VerticalAlignment = VerticalAlignment.Center });
            var sl = new Slider { Style = (Style)FindResource("ThinSlider"), Minimum = 0.5, Maximum = 2, Value = cur.AnimSpeed, Margin = new Thickness(12, 0, 12, 0) };
            Grid.SetColumn(sl, 1); row.Children.Add(sl);
            var lbl = new TextBlock { Text = "Faster", FontSize = 12, Foreground = (Brush)FindResource("Text2Brush"), VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(lbl, 2); row.Children.Add(lbl);
            sl.ValueChanged += (s, e) => { cur.AnimSpeed = Math.Round(e.NewValue, 2); };
            AnimPanel.Children.Add(row);
            var preview = new Button { Style = (Style)FindResource("PillButton"), Content = "Preview", Margin = new Thickness(0, 22, 0, 0), HorizontalAlignment = HorizontalAlignment.Left };
            preview.Click += (s, e) => { Settings.Save(); PreviewTransition(); DemoScroll(); OnCoverClickPreview(); };
            AnimPanel.Children.Add(preview);
        }

        StackPanel demoHost;

        /// <summary>Four sample rows play the chosen scroll animation.</summary>
        void DemoScroll()
        {
            if (demoHost == null) return;
            demoHost.Children.Clear();
            var cur = Settings.Current;
            for (int i = 0; i < 4; i++)
            {
                var row = new Border { CornerRadius = new CornerRadius(10), Padding = new Thickness(14, 10, 14, 10), Margin = new Thickness(0, 0, 0, 6), Background = (Brush)FindResource("SurfaceBrush") };
                var sp = new StackPanel { Orientation = Orientation.Horizontal };
                sp.Children.Add(new Path { Data = (Geometry)FindResource("IcNote"), Fill = (Brush)FindResource("AccentBrush"), Width = 16, Height = 16, Stretch = Stretch.Uniform, Margin = new Thickness(0, 0, 12, 0) });
                sp.Children.Add(new TextBlock { Text = "Sample song " + (i + 1) + " · " + Controls.ListAnim.Names[Math.Max(0, Math.Min(cur.ScrollAnim, Controls.ListAnim.Names.Length - 1))], FontSize = 13 });
                row.Child = sp;
                demoHost.Children.Add(row);
                var r = row; int k = i;
                Dispatcher.BeginInvoke(new Action(() =>
                {
                    var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1 + k * 90) };
                    t.Tick += (o, a) => { t.Stop(); Controls.ListAnim.Animate(r, true, cur.ScrollAnim, cur.ScrollDuration, cur.ScrollInterp); };
                    t.Start();
                }), DispatcherPriority.Loaded);
            }
        }

        void PreviewTransition()
        {
            var host = (Panel)Pages.Parent;
            var old = Controls.Transitions.Capture(Pages, host);
            Pages.ClipToBounds = true;
            Controls.Transitions.Run(Settings.Current.Transition, PageAnim, old, host, Pages);
        }

        void OnCoverClickPreview()
        {
            if (NpColumn.Width.Value <= 0) return;
            // ripple from the centre of the cover
            if (coverFx == null) { coverFx = new Canvas { IsHitTestVisible = false, ClipToBounds = true }; NpCoverHost.Children.Add(coverFx); }
            var fake = new Point(NpCoverHost.ActualWidth / 2, NpCoverHost.ActualHeight / 2);
            if (Settings.Current.CoverEffect == "burst") Burst(NpCoverHost, 22, fake);
            else if (Settings.Current.CoverEffect == "ripple")
            {
                // reuse the ripple drawing by simulating a click at the centre
                RippleAt(fake);
            }
        }

        void RippleAt(Point p)
        {
            var accent = (Color)FindResource("AccentColor");
            coverFx.Clip = new RectangleGeometry(new Rect(0, 0, NpCoverHost.ActualWidth, NpCoverHost.ActualHeight), 22, 22);
            for (int k = 0; k < 4; k++)
            {
                var ring = new Ellipse { Width = 20, Height = 20, Stroke = new SolidColorBrush(k % 2 == 0 ? Colors.White : accent), StrokeThickness = 3 - k * 0.5, Opacity = 0 };
                Canvas.SetLeft(ring, p.X - 10); Canvas.SetTop(ring, p.Y - 10);
                var st = new ScaleTransform(1, 1); ring.RenderTransformOrigin = new Point(0.5, 0.5); ring.RenderTransform = st;
                coverFx.Children.Add(ring);
                var delay = Settings.Ms(k * 140);
                var grow = new DoubleAnimation(1, 14 + k * 2, Settings.Ms(1100)) { BeginTime = delay, EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } };
                st.BeginAnimation(ScaleTransform.ScaleXProperty, grow); st.BeginAnimation(ScaleTransform.ScaleYProperty, grow);
                var fade = new DoubleAnimation(0.9, 0, Settings.Ms(1100)) { BeginTime = delay };
                var r = ring; fade.Completed += (o, a) => coverFx.Children.Remove(r);
                ring.BeginAnimation(OpacityProperty, fade);
            }
        }

        void Section(Panel host, string title, string sub)
        {
            host.Children.Add(new TextBlock { Text = title, Margin = new Thickness(0, 24, 0, 2), FontSize = 11, FontWeight = FontWeights.Bold, Foreground = (Brush)FindResource("AccentBrush") });
            if (sub != null) host.Children.Add(new TextBlock { Text = sub, Margin = new Thickness(0, 0, 0, 8), FontSize = 12, Foreground = (Brush)FindResource("Text2Brush") });
        }

        FrameworkElement Choices((string value, string label)[] options, string current, Action<string> pick)
        {
            var wrap = new WrapPanel { Margin = new Thickness(0, 2, 0, 0) };
            foreach (var o in options)
            {
                var b = new Button { Content = o.label, Style = (Style)FindResource(o.value == current ? "PillButton" : "GhostPill"), Margin = new Thickness(0, 0, 8, 8), Padding = new Thickness(16, 7, 16, 7) };
                var val = o.value;
                b.Click += (s, e) =>
                {
                    pick(val); Settings.Save();
                    foreach (Button other in wrap.Children) other.Style = (Style)FindResource(other == b ? "PillButton" : "GhostPill");
                };
                wrap.Children.Add(b);
            }
            return wrap;
        }

        FrameworkElement Toggle(string title, string sub, bool value, Action<bool> set)
        {
            var text = new StackPanel();
            text.Children.Add(new TextBlock { Text = title, FontSize = 14, FontWeight = FontWeights.SemiBold });
            if (sub != null) text.Children.Add(new TextBlock { Text = sub, FontSize = 12, Foreground = (Brush)FindResource("Text2Brush"), Margin = new Thickness(0, 1, 0, 0) });
            var cb = new CheckBox { Style = (Style)FindResource("Switch"), Content = text, IsChecked = value, Margin = new Thickness(0, 6, 0, 6), MaxWidth = 560, HorizontalAlignment = HorizontalAlignment.Left, Width = 560 };
            cb.Checked += (s, e) => { set(true); Settings.Save(); };
            cb.Unchecked += (s, e) => { set(false); Settings.Save(); };
            return cb;
        }

        // ================================================================ settings: interface size & mini player
        void BuildPrefs()
        {
            PrefsPanel.Children.Clear();
            Section(PrefsPanel, "INTERFACE SIZE", "Automatic fits GEN-Z to your window: small windows shrink it, big and 4K screens enlarge it – always drawn sharp at your display scaling.");
            var cur = Settings.Current;
            var opts = new[] { ("0", "Automatic"), ("0.8", "80%"), ("0.9", "90%"), ("1", "100%"), ("1.1", "110%"), ("1.25", "125%"), ("1.5", "150%") };
            string now = cur.UiScale <= 0 ? "0" : cur.UiScale.ToString(System.Globalization.CultureInfo.InvariantCulture);
            PrefsPanel.Children.Add(Choices(opts, now, v =>
            {
                cur.UiScale = double.Parse(v, System.Globalization.CultureInfo.InvariantCulture);
                scale = -1; ApplyScale();
            }));
            PerformancePrefs(cur);
            Section(PrefsPanel, "PLAYBACK", null);
            PrefsPanel.Children.Add(Toggle("Soft fade on play and pause", "The music fades in and out instead of stopping abruptly", cur.FadePlayPause, v => cur.FadePlayPause = v));
            PrefsPanel.Children.Add(Toggle("Continue where you left off", "Remember Up next, the song and its position when GEN-Z closes", cur.RememberQueue, v => cur.RememberQueue = v));
            OnlinePrefs(PrefsPanel);
            Section(PrefsPanel, "MINI PLAYER (PICTURE IN PICTURE)", "A small always-on-top player. Open it with the button in the title bar or Ctrl+P.");
            PrefsPanel.Children.Add(Toggle("Open the mini player when minimized", "While music plays, minimizing GEN-Z shows the mini player", cur.AutoPip, v => cur.AutoPip = v));
            var b = new Button { Style = (Style)FindResource("PillButton"), Content = "Open mini player now", HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 6, 0, 0) };
            b.Click += (s, e) => OnPip(this, null);
            PrefsPanel.Children.Add(b);
        }

        /// <summary>Old-hardware support: Automatic / Best looks / Light, and software drawing (safe mode).</summary>
        void PerformancePrefs(Settings cur)
        {
            string state = Core.Perf.Light ? "Light mode is on" + (Core.Perf.Reason.Length > 0 ? " (" + Core.Perf.Reason + ")" : "") : "Full effects are on";
            Section(PrefsPanel, "PERFORMANCE & OLDER PCs", Core.Perf.Summary() + " " + state + ". Light mode keeps GEN-Z smooth on older PCs: "
                + "no star field, sidebar or scroll effects, simple fades, a simpler like animation and lighter cover scaling.");
            var opts = new[] { ("auto", "Automatic"), ("best", "Best looks"), ("light", "Light (older PCs)") };
            PrefsPanel.Children.Add(Choices(opts, cur.Performance ?? "auto", v =>
            {
                cur.Performance = v; Core.Perf.Decide();
                Controls.SpaceFx.StarField(NavStars, cur.NavStarField); OnPlayerState();
                ShowToast(Core.Perf.Light ? "Light mode on – smoother on older PCs" : "Full effects on");
            }));
            PrefsPanel.Children.Add(Toggle("Software drawing (safe mode)", "For old or faulty graphics drivers – draws without the graphics card. Takes effect after a restart.",
                cur.SoftwareRendering, v => { cur.SoftwareRendering = v; ShowToast("Restart GEN-Z Music to apply"); }));
        }

        // ================================================================ files from Explorer ("Open with GEN-Z Music")
        public void OpenFiles(string[] paths)
        {
            var songs = paths.Where(p => System.IO.File.Exists(p) && Array.IndexOf(Library.Extensions, System.IO.Path.GetExtension(p).ToLowerInvariant()) >= 0)
                             .Select(p => Library.Songs.FirstOrDefault(x => string.Equals(x.Path, p, StringComparison.OrdinalIgnoreCase)) ?? Library.Read(p))
                             .ToList();
            if (songs.Count > 0) Player.PlayList(songs, 0);
        }

        /// <summary>Another launch asked us to come to the front (and maybe play its files).</summary>
        public void BringBack(string[] files)
        {
            if (pip == null && WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
            if (pip == null) { Activate(); Topmost = true; Topmost = false; }
            if (files != null && files.Length > 0) OpenFiles(files);
        }

        // ================================================================ picture in picture
        MiniPlayer pip;
        void OnPip(object sender, RoutedEventArgs e) { if (pip == null) EnterPip(true); else ExitPip(); }

        void EnterPip(bool minimize)
        {
            if (pip != null) return;
            pip = new MiniPlayer(this);
            pip.Show();
            if (minimize) WindowState = WindowState.Minimized;
        }

        public void ExitPip()
        {
            var p = pip; pip = null;
            p?.Close();
            Settings.Save();
            if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
            Activate();
        }
    }
}
