using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using GenZ.Controls;
using GenZ.Core;

namespace GenZ
{
    /// <summary>View options on every page (grid, small grid, list, details…), sorting, back navigation and the sliding Now Playing panel.</summary>
    public partial class MainWindow
    {
        class GridItem
        {
            public string Title, Sub, Col2, Col3, Tip;
            public int Year, Count;
            public long Ms;
            public Song Cover;
            public bool Round;
            public List<Song> Songs;
            public Action Open;
        }

        bool noTransition;
        View detailFrom = View.Albums;
        Action reshowDetail;

        static readonly (string id, string icon, string tip)[] SongModes =
            { ("list", "IcViewList", "List"), ("compact", "IcViewCompact", "Compact list"), ("covers", "IcViewCovers", "List with covers"), ("details", "IcViewDetails", "Details") };
        static readonly (string id, string icon, string tip)[] GridModes =
            { ("grid", "IcViewGrid", "Large grid"), ("small", "IcViewSmall", "Small grid"), ("list", "IcViewList", "List"), ("details", "IcViewDetails", "Details") };
        static readonly (string id, string icon, string tip)[] ThemeModes =
            { ("cards", "IcViewGrid", "Cards"), ("small", "IcViewSmall", "Small cards"), ("list", "IcViewList", "List") };
        static readonly (string id, string label)[] SongSorts =
            { ("title", "Title"), ("artist", "Artist"), ("album", "Album"), ("year", "Year"), ("length", "Length"), ("added", "Date added"), ("plays", "Most played") };

        // ================================================================ detail pages and back
        void OpenDetail(string title, string sub, List<Song> songs, Song cover)
        {
            if (view != View.Detail) detailFrom = view == View.Search ? View.Songs : view;
            reshowDetail = () => ShowList(title, sub, SortSongs(songs, "detail", "default"), cover);
            view = View.Detail;
            ShowView(View.Detail);
        }

        void OnBack(object sender, RoutedEventArgs e)
        {
            if (view != View.Detail) return;
            ShowView(detailFrom);
        }

        // ================================================================ sorting
        string SongPage(View v)
        {
            switch (v)
            {
                case View.Songs: return "songs";
                case View.Favourites: return "favs";
                case View.Search: return "search";
                case View.Detail: return "detail";
                default: return null; // queue, playlists, recent, most played keep their own order
            }
        }

        List<Song> SortSongs(List<Song> list, string page, string def)
        {
            var key = Settings.Current.Sort(page, def);
            bool desc = key.EndsWith(":desc"); if (desc) key = key.Substring(0, key.Length - 5);
            var c = StringComparer.CurrentCultureIgnoreCase;
            IOrderedEnumerable<Song> q;
            switch (key)
            {
                case "title": q = list.OrderBy(s => s.Title, c); break;
                case "artist": q = list.OrderBy(s => s.Artist, c).ThenBy(s => s.Album, c).ThenBy(s => s.Track); break;
                case "album": q = list.OrderBy(s => s.Album, c).ThenBy(s => s.Track).ThenBy(s => s.Title, c); break;
                case "year": q = list.OrderBy(s => s.Year).ThenBy(s => s.Album, c).ThenBy(s => s.Track); break;
                case "length": q = list.OrderBy(s => s.DurationMs); break;
                case "added": q = list.OrderByDescending(s => s.Modified); break;
                case "plays": q = list.OrderByDescending(s => s.Plays).ThenBy(s => s.Title, c); break;
                default: return desc ? Enumerable.Reverse(list).ToList() : list.ToList();
            }
            var r = q.ToList();
            if (desc) r.Reverse();
            return r;
        }

