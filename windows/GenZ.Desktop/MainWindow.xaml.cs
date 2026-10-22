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
        enum View { Songs, Albums, Artists, Favourites, Queue, Themes, Anim, Eq, About, Search, Detail }

        View view = View.Songs;
        List<Song> listed = new List<Song>();
        readonly DispatcherTimer timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(40) };
        bool seeking;
        bool? lastPlaying;
        Song shownSong;

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
            ThemeManager.Changed += () => { if (view == View.Themes) ShowThemes(); UpdateToggles(); };
            timer.Tick += (s, e) => Tick();
            timer.Start();

            StateChanged += (s, e) =>
            {
                Root.Margin = new Thickness(0);
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
            SourceInitialized += (s, e) => RegisterMediaKeys();
            Closing += (s, e) => { Settings.Current.NowPlayingOpen = NpColumn.Width.Value > 0; Settings.Save(); };

            BuildEq();
            SetThumbIcons();
            Library.LoadCache();
            NavSongs.IsChecked = true;
            ShowView(View.Songs);
            UpdateScanText();
            Library.ScanAsync();
            UpdateScanText();

            // restore the last song (paused) so pressing play continues where you left off
            var last = Library.Songs.FirstOrDefault(x => x.Path == Settings.Current.LastSong);
            if (last != null) ShowSong(last);
            UpdateToggles();
        }

        // ================================================================ library & navigation
        void OnLibraryChanged()
        {
            UpdateScanText();
            if (view == View.Songs || view == View.Albums || view == View.Artists || view == View.Favourites) ShowView(view);
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
            else if (sender == NavArtists) ShowView(View.Artists);
            else if (sender == NavFav) ShowView(View.Favourites);
            else if (sender == NavQueue) ShowView(View.Queue);
            else if (sender == NavThemes) ShowView(View.Themes);
            else if (sender == NavAnim) ShowView(View.Anim);
            else if (sender == NavEq) ShowView(View.Eq);
            else if (sender == NavAbout) ShowView(View.About);
        }

        void ShowPage(FrameworkElement page)
        {
            foreach (FrameworkElement p in Pages.Children) p.Visibility = p == page ? Visibility.Visible : Visibility.Collapsed;
            AnimateIn(page, Settings.Current.PageTransition);
        }

        void ShowView(View v)
        {
            if (Pages == null) return;
            view = v;
            switch (v)
            {
                case View.Songs: ShowList("Songs", $"{Library.Songs.Count} songs", Library.Songs, null); break;
                case View.Favourites:
                    var fav = Library.Songs.Where(s => Settings.Current.Favourites.Contains(s.Path)).ToList();
                    ShowList("Favourites", fav.Count == 0 ? "Tap the heart on Now Playing to add songs here" : $"{fav.Count} songs", fav, null); break;
                case View.Queue: ShowList("Up next", Player.Queue.Count == 0 ? "Nothing queued yet" : $"{Player.Queue.Count} songs in the queue", Player.Queue, null); break;
                case View.Albums: ShowAlbums(); break;
                case View.Artists: ShowArtists(); break;
                case View.Themes: ShowThemes(); break;
                case View.Anim: BuildAnimPage(); ShowPage(PageAnim); break;
                case View.Eq: ShowPage(PageEq); break;
                case View.About: ShowAbout(); break;
            }
        }

        void ShowList(string title, string sub, List<Song> songs, Song coverOf)
        {
            listed = songs;
            ListTitle.Text = title; ListSub.Text = sub;
            SongList.ItemsSource = null;
            SongList.ItemsSource = songs;
            if (coverOf != null)
            {
                ListCover.Visibility = Visibility.Visible;
                ListCover.Background = (Brush)FindResource("SurfaceBrush");
                SetCoverAsync(ListCover, coverOf, 200);
            }
            else ListCover.Visibility = Visibility.Collapsed;
            ShowPage(PageList);
            CascadeList();
        }

        void OnSearch(object sender, TextChangedEventArgs e)
        {
            SearchHint.Visibility = string.IsNullOrEmpty(SearchBox.Text) ? Visibility.Visible : Visibility.Collapsed;
            if (string.IsNullOrWhiteSpace(SearchBox.Text)) { if (view == View.Search) ShowView(View.Songs); return; }
            view = View.Search;
            var r = Library.Search(SearchBox.Text).ToList();
            ShowList("Search", $"{r.Count} results for “{SearchBox.Text.Trim()}”", r, null);
        }

        void OnSongDoubleClick(object sender, MouseButtonEventArgs e) { }

        /// <summary>One click on a song plays it (like a tap on the phone).</summary>
        void OnSongClick(object sender, MouseButtonEventArgs e)
        {
            var el = e.OriginalSource as DependencyObject;
            while (el != null && !(el is ListBoxItem)) el = el is Visual ? VisualTreeHelper.GetParent(el) : LogicalTreeHelper.GetParent(el);
            if (el is ListBoxItem item && item.DataContext is Song s)
            {
                SongList.SelectedItem = s;
                PlayFromList(s);
                var st = new ScaleTransform(1, 1); item.RenderTransformOrigin = new Point(0.5, 0.5); item.RenderTransform = st;
                var a = new DoubleAnimation(0.97, 1, Settings.Ms(320)) { EasingFunction = new ElasticEase { Oscillations = 1, Springiness = 5 } };
                st.BeginAnimation(ScaleTransform.ScaleXProperty, a); st.BeginAnimation(ScaleTransform.ScaleYProperty, a);
            }
        }

        void OnSongKey(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter && SongList.SelectedItem is Song s) { PlayFromList(s); e.Handled = true; }
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
            GridPanel.Children.Clear();
            ShowPage(PageGrid);
        }

        void ShowAlbums()
        {
            ShowGrid("Albums", $"{Library.Albums.Count} albums");
            foreach (var a in Library.Albums)
            {
                var tile = Tile(a.Title, a.Artist, a.First, false);
                var album = a;
                tile.MouseLeftButtonUp += (s, e) => { view = View.Detail; ShowList(album.Title, album.Artist + " · " + album.Subtitle, album.Songs, album.First); };
                GridPanel.Children.Add(tile);
            }
            Cascade(GridPanel);
        }

        void ShowArtists()
        {
            ShowGrid("Artists", $"{Library.Artists.Count} artists");
            foreach (var a in Library.Artists)
            {
                var tile = Tile(a.Name, a.Subtitle, a.First, true);
                var artist = a;
                tile.MouseLeftButtonUp += (s, e) => { view = View.Detail; ShowList(artist.Name, artist.Subtitle, artist.Songs, artist.First); };
                GridPanel.Children.Add(tile);
            }
            Cascade(GridPanel);
        }

        FrameworkElement Tile(string title, string sub, Song coverOf, bool round)
        {
            var art = new Border { Width = 168, Height = 168, CornerRadius = new CornerRadius(round ? 84 : 16), Background = (Brush)FindResource("SurfaceBrush") };
            art.Child = new Path { Data = (Geometry)FindResource(round ? "IcPerson" : "IcAlbum"), Fill = (Brush)FindResource("AccentBrush"), Width = 46, Height = 46, Stretch = Stretch.Uniform, Opacity = 0.7 };
            SetCoverAsync(art, coverOf, 336);
            var t1 = new TextBlock { Text = title, FontSize = 14, FontWeight = FontWeights.SemiBold, Margin = new Thickness(2, 10, 2, 0), HorizontalAlignment = round ? HorizontalAlignment.Center : HorizontalAlignment.Left };
            var t2 = new TextBlock { Text = sub, FontSize = 12, Margin = new Thickness(2, 2, 2, 0), Foreground = (Brush)FindResource("Text2Brush"), HorizontalAlignment = round ? HorizontalAlignment.Center : HorizontalAlignment.Left };
            var sp = new StackPanel { Width = 168, Margin = new Thickness(12, 8, 12, 14), Cursor = Cursors.Hand, Background = Brushes.Transparent };
            sp.Children.Add(art); sp.Children.Add(t1); sp.Children.Add(t2);
            var st = new ScaleTransform(1, 1); art.RenderTransformOrigin = new Point(0.5, 0.5); art.RenderTransform = st;
            sp.MouseEnter += (s, e) => { Spring(st, 1.04); };
            sp.MouseLeave += (s, e) => { Spring(st, 1.0); };
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
            target.Background = new ImageBrush(bmp) { Stretch = Stretch.UniformToFill };
            target.Opacity = 0;
            target.BeginAnimation(OpacityProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(220)));
        }

        // ---------------------------------------------------------------- themes
        void ShowThemes()
        {
            ShowGrid("Themes", $"{ThemeManager.All.Count} themes · the same looks as GEN-Z on Android");
            foreach (var t in ThemeManager.All)
            {
                var theme = t;
                var card = new Border
                {
                    Width = 220, Height = 156, CornerRadius = new CornerRadius(16), Margin = new Thickness(10), Cursor = Cursors.Hand,
                    Background = new SolidColorBrush(t.Bg), ClipToBounds = true,
                    BorderBrush = theme == ThemeManager.Current ? (Brush)FindResource("AccentBrush") : new SolidColorBrush(Color.FromArgb(40, 128, 128, 128)),
                    BorderThickness = new Thickness(theme == ThemeManager.Current ? 3 : 1)
                };
                var g = new Grid();
                g.RowDefinitions.Add(new RowDefinition { Height = new GridLength(62) });
                g.RowDefinitions.Add(new RowDefinition());
                var head = new Border { Background = new LinearGradientBrush(t.Header, t.HeaderEnd, 0), CornerRadius = new CornerRadius(14, 14, 0, 0) };
                head.Child = new TextBlock
                {
                    Text = t.Name, FontFamily = ThemeManager.Font(t.DisplayFont), FontSize = 19, FontWeight = FontWeights.Bold,
                    Foreground = new SolidColorBrush(t.C("headerText", Colors.White)), Margin = new Thickness(14, 0, 10, 0), VerticalAlignment = VerticalAlignment.Center
                };
                g.Children.Add(head);
                var body = new StackPanel { Margin = new Thickness(14, 10, 14, 10) };
                Grid.SetRow(body, 1);
                var sw = new StackPanel { Orientation = Orientation.Horizontal };
                foreach (var key in new[] { "accent", "header", "headerEnd", "surface", "text" })
                    sw.Children.Add(new Ellipse { Width = 20, Height = 20, Margin = new Thickness(0, 0, 6, 0), Fill = new SolidColorBrush(t.C(key, Colors.Gray)), Stroke = new SolidColorBrush(Color.FromArgb(60, 128, 128, 128)) });
                body.Children.Add(sw);
                var bar = new Border { Height = 5, CornerRadius = new CornerRadius(2.5), Margin = new Thickness(0, 14, 40, 0), HorizontalAlignment = HorizontalAlignment.Stretch };
                var pcs = t.ProgressColors.Count > 0 ? t.ProgressColors : new List<Color> { t.Header, t.Accent, t.HeaderEnd };
                var pg = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 0) };
                for (int i = 0; i < pcs.Count; i++) pg.GradientStops.Add(new GradientStop(pcs[i], pcs.Count == 1 ? 0 : (double)i / (pcs.Count - 1)));
                bar.Background = pg;
                body.Children.Add(bar);
                body.Children.Add(new TextBlock
                {
                    Text = (t.Dark ? "Dark" : "Light") + " · " + Cap(t.ProgressStyle) + " bar",
                    FontFamily = ThemeManager.Font(t.Font), FontSize = 11, Margin = new Thickness(0, 10, 0, 0),
                    Foreground = new SolidColorBrush(t.C("textSecondary", Colors.Gray))
                });
                g.Children.Add(body);
                card.Child = g;
                var st = new ScaleTransform(1, 1); card.RenderTransformOrigin = new Point(0.5, 0.5); card.RenderTransform = st;
                card.MouseEnter += (s, e) => Spring(st, 1.04);
                card.MouseLeave += (s, e) => Spring(st, 1.0);
                card.MouseLeftButtonUp += (s, e) => { ThemeManager.Apply(theme); Settings.Save(); RefreshCoverGlow(); };
                GridPanel.Children.Add(card);
            }
            Cascade(GridPanel);
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
            var st = new ScaleTransform(1, 1); NpFav.RenderTransformOrigin = new Point(0.5, 0.5); NpFav.RenderTransform = st;
            var a = new DoubleAnimation(1.5, 1, TimeSpan.FromMilliseconds(520)) { EasingFunction = new ElasticEase { Oscillations = 2, Springiness = 4 } };
            st.BeginAnimation(ScaleTransform.ScaleXProperty, a); st.BeginAnimation(ScaleTransform.ScaleYProperty, a);
            if (on) Burst(NpFav, 18);
            if (view == View.Favourites) ShowView(View.Favourites);
        }

        void UpdateFav(Song s)
        {
            bool on = s != null && Settings.Current.Favourites.Contains(s.Path);
            NpFav.Tag = FindResource(on ? "IcHeart" : "IcHeartOutline");
            NpFav.Foreground = on ? (Brush)FindResource("AccentBrush") : (Brush)FindResource("NpTextBrush");
        }

        void OnTrackChanged()
        {
            var s = Player.Current;
            if (Player.Error != null) ScanText.Text = Player.Error;
            ShowSong(s);
            if (view == View.Queue) ShowView(View.Queue);
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
                NpCover.Background = new ImageBrush(big) { Stretch = Stretch.UniformToFill };
                BarCover.Background = new ImageBrush(big) { Stretch = Stretch.UniformToFill };
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
            NpBar.Playing = playing && Settings.Current.Sparkles && !Settings.Current.ReduceMotion;
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
            Root.Children.Add(fxLayer);
            return fxLayer;
        }

        void Tick()
        {
            var cur = Player.Current;
            if (cur == null) return;
            var dur = Player.Duration; var pos = Player.Position;
            double f = dur.TotalMilliseconds > 0 ? pos.TotalMilliseconds / dur.TotalMilliseconds : 0;
            if (!seeking)
            {
                NpBar.Value = f; BarBar.Value = f;
                ShowTimes(pos);
            }
            if (Player.IsPlaying) Taskbar.ProgressValue = f;
        }

        void ShowTimes(TimeSpan pos)
        {
            var dur = Player.Duration;
            NpElapsed.Text = BarElapsed.Text = Library.Format((long)pos.TotalMilliseconds);
            NpRemain.Text = "-" + Library.Format((long)(dur - pos).TotalMilliseconds);
            BarTotal.Text = Library.Format((long)dur.TotalMilliseconds);
        }

        // ================================================================ window, keys, taskbar
        void OnMin(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
        void OnMax(object sender, RoutedEventArgs e) => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
        void OnClose(object sender, RoutedEventArgs e) => Close();

        void OnTogglePanel(object sender, RoutedEventArgs e)
        {
            bool open = NpColumn.Width.Value > 0;
            NpColumn.Width = new GridLength(open ? 0 : 380);
            Settings.Current.NowPlayingOpen = !open;
        }

        void OnKeys(object sender, KeyEventArgs e)
        {
            bool ctrl = (Keyboard.Modifiers & ModifierKeys.Control) != 0;
            if (e.Key == Key.F && ctrl) { SearchBox.Focus(); SearchBox.SelectAll(); e.Handled = true; }
            else if (e.Key == Key.P && ctrl) { OnPip(this, null); e.Handled = true; }
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
            double s = Settings.Current.UiScale > 0 ? Settings.Current.UiScale
                : Math.Max(0.66, Math.Min(1.0, Math.Min(w / 1240.0, h / 790.0)));
            if (Math.Abs(s - scale) < 0.004 && Root.LayoutTransform is ScaleTransform) return;
            scale = s;
            Root.LayoutTransform = Math.Abs(s - 1) < 0.004 ? Transform.Identity : new ScaleTransform(s, s);
            var chrome = WindowChrome.GetWindowChrome(this);
            if (chrome != null)
            {
                if (chrome.IsFrozen) { chrome = (WindowChrome)chrome.Clone(); chrome.CaptionHeight = 56 * s; WindowChrome.SetWindowChrome(this, chrome); }
                else chrome.CaptionHeight = 56 * s;
            }
        }

        /// <summary>The Now Playing cover grows and shrinks so the controls always fit.</summary>
        void FitCover()
        {
            // the cover gets whatever the title, progress and controls leave free – they are always fully visible
            double used = 26 + 18 + NpInfo.ActualHeight + NpInfo.Margin.Top + NpControls.ActualHeight + NpControls.Margin.Top + 8;
            double w = NpPanel.ActualWidth - 56, h = NpPanel.ActualHeight - used;
            double size = Math.Max(90, Math.Min(420, Math.Min(w, h)));
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

        /// <summary>Tiles drop in one after another.</summary>
        void Cascade(Panel panel)
        {
            if (Settings.Current.ReduceMotion || Settings.Current.ListAnimation == "none") return;
            int i = 0;
            foreach (UIElement el in panel.Children)
            {
                if (i >= 36) break;
                var fe = (FrameworkElement)el;
                fe.Opacity = 0;
                var delay = Settings.Ms(i * 28);
                var fade = new DoubleAnimation(0, 1, Settings.Ms(300)) { BeginTime = delay };
                fe.BeginAnimation(OpacityProperty, fade);
                if (Settings.Current.ListAnimation == "cascade")
                {
                    var tt = new TranslateTransform(0, 26);
                    var group = new TransformGroup();
                    var holder = fe.RenderTransform;
                    group.Children.Add(holder ?? Transform.Identity); group.Children.Add(tt);
                    fe.RenderTransform = group;
                    tt.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(26, 0, Settings.Ms(420)) { BeginTime = delay, EasingFunction = new BackEase { Amplitude = 0.35, EasingMode = EasingMode.EaseOut } });
                }
                i++;
            }
            for (int k = 36; k < panel.Children.Count; k++) ((FrameworkElement)panel.Children[k]).Opacity = 1;
        }

        /// <summary>Song rows slide in one after another (only the rows on screen).</summary>
        void CascadeList()
        {
            if (Settings.Current.ReduceMotion || Settings.Current.ListAnimation == "none") return;
            Dispatcher.BeginInvoke(new Action(() =>
            {
                for (int i = 0; i < 22; i++)
                {
                    if (!(SongList.ItemContainerGenerator.ContainerFromIndex(i) is FrameworkElement row)) break;
                    var delay = Settings.Ms(i * 22);
                    row.Opacity = 0;
                    row.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, Settings.Ms(260)) { BeginTime = delay });
                    if (Settings.Current.ListAnimation == "cascade")
                    {
                        var tt = new TranslateTransform(-24, 0); row.RenderTransform = tt;
                        tt.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(-24, 0, Settings.Ms(380)) { BeginTime = delay, EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
                    }
                }
            }), DispatcherPriority.Loaded);
        }

        // cover touch effects on Now Playing
        Canvas coverFx;
        void OnCoverClick(object sender, MouseButtonEventArgs e)
        {
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

            Section(AnimPanel, "PAGE TRANSITION", "How a screen appears when you switch pages.");
            AnimPanel.Children.Add(Choices(new[] { ("slide-up", "Slide up"), ("slide-left", "Slide in"), ("fade", "Fade"), ("zoom", "Zoom"), ("none", "None") },
                cur.PageTransition, v => { cur.PageTransition = v; AnimateIn(PageAnim, v); }));

            Section(AnimPanel, "LISTS & GRIDS", "Songs, albums and theme cards arriving on screen.");
            AnimPanel.Children.Add(Choices(new[] { ("cascade", "Cascade"), ("fade", "Fade in"), ("none", "None") },
                cur.ListAnimation, v => cur.ListAnimation = v));

            Section(AnimPanel, "COVER TOUCH EFFECT", "Click the album cover on Now Playing.");
            AnimPanel.Children.Add(Choices(new[] { ("ripple", "Water ripple"), ("burst", "Sparkle burst"), ("none", "None") },
                cur.CoverEffect, v => cur.CoverEffect = v));

            Section(AnimPanel, "EFFECTS", null);
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
            preview.Click += (s, e) => { Settings.Save(); AnimateIn(PageAnim, cur.PageTransition); OnCoverClickPreview(); };
            AnimPanel.Children.Add(preview);
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
            Section(PrefsPanel, "INTERFACE SIZE", "Automatic fits GEN-Z to your window and display scaling.");
            var cur = Settings.Current;
            var opts = new[] { ("0", "Automatic"), ("0.8", "80%"), ("0.9", "90%"), ("1", "100%"), ("1.1", "110%"), ("1.25", "125%"), ("1.5", "150%") };
            string now = cur.UiScale <= 0 ? "0" : cur.UiScale.ToString(System.Globalization.CultureInfo.InvariantCulture);
            PrefsPanel.Children.Add(Choices(opts, now, v =>
            {
                cur.UiScale = double.Parse(v, System.Globalization.CultureInfo.InvariantCulture);
                scale = -1; ApplyScale();
            }));
            Section(PrefsPanel, "MINI PLAYER (PICTURE IN PICTURE)", "A small always-on-top player. Open it with the button in the title bar or Ctrl+P.");
            PrefsPanel.Children.Add(Toggle("Open the mini player when minimized", "While music plays, minimizing GEN-Z shows the mini player", cur.AutoPip, v => cur.AutoPip = v));
            var b = new Button { Style = (Style)FindResource("PillButton"), Content = "Open mini player now", HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 6, 0, 0) };
            b.Click += (s, e) => OnPip(this, null);
            PrefsPanel.Children.Add(b);
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
