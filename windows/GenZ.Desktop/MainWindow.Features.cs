using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Threading;
using GenZ.Controls;
using GenZ.Core;

namespace GenZ
{
    /// <summary>Playlists, right-click menus, queue editing, drag &amp; drop, lyrics, sleep timer, toasts.</summary>
    public partial class MainWindow
    {
        const string SongsFormat = "GenZSongs";
        Playlist openPlaylist;

        void InitFeatures()
        {
            Playlists.Load();
            Playlists.Changed += BuildNavPlaylists;
            BuildNavPlaylists();
            Player.QueueChanged += () => { if (view == View.Queue) ShowView(View.Queue, true); SaveSession(); };
            Player.SleepEnded += () => { sleepAt = null; UpdateSleepChip(); ShowToast("Sleep timer: the song ended, music paused. Good night ♪"); };
            SongList.ContextMenu = new ContextMenu(); // replaced on opening by the song menu
            ListAnim.Attach(SongList);
            foreach (var rb in NavPanel.Children.OfType<RadioButton>()) Bounce.Press(rb);
            UpdateVolumeBox();
            if (Settings.Current.LyricsOpen) { NpLyrics.Visibility = Visibility.Visible; NpLyricsBtn.Foreground = (Brush)FindResource("AccentBrush"); }
        }