        List<GridItem> SortItems(List<GridItem> items, string page)
        {
            var key = Settings.Current.Sort(page, "title");
            bool desc = key.EndsWith(":desc"); if (desc) key = key.Substring(0, key.Length - 5);
            var c = StringComparer.CurrentCultureIgnoreCase;
            List<GridItem> r;
            switch (key)
            {
                case "col2": r = items.OrderBy(i => i.Col2, c).ThenBy(i => i.Title, c).ToList(); break;
                case "year": r = items.OrderBy(i => i.Year).ThenBy(i => i.Title, c).ToList(); break;
                case "count": r = items.OrderByDescending(i => i.Count).ThenBy(i => i.Title, c).ToList(); break;
                case "length": r = items.OrderByDescending(i => i.Ms).ToList(); break;
                case "plays": r = items.OrderByDescending(i => i.Songs.Sum(s => s.Plays)).ThenBy(i => i.Title, c).ToList(); break;
                default: r = items.OrderBy(i => i.Title, c).ToList(); break;
            }
            if (desc) r.Reverse();
            return r;
        }

        // ================================================================ toolbar: sort menu + view switcher
        void BuildTools(Panel host, string modePage, (string id, string icon, string tip)[] modes, string modeDef,
                        string sortPage, (string id, string label)[] sorts, string sortDef)
        {
            host.Children.Clear();
            if (sortPage != null && sorts != null)
            {
                var cur = Settings.Current.Sort(sortPage, sortDef);
                bool desc = cur.EndsWith(":desc");
                var key = desc ? cur.Substring(0, cur.Length - 5) : cur;
                var label = sorts.FirstOrDefault(x => x.id == key).label ?? "Default";
                var sb = new Button { Style = (Style)FindResource("GhostPill"), Margin = new Thickness(0, 0, 10, 0), Padding = new Thickness(14, 6, 14, 6), ToolTip = "Sort" };
                var sp = new StackPanel { Orientation = Orientation.Horizontal };
                var ic = new Path { Data = (Geometry)FindResource("IcSort"), Width = 13, Height = 13, Stretch = Stretch.Uniform, Margin = new Thickness(0, 0, 8, 0) };
                ic.SetResourceReference(Shape.FillProperty, "AccentBrush");
                var tx = new TextBlock { Text = label + (desc ? " ↓" : ""), FontSize = 12.5 };
                tx.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
                sp.Children.Add(ic); sp.Children.Add(tx); sb.Content = sp;
                sb.Click += (s, e) =>
                {
                    var m = new ContextMenu();
                    foreach (var o in sorts)
                    {
                        var id = o.id;
                        var mi = Item(o.label, null, () => SetSort(sortPage, id + (desc ? ":desc" : "")));
                        mi.IsChecked = id == key;
                        m.Items.Add(mi);
                    }
                    m.Items.Add(new Separator());
                    var asc = Item("Normal order", null, () => SetSort(sortPage, key)); asc.IsChecked = !desc; m.Items.Add(asc);
                    var dsc = Item("Reverse order", null, () => SetSort(sortPage, key + ":desc")); dsc.IsChecked = desc; m.Items.Add(dsc);
                    m.PlacementTarget = sb; m.Placement = PlacementMode.Bottom; m.IsOpen = true;
                };
                host.Children.Add(sb);
            }
            if (modes == null) return;
            var mode = Settings.Current.Mode(modePage, modeDef);
            var seg = new Border { CornerRadius = new CornerRadius(12), Padding = new Thickness(3) };
            seg.SetResourceReference(Border.BackgroundProperty, "SurfaceBrush");
            var row = new StackPanel { Orientation = Orientation.Horizontal };
            foreach (var m in modes)
            {
                var id = m.id;
                bool on = id == mode;
                var b = new Button { Style = (Style)FindResource("IconButton"), Tag = FindResource(m.icon), Width = 34, Height = 30, ToolTip = m.tip };
                b.SetResourceReference(ForegroundProperty, on ? "AccentBrush" : "Text2Brush");
                var holder = new Border { CornerRadius = new CornerRadius(9), Child = b, Margin = new Thickness(1, 0, 1, 0) };
                if (on) holder.SetResourceReference(Border.BackgroundProperty, "AccentSoftBrush");
                b.Click += (s, e) =>
                {
                    if (Settings.Current.Mode(modePage, modeDef) == id) return;
                    Settings.Current.ViewModes[modePage] = id; Settings.Save();
                    noTransition = true;
                    try { ShowView(view); } finally { noTransition = false; }
                };
                row.Children.Add(holder);
            }
            seg.Child = row;
            host.Children.Add(seg);
        }

        void SetSort(string page, string value)
        {
            Settings.Current.SortModes[page] = value; Settings.Save();
            noTransition = true;
            try { ShowView(view); } finally { noTransition = false; }
        }

        // ================================================================ song lists
        /// <summary>Applies the chosen song-list look (list, compact, covers, details) and its toolbar.</summary>
        void ApplySongMode()
        {
            var page = SongPage(view);
            BuildTools(ListTools, "songlist", SongModes, "list", page, page == null ? null : (page == "detail" ? new[] { ("default", "Track order") }.Concat(SongSorts).ToArray() : SongSorts),
                page == "detail" ? "default" : "title");
            var mode = Settings.Current.Mode("songlist", "list");
            string key = mode == "compact" ? "SongRowCompact" : mode == "covers" ? "SongRowCovers" : mode == "details" ? "SongRowDetails" : "SongRowList";
            var tpl = (DataTemplate)FindResource(key);
            if (SongList.ItemTemplate != tpl) SongList.ItemTemplate = tpl;
            ListHeader.Visibility = mode == "details" ? Visibility.Visible : Visibility.Collapsed;
            if (mode == "details") BuildSongHeader(page);
            BackBtn.Visibility = view == View.Detail ? Visibility.Visible : Visibility.Collapsed;
        }