        // ================================================================ toast
        DispatcherTimer toastTimer;
        void ShowToast(string text)
        {
            ToastText.Text = text;
            Toast.Visibility = Visibility.Visible;
            var tt = new TranslateTransform(0, 18); Toast.RenderTransform = tt;
            tt.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(18, 0, Settings.Ms(360)) { EasingFunction = new BackEase { Amplitude = 0.5, EasingMode = EasingMode.EaseOut } });
            Toast.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(200)));
            if (toastTimer == null)
            {
                toastTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(2600) };
                toastTimer.Tick += (s, e) =>
                {
                    toastTimer.Stop();
                    var a = new DoubleAnimation(0, TimeSpan.FromMilliseconds(260));
                    a.Completed += (o, x) => { if (!toastTimer.IsEnabled) Toast.Visibility = Visibility.Collapsed; };
                    Toast.BeginAnimation(OpacityProperty, a);
                };
            }
            toastTimer.Stop(); toastTimer.Start();
        }

        static string Songs(int n) => n + (n == 1 ? " song" : " songs");

        // ================================================================ volume: one slider at a time
        void UpdateVolumeBox(bool? open = null) => BarVolumeBox.Visibility = (open ?? NpColumn.Width.Value > 0) ? Visibility.Collapsed : Visibility.Visible;

        // ================================================================ session
        void SaveSession()
        {
            var c = Settings.Current;
            if (c.RememberQueue && Player.Queue.Count > 0)
            {
                c.LastQueue = Player.Queue.Select(s => s.Path).ToList();
                c.LastIndex = Player.Index;
                c.LastPositionMs = (long)Player.Position.TotalMilliseconds;
            }
            Settings.Save();
        }

        bool RestoreSession()
        {
            var c = Settings.Current;
            if (!c.RememberQueue || c.LastQueue == null || c.LastQueue.Count == 0) return false;
            string curPath = c.LastIndex >= 0 && c.LastIndex < c.LastQueue.Count ? c.LastQueue[c.LastIndex] : c.LastSong;
            var q = c.LastQueue.Select(p => Library.ByPath.TryGetValue(p, out var s) ? s : (System.IO.File.Exists(p) ? Library.Read(p) : null)).Where(x => x != null).ToList();
            if (q.Count == 0) return false;
            int idx = q.FindIndex(s => string.Equals(s.Path, curPath, StringComparison.OrdinalIgnoreCase));
            var pos = TimeSpan.FromMilliseconds(c.LastPositionMs);
            Player.Restore(q, idx < 0 ? 0 : idx, pos);
            c.LastPositionMs = (long)pos.TotalMilliseconds;
            return true;
        }

        // ================================================================ extra library views
        static string ShortDir(string dir)
        {
            var parent = System.IO.Path.GetDirectoryName(dir ?? "");
            return string.IsNullOrEmpty(parent) ? dir : System.IO.Path.GetFileName(parent.TrimEnd('\\')) is string n && n.Length > 0 ? n : parent;
        }

        void TileMenu(FrameworkElement tile, Func<List<Song>> songs)
        {
            tile.MouseRightButtonUp += (s, e) =>
            {
                var list = songs();
                if (list.Count == 0) return;
                var m = SongsMenu(list, "tile");
                m.PlacementTarget = tile; m.IsOpen = true; e.Handled = true;
            };
        }

        List<Song> RecentSongs() => Settings.Current.Recent.Select(p => Library.ByPath.TryGetValue(p, out var s) ? s : null).Where(s => s != null).ToList();

        List<Song> MostPlayed() => Settings.Current.PlayCounts.Where(kv => kv.Value > 0).OrderByDescending(kv => kv.Value)
            .Select(kv => Library.ByPath.TryGetValue(kv.Key, out var s) ? s : null).Where(s => s != null).Take(100).ToList();

        void ShowPlaylist(Playlist p)
        {
            if (p == null || !Playlists.All.Contains(p)) { ShowView(View.Songs, true); NavSongs.IsChecked = true; return; }
            var songs = Playlists.Songs(p);
            var total = Library.Format(songs.Sum(s => s.DurationMs));
            ShowList(p.Name, songs.Count == 0 ? "Empty playlist – right-click songs › Add to playlist, or drag songs here" : Songs(songs.Count) + " · " + total, songs, songs.FirstOrDefault());
        }

        // ================================================================ playlists in the navigation
        void BuildNavPlaylists()
        {
            NavPlaylists.Children.Clear();
            foreach (var p in Playlists.All)
            {
                var pl = p;
                var rb = new RadioButton { Style = (Style)FindResource("NavItem"), Content = p.Name, Tag = FindResource("IcPlaylist"), AllowDrop = true };
                if (view == View.Playlist && openPlaylist == p) rb.IsChecked = true;
                rb.Checked += (s, e) => { if (!IsInitialized) return; openPlaylist = pl; ShowView(View.Playlist); };
                rb.MouseRightButtonUp += (s, e) => { var m = PlaylistMenu(pl); m.PlacementTarget = rb; m.IsOpen = true; e.Handled = true; };
                rb.DragOver += (s, e) => { e.Effects = e.Data.GetDataPresent(SongsFormat) ? DragDropEffects.Copy : DragDropEffects.None; e.Handled = true; };
                rb.DragEnter += (s, e) => { if (e.Data.GetDataPresent(SongsFormat)) Bounce.Squish(rb, true); };
                rb.DragLeave += (s, e) => Bounce.Squish(rb, false);
                rb.Drop += (s, e) =>
                {
                    Bounce.Squish(rb, false);
                    if (e.Data.GetData(SongsFormat) is List<Song> songs) { Playlists.Add(pl, songs); ShowToast("Added " + Songs(songs.Count) + " to " + pl.Name); }
                    e.Handled = true;
                };
                Bounce.Press(rb);
                NavPlaylists.Children.Add(rb);
            }
            NoPlaylists.Visibility = Playlists.All.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            if (view == View.Playlist && openPlaylist != null && Playlists.All.Contains(openPlaylist)) ShowView(View.Playlist, true);
        }

        Playlist NewPlaylist(IList<Song> songs = null)
        {
            var name = Dialogs.Prompt(this, "New playlist", "Give your playlist a name", Playlists.UniqueName("My playlist"), "Create");
            if (name == null) return null;
            var p = Playlists.Create(name, songs);
            ShowToast(songs == null || songs.Count == 0 ? "Created " + p.Name : "Created " + p.Name + " with " + Songs(songs.Count));
            return p;
        }

        void OpenPlaylist(Playlist p)
        {
            openPlaylist = p;
            var rb = NavPlaylists.Children.OfType<RadioButton>().FirstOrDefault(r => (string)r.Content == p.Name);
            if (rb != null && rb.IsChecked != true) rb.IsChecked = true; else ShowView(View.Playlist);
        }

        void OnNewPlaylist(object sender, RoutedEventArgs e) { var p = NewPlaylist(); if (p != null) OpenPlaylist(p); }

        void OnImportPlaylist(object sender, RoutedEventArgs e)
        {
            var d = new Microsoft.Win32.OpenFileDialog { Filter = "Playlists (*.m3u, *.m3u8)|*.m3u;*.m3u8|All files|*.*", Title = "Import a playlist" };
            if (d.ShowDialog(this) != true) return;
            try
            {
                var p = Playlists.ImportM3u(d.FileName);
                ShowToast("Imported " + p.Name + " · " + Songs(p.Paths.Count));
                OpenPlaylist(p);
            }
            catch (Exception ex) { Dialogs.Message(this, "Couldn't import", ex.Message); }
        }

        void ExportPlaylist(Playlist p)
        {
            var d = new Microsoft.Win32.SaveFileDialog { Filter = "M3U8 playlist (*.m3u8)|*.m3u8|M3U playlist (*.m3u)|*.m3u", FileName = p.Name + ".m3u8", Title = "Export playlist" };
            if (d.ShowDialog(this) != true) return;
            try { Playlists.ExportM3u(p, d.FileName); ShowToast("Exported " + p.Name); }
            catch (Exception ex) { Dialogs.Message(this, "Couldn't export", ex.Message); }
        }

        void RenamePlaylist(Playlist p)
        {
            var name = Dialogs.Prompt(this, "Rename playlist", "New name", p.Name);
            if (name != null) Playlists.Rename(p, name);
        }

        void DeletePlaylist(Playlist p)
        {
            if (!Dialogs.Confirm(this, "Delete playlist", "Delete “" + p.Name + "”? Your songs stay on your computer – only the playlist is removed.", "Delete")) return;
            bool open = openPlaylist == p && view == View.Playlist;
            Playlists.Delete(p);
            ShowToast("Deleted " + p.Name);
            if (open) { openPlaylist = null; NavSongs.IsChecked = true; }
        }

        ContextMenu PlaylistMenu(Playlist p)
        {
            var m = new ContextMenu();
            m.Items.Add(Item("Play", "IcPlay", () => { var s = Playlists.Songs(p); if (s.Count > 0) Player.PlayList(s, 0); }));
            m.Items.Add(Item("Shuffle", "IcShuffle", () => { var s = Playlists.Songs(p); if (s.Count > 0) { Settings.Current.Shuffle = true; Player.PlayList(s, new Random().Next(s.Count)); UpdateToggles(); } }));
            m.Items.Add(Item("Add to queue", "IcQueue", () => { Player.Enqueue(Playlists.Songs(p)); ShowToast("Added " + p.Name + " to Up next"); }));
            m.Items.Add(new Separator());
            m.Items.Add(Item("Rename…", "IcEdit", () => RenamePlaylist(p)));
            m.Items.Add(Item("Export as M3U…", "IcExport", () => ExportPlaylist(p)));
            m.Items.Add(Item("Delete playlist", "IcDelete", () => DeletePlaylist(p)));
            return m;
        }

        void OnListMore(object sender, RoutedEventArgs e)
        {
            ContextMenu m;
            if (view == View.Playlist && openPlaylist != null) m = PlaylistMenu(openPlaylist);
            else if (view == View.Queue)
            {
                m = new ContextMenu();
                m.Items.Add(Item("Save queue as playlist…", "IcPlaylistAdd", () => { var p = NewPlaylist(Player.Queue.ToList()); if (p != null) OpenPlaylist(p); }));
                m.Items.Add(Item("Clear Up next", "IcDelete", () => { Player.ClearQueue(); ShowToast("Cleared Up next"); }));
            }
            else return;
            m.PlacementTarget = ListMore; m.Placement = PlacementMode.Bottom; m.IsOpen = true;
        }

        // ================================================================ song menus
        MenuItem Item(string header, string icon, Action click, string gesture = null)
        {
            var mi = new MenuItem { Header = header, InputGestureText = gesture ?? "" };
            if (icon != null)
            {
                var path = new Path { Data = (Geometry)FindResource(icon), Width = 15, Height = 15, Stretch = Stretch.Uniform };
                path.SetResourceReference(Shape.FillProperty, "AccentBrush");
                mi.Icon = path;
            }
            if (click != null) mi.Click += (s, e) => click();
            return mi;
        }

        ContextMenu SongsMenu(IList<Song> songs, string context)
        {
            var m = new ContextMenu();
            var list = songs.ToList();
            bool one = list.Count == 1;
            m.Items.Add(Item(one ? "Play" : "Play " + Songs(list.Count), "IcPlay", () =>
            {
                if (context == "list" && one) PlayFromList(list[0]); else Player.PlayList(list, 0);
            }, one && context == "list" ? "Enter" : null));
            m.Items.Add(Item("Play next", "IcPlayNext", () => { Player.PlayNext(list); ShowToast(one ? "“" + list[0].Title + "” plays next" : Songs(list.Count) + " play next"); }));
            m.Items.Add(Item("Add to Up next", "IcQueue", () => { Player.Enqueue(list); ShowToast("Added " + Songs(list.Count) + " to Up next"); }));

            var add = Item("Add to playlist", "IcPlaylistAdd", null);
            add.Items.Add(Item("New playlist…", "IcAdd", () => { var p = NewPlaylist(list); }));
            if (Playlists.All.Count > 0) add.Items.Add(new Separator());
            foreach (var p in Playlists.All)
            {
                var pl = p;
                add.Items.Add(Item(p.Name, "IcPlaylist", () => { Playlists.Add(pl, list); ShowToast("Added " + Songs(list.Count) + " to " + pl.Name); }));
            }
            m.Items.Add(add);

            if (context == "list" && view == View.Playlist && openPlaylist != null)
                m.Items.Add(Item("Remove from this playlist", "IcDelete", RemoveSelected, "Del"));
            if (context == "list" && view == View.Queue)
                m.Items.Add(Item("Remove from Up next", "IcDelete", RemoveSelected, "Del"));

            m.Items.Add(new Separator());
            bool allFav = list.All(s => Settings.Current.Favourites.Contains(s.Path));
            m.Items.Add(Item(allFav ? "Remove from Favourites" : "Add to Favourites", allFav ? "IcHeartOutline" : "IcHeart", () =>
            {
                var favs = Settings.Current.Favourites;
                foreach (var s in list) { if (allFav) favs.Remove(s.Path); else if (!favs.Contains(s.Path)) favs.Add(s.Path); }
                Settings.Save(); UpdateFav(Player.Current ?? shownSong);
                ShowToast(allFav ? "Removed from Favourites" : "Added to Favourites ♥");
                if (view == View.Favourites) ShowView(View.Favourites, true);
            }));

            if (one)
            {
                var s = list[0];
                m.Items.Add(new Separator());
                var album = Library.Albums.FirstOrDefault(a => a.Key == s.AlbumKey);
                var goAlbum = Item("Go to album", "IcAlbum", () => OpenDetail(album.Title, album.Artist + " · " + album.Subtitle, album.Songs, album.First));
                goAlbum.IsEnabled = album != null;
                m.Items.Add(goAlbum);
                var artist = Library.Artists.FirstOrDefault(a => string.Equals(a.Name, s.Artist, StringComparison.CurrentCultureIgnoreCase));
                var goArtist = Item("Go to artist", "IcPerson", () => OpenDetail(artist.Name, artist.Subtitle, artist.Songs, artist.First));
                goArtist.IsEnabled = artist != null;
                m.Items.Add(goArtist);
                m.Items.Add(Item("Show in folder", "IcFolder", () => ShowInFolder(s.Path)));
                m.Items.Add(Item("Song info & tags…", "IcEdit", () => EditSong(s), "Ctrl+I"));
            }
            return m;
        }

        public static void ShowInFolder(string path)
        {
            try { Process.Start("explorer.exe", "/select,\"" + path + "\""); } catch { }
        }

        void EditSong(Song s)
        {
            if (s == null) return;
            var w = new SongInfoWindow(this, s);
            if (w.ShowDialog() == true && w.Saved)
            {
                Library.Refresh();
                if (s == shownSong) ShowSong(s);
                if (s == Player.Current) LoadLyrics(s);
                ShowToast("Saved tags for “" + s.Title + "”");
            }
        }

        List<int> SelectedIndexes()
        {
            var set = new HashSet<object>(SongList.SelectedItems.Cast<object>());
            var r = new List<int>();
            for (int i = 0; i < listed.Count; i++) if (set.Contains(listed[i])) r.Add(i);
            return r;
        }

        List<Song> SelectedSongs() => SelectedIndexes().Select(i => listed[i]).Distinct().ToList();

        /// <summary>Maps positions in the shown playlist to positions in its stored paths (missing files are not shown).</summary>
        List<int> PathIndexes(Playlist p)
        {
            var map = new List<int>(); int j = 0;
            for (int i = 0; i < listed.Count; i++)
            {
                while (j < p.Paths.Count && !string.Equals(p.Paths[j], listed[i].Path, StringComparison.OrdinalIgnoreCase)) j++;
                map.Add(j); j++;
            }
            return map;
        }

        void RemoveSelected()
        {
            var idx = SelectedIndexes();
            if (idx.Count == 0) return;
            if (view == View.Queue) { Player.RemoveAt(idx); ShowToast("Removed " + Songs(idx.Count) + " from Up next"); }
            else if (view == View.Playlist && openPlaylist != null)
            {
                var map = PathIndexes(openPlaylist);
                Playlists.RemoveAt(openPlaylist, idx.Select(i => map[i]));
                ShowToast("Removed " + Songs(idx.Count) + " from " + openPlaylist.Name);
            }
        }

        void OnSongMenu(object sender, ContextMenuEventArgs e)
        {
            e.Handled = true;
            var songs = SelectedSongs();
            if (songs.Count == 0) return;
            var m = SongsMenu(songs, "list");
            m.PlacementTarget = SongList; m.Placement = PlacementMode.MousePoint; m.IsOpen = true;
        }

        void OnCoverMenu(object sender, MouseButtonEventArgs e)
        {
            var s = Player.Current ?? shownSong;
            if (s == null) return;
            var m = SongsMenu(new List<Song> { s }, "np");
            m.Items.Insert(0, Item(NpLyrics.Visibility == Visibility.Visible ? "Hide lyrics" : "Show lyrics", "IcLyrics", () => OnLyricsToggle(this, null), "Ctrl+L"));
            m.PlacementTarget = NpCoverHost; m.Placement = PlacementMode.MousePoint; m.IsOpen = true;
            e.Handled = true;
        }

        // ================================================================ press, drag & drop in the song list
        Point dragStart;
        Song dragSong;
        int dragFrom = -1;
        bool dragged;
        ListBoxItem pressedRow;
        Rectangle dropLine;

        static ListBoxItem RowOf(object source)
        {
            var el = source as DependencyObject;
            while (el != null && !(el is ListBoxItem)) el = el is Visual || el is System.Windows.Media.Media3D.Visual3D ? VisualTreeHelper.GetParent(el) : LogicalTreeHelper.GetParent(el);
            return el as ListBoxItem;
        }

        void OnRowDown(object sender, MouseButtonEventArgs e)
        {
            dragged = false;
            dragStart = e.GetPosition(SongList);
            pressedRow = RowOf(e.OriginalSource);
            dragSong = pressedRow?.DataContext as Song;
            dragFrom = dragSong != null ? SongList.ItemContainerGenerator.IndexFromContainer(pressedRow) : -1;
            if (pressedRow != null) Bounce.Squish(pressedRow, true);
        }

        void ReleasePress()
        {
            if (pressedRow != null) { Bounce.Squish(pressedRow, false); pressedRow = null; }
        }

        void OnRowMove(object sender, MouseEventArgs e)
        {
            if (e.LeftButton != MouseButtonState.Pressed || dragSong == null) return;
            var p = e.GetPosition(SongList);
            if (Math.Abs(p.X - dragStart.X) < 8 && Math.Abs(p.Y - dragStart.Y) < 8) return;
            dragged = true;
            ReleasePress();
            var songs = SongList.SelectedItems.Contains(dragSong) && SongList.SelectedItems.Count > 1 ? SelectedSongs() : new List<Song> { dragSong };
            var data = new DataObject(SongsFormat, songs);
            dragSong = null;
            try { DragDrop.DoDragDrop(SongList, data, DragDropEffects.Copy | DragDropEffects.Move); }
            catch { }
            HideDropLine();
            dragFrom = -1;
        }

        bool CanReorder => view == View.Queue || (view == View.Playlist && openPlaylist != null);

        int DropIndex(DragEventArgs e, out ListBoxItem row, out bool after)
        {
            row = RowOf(SongList.InputHitTest(e.GetPosition(SongList)) as DependencyObject);
            after = false;
            if (row == null) return listed.Count;
            int i = SongList.ItemContainerGenerator.IndexFromContainer(row);
            after = e.GetPosition(row).Y > row.ActualHeight / 2;
            return after ? i + 1 : i;
        }

        void OnListDragOver(object sender, DragEventArgs e)
        {
            if (!e.Data.GetDataPresent(SongsFormat)) return; // files from Explorer: handled by the window
            e.Handled = true;
            if (!CanReorder || dragFrom < 0) { e.Effects = DragDropEffects.None; HideDropLine(); return; }
            e.Effects = DragDropEffects.Move;
            DropIndex(e, out var row, out var after);
            ShowDropLine(row, after);
        }

        void OnListDragLeave(object sender, DragEventArgs e) => HideDropLine();

        void OnListDrop(object sender, DragEventArgs e)
        {
            if (!e.Data.GetDataPresent(SongsFormat)) return;
            e.Handled = true;
            HideDropLine();
            if (!CanReorder || dragFrom < 0) return;
            int to = DropIndex(e, out _, out _);
            int from = dragFrom;
            if (to > from) to--;
            if (to == from) return;
            if (view == View.Queue) Player.Move(from, to);
            else
            {
                var map = PathIndexes(openPlaylist);
                int pf = map[from], pt = to < map.Count ? map[to] : openPlaylist.Paths.Count;
                Playlists.Move(openPlaylist, pf, pt);
            }
            Dispatcher.BeginInvoke(new Action(() =>
            {
                if (to < listed.Count) { SongList.SelectedIndex = to; if (SongList.ItemContainerGenerator.ContainerFromIndex(to) is FrameworkElement r) Bounce.Squish(r, false); }
            }), DispatcherPriority.Loaded);
        }

        void ShowDropLine(ListBoxItem row, bool after)
        {
            var layer = AdornerLayerCanvas();
            if (dropLine == null)
            {
                dropLine = new Rectangle { Height = 3, RadiusX = 1.5, RadiusY = 1.5, IsHitTestVisible = false };
                dropLine.SetResourceReference(Shape.FillProperty, "AccentBrush");
            }
            if (dropLine.Parent == null) layer.Children.Add(dropLine);
            Point p; double w;
            if (row != null) { p = row.TranslatePoint(new Point(6, after ? row.ActualHeight : 0), layer); w = row.ActualWidth - 12; }
            else
            {
                var last = listed.Count > 0 ? SongList.ItemContainerGenerator.ContainerFromIndex(listed.Count - 1) as FrameworkElement : null;
                if (last == null) { HideDropLine(); return; }
                p = last.TranslatePoint(new Point(6, last.ActualHeight), layer); w = last.ActualWidth - 12;
            }
            dropLine.Width = Math.Max(10, w);
            Canvas.SetLeft(dropLine, p.X); Canvas.SetTop(dropLine, p.Y - 1.5);
        }

        void HideDropLine() { if (dropLine?.Parent is Panel pp) pp.Children.Remove(dropLine); }

        // files and folders dropped from Explorer
        void OnWindowDragOver(object sender, DragEventArgs e)
        {
            if (e.Handled) return;
            e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None;
            e.Handled = true;
        }

        void OnWindowDrop(object sender, DragEventArgs e)
        {
            if (e.Handled || !(e.Data.GetData(DataFormats.FileDrop) is string[] paths)) return;
            e.Handled = true;
            var files = Library.AudioFiles(paths);
            if (files.Count == 0) { ShowToast("No music files there"); return; }
            var songs = files.Select(Library.Get).ToList();
            if (view == View.Playlist && openPlaylist != null) { Playlists.Add(openPlaylist, songs); ShowToast("Added " + Songs(songs.Count) + " to " + openPlaylist.Name); }
            else if (view == View.Queue) { Player.Enqueue(songs); ShowToast("Added " + Songs(songs.Count) + " to Up next"); }
            else { Player.PlayList(songs, 0); ShowToast("Playing " + Songs(songs.Count)); }
        }

        // ================================================================ sleep timer
        DateTime? sleepAt;
        string sleepShown;

        void OnSleep(object sender, RoutedEventArgs e)
        {
            var m = new ContextMenu();
            var title = Item("Sleep timer", "IcMoon", null); title.IsEnabled = false; m.Items.Add(title);
            m.Items.Add(new Separator());
            var off = Item("Off", null, () => SetSleep(null, false)); off.IsChecked = sleepAt == null && !Player.StopAfterCurrent; m.Items.Add(off);
            foreach (var min in new[] { 5, 10, 15, 30, 45, 60, 90, 120 })
            {
                var mm = min;
                m.Items.Add(Item(min < 60 ? min + " minutes" : (min / 60.0).ToString("0.#") + (min == 60 ? " hour" : " hours"), null, () => SetSleep(TimeSpan.FromMinutes(mm), false)));
            }
            var eos = Item("End of this song", null, () => SetSleep(null, true)); eos.IsChecked = Player.StopAfterCurrent; m.Items.Add(eos);
            m.Items.Add(Item("Custom…", null, () =>
            {
                var v = Dialogs.Prompt(this, "Sleep timer", "Pause the music after how many minutes?", "25", "Start");
                if (v != null && double.TryParse(v, out var mins) && mins > 0) SetSleep(TimeSpan.FromMinutes(Math.Min(mins, 24 * 60)), false);
            }));
            m.PlacementTarget = SleepBtn; m.Placement = PlacementMode.Bottom; m.IsOpen = true;
        }

        void SetSleep(TimeSpan? after, bool endOfSong)
        {
            Player.StopAfterCurrent = endOfSong;
            sleepAt = after.HasValue ? DateTime.Now + after.Value : (DateTime?)null;
            Player.SleepGain = 1;
            sleepShown = null;
            UpdateSleepChip();
            if (endOfSong) ShowToast("Music will pause when this song ends");
            else if (after.HasValue) ShowToast("Music will fade out and pause in " + (after.Value.TotalMinutes >= 60 ? after.Value.TotalHours.ToString("0.#") + " h" : Math.Round(after.Value.TotalMinutes) + " min"));
            else ShowToast("Sleep timer off");
            Controls.Fx.Pop(SleepBtn);
        }

        void UpdateSleepChip()
        {
            bool on = sleepAt != null || Player.StopAfterCurrent;
            SleepChip.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
            SleepBtn.Opacity = on ? 1 : 0.85;
            if (Player.StopAfterCurrent) SleepText.Text = "End of song";
        }

        void SleepTick()
        {
            if (Player.StopAfterCurrent)
            {
                // fade the last 15 seconds of the song
                var left = (Player.Duration - Player.Position).TotalSeconds;
                Player.SleepGain = left < 15 ? Math.Max(0, left / 15) : 1;
                if (SleepChip.Visibility != Visibility.Visible) UpdateSleepChip();
                return;
            }
            if (sleepAt == null) return;
            var remain = sleepAt.Value - DateTime.Now;
            if (remain <= TimeSpan.Zero)
            {
                sleepAt = null;
                Player.Pause(false);
                Player.SleepGain = 1;
                UpdateSleepChip();
                ShowToast("Sleep timer: music paused. Good night ♪");
                return;
            }
            if (remain.TotalSeconds < 30) Player.SleepGain = remain.TotalSeconds / 30;
            var text = remain.TotalHours >= 1 ? $"{(int)remain.TotalHours}:{remain.Minutes:00}:{remain.Seconds:00}" : $"{remain.Minutes}:{remain.Seconds:00}";
            if (text != sleepShown) { sleepShown = text; SleepText.Text = text; }
        }

        // ================================================================ lyrics
        LyricsDoc lyricsDoc = new LyricsDoc();
        readonly List<TextBlock> lyricBlocks = new List<TextBlock>();
        int lyricIdx = -2;
        DateTime lyricsManualUntil;
        double lyricsY;
        int lyricsLoad;

        async void LoadLyrics(Song s)
        {
            int ticket = ++lyricsLoad;
            lyricsDoc = new LyricsDoc();
            BuildLyrics(s == null ? "Pick a song to see its lyrics." : "Looking for lyrics…");
            if (s == null) return;
            var d = await Task.Run(() => Lyrics.Load(s));
            if (ticket != lyricsLoad) return;
            lyricsDoc = d;
            BuildLyrics(null);
        }

        void BuildLyrics(string message)
        {
            LyricsPanel.Children.Clear(); lyricBlocks.Clear(); lyricIdx = -2;
            SetLyricsY(0, false);
            if (message != null || lyricsDoc.Empty)
            {
                LyricsPanel.Children.Add(new TextBlock
                {
                    Text = message ?? "No lyrics for this song yet.\n\nPut a .lrc file with the same name next to the song, or paste the lyrics in Song info (right-click the cover › Song info & tags).",
                    Foreground = Brushes.White, Opacity = 0.8, FontSize = 13.5, TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center, Margin = new Thickness(6, 40, 6, 0), LineHeight = 21
                });
                return;
            }
            LyricsPanel.Children.Add(new TextBlock
            {
                Text = (lyricsDoc.Synced ? "SYNCED LYRICS" : "LYRICS") + (lyricsDoc.Source != null ? " · " + lyricsDoc.Source : ""),
                Foreground = Brushes.White, Opacity = 0.5, FontSize = 10.5, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 0, 0, 10), TextTrimming = TextTrimming.CharacterEllipsis
            });
            foreach (var line in lyricsDoc.Lines)
            {
                var ln = line;
                var t = new TextBlock
                {
                    Text = string.IsNullOrWhiteSpace(line.Text) ? "♪" : line.Text, Foreground = Brushes.White, FontSize = 17, FontWeight = FontWeights.SemiBold,
                    TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 6, 0, 6), Opacity = lyricsDoc.Synced ? 0.4 : 0.9,
                    RenderTransformOrigin = new Point(0, 0.5), RenderTransform = new ScaleTransform(1, 1)
                };
                t.SetResourceReference(TextBlock.FontFamilyProperty, "DisplayFont");
                if (lyricsDoc.Synced)
                {
                    t.Cursor = Cursors.Hand;
                    t.MouseLeftButtonDown += (s, e) => { Player.Position = ln.Time; lyricsManualUntil = DateTime.MinValue; e.Handled = true; };
                }
                lyricBlocks.Add(t);
                LyricsPanel.Children.Add(t);
            }
        }

        void SetLyricsY(double y, bool animate)
        {
            lyricsY = y;
            var tt = (TranslateTransform)LyricsPanel.RenderTransform;
            if (!animate || Settings.Current.ReduceMotion) { tt.BeginAnimation(TranslateTransform.YProperty, null); tt.Y = y; return; }
            tt.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(y, Settings.Ms(480)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut } });
        }

        void LyricsTick()
        {
            if (NpLyrics.Visibility != Visibility.Visible || !lyricsDoc.Synced || lyricBlocks.Count == 0) return;
            int i = lyricsDoc.IndexAt(Player.Position + TimeSpan.FromMilliseconds(150));
            if (i == lyricIdx) return;
            if (lyricIdx >= 0 && lyricIdx < lyricBlocks.Count) Highlight(lyricBlocks[lyricIdx], false);
            lyricIdx = i;
            if (i >= 0) Highlight(lyricBlocks[i], true);
            if (DateTime.Now >= lyricsManualUntil) CenterLyric(i);
        }

        void Highlight(TextBlock t, bool on)
        {
            t.BeginAnimation(OpacityProperty, new DoubleAnimation(on ? 1 : 0.4, Settings.Ms(260)));
            var st = (ScaleTransform)t.RenderTransform;
            var a = new DoubleAnimation(on ? 1.06 : 1, Settings.Ms(320)) { EasingFunction = new BackEase { Amplitude = 0.4, EasingMode = EasingMode.EaseOut } };
            st.BeginAnimation(ScaleTransform.ScaleXProperty, a); st.BeginAnimation(ScaleTransform.ScaleYProperty, a);
        }

        void CenterLyric(int i)
        {
            if (i < 0 || i >= lyricBlocks.Count) { SetLyricsY(0, true); return; }
            var b = lyricBlocks[i];
            double y = b.TranslatePoint(new Point(0, 0), LyricsPanel).Y;
            double target = -(y + b.ActualHeight / 2 - LyricsClip.ActualHeight * 0.42);
            target = Math.Min(0, Math.Max(-(LyricsPanel.ActualHeight - LyricsClip.ActualHeight * 0.5), target));
            SetLyricsY(target, true);
        }

        void OnLyricsWheel(object sender, MouseWheelEventArgs e)
        {
            double min = -Math.Max(0, LyricsPanel.ActualHeight - LyricsClip.ActualHeight * 0.5);
            SetLyricsY(Math.Max(min, Math.Min(0, lyricsY + e.Delta * 0.5)), false);
            lyricsManualUntil = DateTime.Now.AddSeconds(4);
            e.Handled = true;
        }

        void OnLyricsToggle(object sender, RoutedEventArgs e)
        {
            bool open = NpLyrics.Visibility != Visibility.Visible;
            Settings.Current.LyricsOpen = open;
            NpLyricsBtn.Foreground = open ? (Brush)FindResource("AccentBrush") : (Brush)FindResource("NpTextBrush");
            Controls.Fx.Pop(NpLyricsBtn);
            if (open)
            {
                NpLyrics.Visibility = Visibility.Visible;
                NpLyrics.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, Settings.Ms(240)));
                var st = new ScaleTransform(0.94, 0.94); NpLyrics.RenderTransformOrigin = new Point(0.5, 0.5); NpLyrics.RenderTransform = st;
                var a = new DoubleAnimation(1, Settings.Ms(360)) { EasingFunction = new BackEase { Amplitude = 0.4, EasingMode = EasingMode.EaseOut } };
                st.BeginAnimation(ScaleTransform.ScaleXProperty, a); st.BeginAnimation(ScaleTransform.ScaleYProperty, a);
                lyricIdx = -2;
                Dispatcher.BeginInvoke(new Action(() => { lyricsManualUntil = DateTime.MinValue; LyricsTick(); }), DispatcherPriority.Loaded);
            }
            else
            {
                var a = new DoubleAnimation(0, Settings.Ms(180));
                a.Completed += (s, x) => { if (!Settings.Current.LyricsOpen) NpLyrics.Visibility = Visibility.Collapsed; };
                NpLyrics.BeginAnimation(OpacityProperty, a);
            }
            Settings.Save();
        }

        // ================================================================ player bar: Android-style title cross-fade and cover swipe
        void AnimateBar(bool forward)
        {
            if (Settings.Current.ReduceMotion) return;
            foreach (var t in new FrameworkElement[] { BarTitle, BarArtist })
                t.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, Settings.Ms(t == BarTitle ? 200 : 280)));
            var g = new TransformGroup(); var rt = new RotateTransform(); var tt = new TranslateTransform();
            g.Children.Add(rt); g.Children.Add(tt);
            BarCover.RenderTransformOrigin = new Point(0.5, 0.5); BarCover.RenderTransform = g;
            double d = forward ? 1 : -1;
            var back = new BackEase { Amplitude = 0.45, EasingMode = EasingMode.EaseOut };
            tt.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(d * 36, 0, Settings.Ms(420)) { EasingFunction = back });
            rt.BeginAnimation(RotateTransform.AngleProperty, new DoubleAnimation(d * 12, 0, Settings.Ms(420)) { EasingFunction = back });
            BarCover.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, Settings.Ms(260)));
        }
    }
}