        void BuildSongHeader(string sortPage)
        {
            ListHeader.Children.Clear(); ListHeader.ColumnDefinitions.Clear();
            var cols = new (double w, bool star, string label, string sort)[]
            {
                (30, false, "", null), (3, true, "TITLE", "title"), (2, true, "ARTIST", "artist"), (2, true, "ALBUM", "album"), (1.2, true, "GENRE", null),
                (52, false, "YEAR", "year"), (52, false, "PLAYS", "plays"), (96, false, "FORMAT", null), (56, false, "TIME", "length"),
            };
            var cur = sortPage != null ? Settings.Current.Sort(sortPage, sortPage == "detail" ? "default" : "title") : "";
            for (int i = 0; i < cols.Length; i++)
            {
                var c = cols[i];
                ListHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(c.w, c.star ? GridUnitType.Star : GridUnitType.Pixel) });
                if (c.label == "") continue;
                bool active = c.sort != null && cur.StartsWith(c.sort);
                var t = new TextBlock { Text = c.label + (active ? (cur.EndsWith(":desc") ? " ▾" : " ▴") : ""), FontSize = 10.5, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 0, 10, 0) };
                t.SetResourceReference(TextBlock.ForegroundProperty, active ? "AccentBrush" : "Text2Brush");
                if (i == cols.Length - 1) t.HorizontalAlignment = HorizontalAlignment.Right;
                if (sortPage != null && c.sort != null)
                {
                    var id = c.sort;
                    t.Cursor = Cursors.Hand;
                    t.MouseLeftButtonUp += (s, e) => SetSort(sortPage, cur == id ? id + ":desc" : id);
                }
                Grid.SetColumn(t, i);
                ListHeader.Children.Add(t);
            }
        }

        // ================================================================ albums, artists, genres, folders
        void ShowAlbums()
        {
            var items = Library.Albums.Select(a =>
            {
                var al = a;
                return new GridItem
                {
                    Title = a.Title, Sub = a.Artist, Col2 = a.Artist, Col3 = a.Year > 0 ? a.Year.ToString() : "", Year = a.Year,
                    Count = a.Songs.Count, Ms = a.Songs.Sum(s => s.DurationMs), Cover = a.First, Songs = a.Songs,
                    Open = () => OpenDetail(al.Title, al.Artist + " · " + al.Subtitle, al.Songs, al.First)
                };
            }).ToList();
            ShowItems("albums", "Albums", Library.Albums.Count + " albums", items, "ARTIST", "YEAR",
                new[] { ("title", "Title"), ("col2", "Artist"), ("year", "Year"), ("count", "Number of songs"), ("length", "Length"), ("plays", "Most played") });
        }

        void ShowArtists()
        {
            var items = Library.Artists.Select(a =>
            {
                var ar = a;
                return new GridItem
                {
                    Title = a.Name, Sub = a.Subtitle, Col2 = a.AlbumCount + (a.AlbumCount == 1 ? " album" : " albums"), Col3 = "", Round = true,
                    Count = a.Songs.Count, Ms = a.Songs.Sum(s => s.DurationMs), Cover = a.First, Songs = a.Songs,
                    Open = () => OpenDetail(ar.Name, ar.Subtitle, ar.Songs, ar.First)
                };
            }).ToList();
            ShowItems("artists", "Artists", Library.Artists.Count + " artists", items, "ALBUMS", "",
                new[] { ("title", "Name"), ("count", "Number of songs"), ("length", "Length"), ("plays", "Most played") });
        }

        void ShowGroups(string title, List<GroupInfo> groups, bool folders)
        {
            var items = groups.Select(g =>
            {
                var gr = g;
                int artists = g.Songs.Select(s => s.Artist).Distinct().Count();
                return new GridItem
                {
                    Title = g.Name, Sub = folders ? Songs(g.Songs.Count) + " · " + ShortDir(g.Detail) : g.Subtitle, Tip = folders ? g.Detail : null,
                    Col2 = folders ? g.Detail : artists + (artists == 1 ? " artist" : " artists"), Col3 = "",
                    Count = g.Songs.Count, Ms = g.Songs.Sum(s => s.DurationMs), Cover = g.First, Songs = g.Songs,
                    Open = () => OpenDetail(gr.Name, folders ? gr.Detail : gr.Subtitle, gr.Songs, gr.First)
                };
            }).ToList();
            var page = folders ? "folders" : "genres";
            ShowItems(page, title, groups.Count + (folders ? (groups.Count == 1 ? " folder" : " folders") : (groups.Count == 1 ? " genre" : " genres")), items,
                folders ? "LOCATION" : "ARTISTS", "",
                folders ? new[] { ("title", "Name"), ("col2", "Location"), ("count", "Number of songs"), ("length", "Length") }
                        : new[] { ("title", "Name"), ("count", "Number of songs"), ("length", "Length"), ("plays", "Most played") });
        }

        void ShowItems(string page, string title, string sub, List<GridItem> items, string col2Label, string col3Label, (string, string)[] sorts)
        {
            ShowGrid(title, sub);
            BuildTools(GridTools, page, GridModes, "grid", page, sorts, "title");
            items = SortItems(items, page);
            var mode = Settings.Current.Mode(page, "grid");
            if (mode == "grid" || mode == "small")
            {
                int size = mode == "small" ? 120 : 168;
                foreach (var it in items)
                {
                    var i = it;
                    var tile = Tile(it.Title, it.Sub, it.Cover, it.Round, size);
                    if (it.Tip != null) tile.ToolTip = it.Tip;
                    tile.MouseLeftButtonUp += (s, e) => i.Open();
                    TileMenu(tile, () => i.Songs);
                    GridPanel.Children.Add(tile);
                }
                Cascade(GridPanel);
                return;
            }
            bool details = mode == "details";
            if (details) BuildGridHeader(page, col2Label, col3Label);
            foreach (var it in items) GridRows.Children.Add(ItemRow(it, details));
            var rows = GridRows.Children.OfType<FrameworkElement>().ToList();
            if (quietNav) foreach (var r in rows) r.Opacity = 1;
            else Bounce.Enter(rows, 30, 30);
        }

        static ColumnDefinition[] ItemColumns() => new[]
        {
            new ColumnDefinition { Width = new GridLength(52) }, new ColumnDefinition { Width = new GridLength(3, GridUnitType.Star) },
            new ColumnDefinition { Width = new GridLength(2, GridUnitType.Star) }, new ColumnDefinition { Width = new GridLength(70) },
            new ColumnDefinition { Width = new GridLength(70) }, new ColumnDefinition { Width = new GridLength(80) },
        };

        void BuildGridHeader(string page, string col2, string col3)
        {
            GridHeader.Visibility = Visibility.Visible;
            foreach (var c in ItemColumns()) GridHeader.ColumnDefinitions.Add(c);
            var cur = Settings.Current.Sort(page, "title");
            var labels = new (string text, string sort)[] { ("", null), ("NAME", "title"), (col2, "col2"), (col3, col3 == "YEAR" ? "year" : null), ("SONGS", "count"), ("LENGTH", "length") };
            for (int i = 1; i < labels.Length; i++)
            {
                if (string.IsNullOrEmpty(labels[i].text)) continue;
                bool active = labels[i].sort != null && cur.StartsWith(labels[i].sort);
                var t = new TextBlock { Text = labels[i].text + (active ? (cur.EndsWith(":desc") ? " ▾" : " ▴") : ""), FontSize = 10.5, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 0, 10, 0) };
                t.SetResourceReference(TextBlock.ForegroundProperty, active ? "AccentBrush" : "Text2Brush");
                if (i == labels.Length - 1) t.HorizontalAlignment = HorizontalAlignment.Right;
                if (labels[i].sort != null)
                {
                    var id = labels[i].sort; t.Cursor = Cursors.Hand;
                    t.MouseLeftButtonUp += (s, e) => SetSort(page, cur == id ? id + ":desc" : id);
                }
                Grid.SetColumn(t, i); GridHeader.Children.Add(t);
            }
        }

        FrameworkElement ItemRow(GridItem it, bool details)
        {
            var row = new Border { CornerRadius = new CornerRadius(12), Padding = new Thickness(10, details ? 5 : 8, 14, details ? 5 : 8), Margin = new Thickness(0, 1, 0, 1), Cursor = Cursors.Hand, Background = Brushes.Transparent };
            if (it.Tip != null) row.ToolTip = it.Tip;
            var g = new Grid();
            int coverSize = details ? 36 : 52;
            var cover = new Border { Width = coverSize, Height = coverSize, CornerRadius = new CornerRadius(it.Round ? coverSize / 2.0 : 8), HorizontalAlignment = HorizontalAlignment.Left };
            CoverLoader.SetSong(cover, it.Cover);
            g.Children.Add(cover);
            if (details)
            {
                foreach (var c in ItemColumns()) g.ColumnDefinitions.Add(c);
                AddCell(g, 1, it.Title, true, false);
                AddCell(g, 2, it.Col2, false, false);
                AddCell(g, 3, it.Col3, false, false);
                AddCell(g, 4, it.Count.ToString(), false, false);
                AddCell(g, 5, Library.Format(it.Ms), false, true);
            }
            else
            {
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(coverSize + 16) });
                g.ColumnDefinitions.Add(new ColumnDefinition());
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var sp = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                sp.Children.Add(new TextBlock { Text = it.Title, FontSize = 15, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis });
                var sub = new TextBlock { Text = it.Sub, FontSize = 12.5, Margin = new Thickness(0, 2, 0, 0), TextTrimming = TextTrimming.CharacterEllipsis };
                sub.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
                sp.Children.Add(sub);
                Grid.SetColumn(sp, 1); g.Children.Add(sp);
                var right = new TextBlock { Text = Songs(it.Count) + " · " + Library.Format(it.Ms), FontSize = 12.5, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 0, 0) };
                right.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
                Grid.SetColumn(right, 2); g.Children.Add(right);
            }
            row.Child = g;
            row.MouseEnter += (s, e) => row.SetResourceReference(Border.BackgroundProperty, "HoverBrush");
            row.MouseLeave += (s, e) => row.Background = Brushes.Transparent;
            row.MouseLeftButtonUp += (s, e) => it.Open();
            TileMenu(row, () => it.Songs);
            Bounce.Press(row);
            return row;
        }

        static void AddCell(Grid g, int col, string text, bool bold, bool right)
        {
            var t = new TextBlock { Text = text ?? "", FontSize = bold ? 13.5 : 12.5, FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal, VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(0, 0, 10, 0) };
            if (!bold) t.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
            if (right) t.HorizontalAlignment = HorizontalAlignment.Right;
            Grid.SetColumn(t, col); g.Children.Add(t);
        }

        // ================================================================ themes: cards, small cards, list
        void ShowThemes()
        {
            ShowGrid("Themes", $"{ThemeManager.All.Count} themes · the same looks as GEN-Z on Android");
            BuildTools(GridTools, "themes", ThemeModes, "cards", null, null, null);
            var mode = Settings.Current.Mode("themes", "cards");
            foreach (var t in ThemeManager.All)
            {
                var theme = t;
                if (mode == "list") { GridRows.Children.Add(ThemeRow(theme)); continue; }
                var card = ThemeCard(theme);
                if (mode == "small") card.LayoutTransform = new ScaleTransform(0.72, 0.72);
                var holder = new Border { Child = card };
                Bounce.Press(holder);
                GridPanel.Children.Add(holder);
            }
            if (mode == "list")
            {
                var rows = GridRows.Children.OfType<FrameworkElement>().ToList();
                if (quietNav) foreach (var r in rows) r.Opacity = 1; else Bounce.Enter(rows, 26, 30);
            }
            else Cascade(GridPanel);
        }

        void ApplyTheme(Theme theme) { ThemeManager.Apply(theme); Settings.Save(); RefreshCoverGlow(); }

        FrameworkElement ThemeCard(Theme t)
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
            body.Children.Add(Swatches(t, 20));
            var bar = new Border { Height = 5, CornerRadius = new CornerRadius(2.5), Margin = new Thickness(0, 14, 40, 0), HorizontalAlignment = HorizontalAlignment.Stretch, Background = ProgressBrush(t) };
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
            card.MouseLeftButtonUp += (s, e) => ApplyTheme(theme);
            return card;
        }

        static StackPanel Swatches(Theme t, double size)
        {
            var sw = new StackPanel { Orientation = Orientation.Horizontal };
            foreach (var key in new[] { "accent", "header", "headerEnd", "surface", "text" })
                sw.Children.Add(new Ellipse { Width = size, Height = size, Margin = new Thickness(0, 0, size * 0.3, 0), Fill = new SolidColorBrush(t.C(key, Colors.Gray)), Stroke = new SolidColorBrush(Color.FromArgb(60, 128, 128, 128)) });
            return sw;
        }

        static Brush ProgressBrush(Theme t)
        {
            var pcs = t.ProgressColors.Count > 0 ? t.ProgressColors : new List<Color> { t.Header, t.Accent, t.HeaderEnd };
            var pg = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 0) };
            for (int i = 0; i < pcs.Count; i++) pg.GradientStops.Add(new GradientStop(pcs[i], pcs.Count == 1 ? 0 : (double)i / (pcs.Count - 1)));
            return pg;
        }

        FrameworkElement ThemeRow(Theme t)
        {
            var theme = t;
            bool current = t == ThemeManager.Current;
            var row = new Border { CornerRadius = new CornerRadius(14), Padding = new Thickness(10, 8, 16, 8), Margin = new Thickness(0, 2, 0, 2), Cursor = Cursors.Hand, Background = Brushes.Transparent, BorderThickness = new Thickness(current ? 2 : 0) };
            if (current) row.SetResourceReference(Border.BorderBrushProperty, "AccentBrush");
            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(150) });
            g.ColumnDefinitions.Add(new ColumnDefinition());
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(150) });
            var chip = new Border { Height = 40, CornerRadius = new CornerRadius(10), Background = new LinearGradientBrush(t.Header, t.HeaderEnd, 0), Margin = new Thickness(0, 0, 16, 0) };
            chip.Child = new TextBlock { Text = "Aa", FontFamily = ThemeManager.Font(t.DisplayFont), FontSize = 18, FontWeight = FontWeights.Bold, Foreground = new SolidColorBrush(t.C("headerText", Colors.White)), VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
            g.Children.Add(chip);
            var name = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            name.Children.Add(new TextBlock { Text = t.Name + (current ? "  ✓" : ""), FontFamily = ThemeManager.Font(t.DisplayFont), FontSize = 16, FontWeight = FontWeights.Bold });
            var sub = new TextBlock { Text = (t.Dark ? "Dark" : "Light") + " · " + Cap(t.ProgressStyle) + " bar", FontSize = 12 };
            sub.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
            name.Children.Add(sub);
            Grid.SetColumn(name, 1); g.Children.Add(name);
            var sw = Swatches(t, 18); sw.VerticalAlignment = VerticalAlignment.Center; sw.Margin = new Thickness(0, 0, 16, 0);
            Grid.SetColumn(sw, 2); g.Children.Add(sw);
            var bar = new Border { Height = 6, CornerRadius = new CornerRadius(3), Background = ProgressBrush(t), VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(bar, 3); g.Children.Add(bar);
            row.Child = g;
            row.MouseEnter += (s, e) => row.SetResourceReference(Border.BackgroundProperty, "HoverBrush");
            row.MouseLeave += (s, e) => row.Background = Brushes.Transparent;
            row.MouseLeftButtonUp += (s, e) => ApplyTheme(theme);
            Bounce.Press(row);
            return row;
        }

        // ================================================================ Now Playing panel slides open and closed
        bool panelAnimating;

        void OnTogglePanel(object sender, RoutedEventArgs e)
        {
            if (panelAnimating) return;
            bool open = NpColumn.Width.Value > 0;
            Settings.Current.NowPlayingOpen = !open;
            SlidePanel(!open);
        }

        void OnBarSongClick(object sender, MouseButtonEventArgs e)
        {
            if (NpColumn.Width.Value <= 0 && !panelAnimating) { Settings.Current.NowPlayingOpen = true; SlidePanel(true); }
        }

        void SlidePanel(bool open)
        {
            const double full = 380;
            double from = NpColumn.Width.Value, to = open ? full : 0;
            if (open) { NpPanel.Visibility = Visibility.Visible; UpdateVolumeBox(open: true); }
            var dur = Settings.Ms(380).TotalMilliseconds;
            if (dur <= 0) { NpColumn.Width = new GridLength(to); UpdateVolumeBox(); return; }
            panelAnimating = true;
            var start = DateTime.Now;
            var ease = new CubicEase { EasingMode = EasingMode.EaseInOut };
            // contents slide and fade with the column
            var tt = new TranslateTransform(open ? 60 : 0, 0);
            NpPanel.RenderTransform = tt;
            tt.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(open ? 60 : 0, open ? 0 : 60, TimeSpan.FromMilliseconds(dur)) { EasingFunction = ease });
            NpPanel.BeginAnimation(OpacityProperty, new DoubleAnimation(open ? 0 : 1, open ? 1 : 0, TimeSpan.FromMilliseconds(dur)));
            EventHandler tick = null;
            tick = (s, e) =>
            {
                double t = Math.Min(1, (DateTime.Now - start).TotalMilliseconds / dur);
                NpColumn.Width = new GridLength(from + (to - from) * ease.Ease(t));
                if (t >= 1)
                {
                    System.Windows.Media.CompositionTarget.Rendering -= tick;
                    panelAnimating = false;
                    NpPanel.BeginAnimation(OpacityProperty, null); NpPanel.Opacity = 1;
                    NpPanel.RenderTransform = Transform.Identity;
                    UpdateVolumeBox();
                    FitCover();
                }
            };
            System.Windows.Media.CompositionTarget.Rendering += tick;
            Controls.Fx.Pop(BarNpBtn);
        }

        // ================================================================ equalizer bands rise in
        void EqIntro()
        {
            if (Settings.Current.ReduceMotion) return;
            int i = 0;
            foreach (FrameworkElement col in EqBands.Children) Stagger.Rise(col, 200 + i++ * 45, 70);
        }
    }
}
