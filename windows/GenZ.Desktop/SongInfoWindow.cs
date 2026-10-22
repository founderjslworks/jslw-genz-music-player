using System;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using GenZ.Core;

namespace GenZ
{
    /// <summary>Song info and tag editor: title, artist, album, album artist, genre, year, track and lyrics.</summary>
    public class SongInfoWindow : Window
    {
        readonly Song song;
        readonly TextBox title, artist, album, albumArtist, genre, year, track, lyrics;
        public bool Saved { get; private set; }

        public SongInfoWindow(Window owner, Song s)
        {
            song = s;
            Owner = owner; WindowStartupLocation = WindowStartupLocation.CenterOwner;
            Title = "Song info – " + s.Title;
            Width = 720; Height = 680; MinWidth = 560; MinHeight = 520;
            SetResourceReference(BackgroundProperty, "BgBrush");
            SetResourceReference(FontFamilyProperty, "BodyFont");
            try { Icon = new System.Windows.Media.Imaging.BitmapImage(new Uri("pack://application:,,,/Assets/genz.png")); } catch { }

            var root = new Grid { Margin = new Thickness(22) };
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition());
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

            // header: cover + format info
            var head = new Grid { Margin = new Thickness(0, 0, 0, 16) };
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            head.ColumnDefinitions.Add(new ColumnDefinition());
            var cover = new Border { Width = 110, Height = 110, CornerRadius = new CornerRadius(14) };
            cover.SetResourceReference(Border.BackgroundProperty, "SurfaceBrush");
            head.Children.Add(cover);
            LoadCover(cover);
            var info = new StackPanel { Margin = new Thickness(18, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            info.Children.Add(Label(s.Title, 20, true, "TextBrush"));
            info.Children.Add(Label(s.FormatBadge + " · " + s.DurationText + " · " + Size(s.Path), 12.5, false, "AccentBrush"));
            var path = Label(s.Path, 12, false, "Text2Brush"); path.TextWrapping = TextWrapping.Wrap; path.Margin = new Thickness(0, 6, 0, 0);
            info.Children.Add(path);
            Grid.SetColumn(info, 1); head.Children.Add(info);
            root.Children.Add(head);

            // fields
            var form = new Grid();
            form.ColumnDefinitions.Add(new ColumnDefinition());
            form.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(16) });
            form.ColumnDefinitions.Add(new ColumnDefinition());
            for (int i = 0; i < 5; i++) form.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            form.RowDefinitions.Add(new RowDefinition());
            title = Field(form, "Title", s.Title, 0, 0, 3);
            artist = Field(form, "Artist", s.Artist, 1, 0, 1);
            albumArtist = Field(form, "Album artist", s.AlbumArtist, 1, 2, 1);
            album = Field(form, "Album", s.Album, 2, 0, 1);
            genre = Field(form, "Genre", s.Genre, 2, 2, 1);
            year = Field(form, "Year", s.Year > 0 ? s.Year.ToString() : "", 3, 0, 1);
            track = Field(form, "Track number", s.Track > 0 ? s.Track.ToString() : "", 3, 2, 1);
            var lyr = Label("Lyrics – plain text, or synced LRC lines like [01:23.45] Hello", 12, false, "Text2Brush");
            lyr.Margin = new Thickness(0, 10, 0, 4);
            Grid.SetRow(lyr, 4); Grid.SetColumnSpan(lyr, 3); form.Children.Add(lyr);
            lyrics = Box("");
            lyrics.AcceptsReturn = true; lyrics.TextWrapping = TextWrapping.Wrap; lyrics.VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
            lyrics.VerticalContentAlignment = VerticalAlignment.Top; lyrics.FontSize = 13;
            Grid.SetRow(lyrics, 5); Grid.SetColumnSpan(lyrics, 3); form.Children.Add(lyrics);
            try { using (var f = TagLib.File.Create(s.Path)) lyrics.Text = f.Tag.Lyrics ?? ""; } catch { }
            Grid.SetRow(form, 1); root.Children.Add(form);

            // buttons
            var bar = new Grid { Margin = new Thickness(0, 16, 0, 0) };
            var folder = new Button { Content = "Show in folder", HorizontalAlignment = HorizontalAlignment.Left };
            folder.SetResourceReference(StyleProperty, "GhostPill");
            folder.Click += (o, e) => MainWindow.ShowInFolder(s.Path);
            bar.Children.Add(folder);
            var right = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
            var cancel = new Button { Content = "Cancel", IsCancel = true, Margin = new Thickness(0, 0, 10, 0) };
            cancel.SetResourceReference(StyleProperty, "GhostPill");
            var save = new Button { Content = "Save tags" };
            save.SetResourceReference(StyleProperty, "PillButton");
            save.Click += (o, e) => Save();
            right.Children.Add(cancel); right.Children.Add(save);
            bar.Children.Add(right);
            Grid.SetRow(bar, 2); root.Children.Add(bar);
            Content = root;
        }

        static string Size(string path)
        {
            try { var b = new FileInfo(path).Length; return b > 1 << 20 ? (b / 1048576.0).ToString("0.0") + " MB" : (b / 1024) + " KB"; } catch { return ""; }
        }

        async void LoadCover(Border b)
        {
            var bmp = await Covers.LoadAsync(song, 220);
            if (bmp != null) b.Background = Covers.Brush(bmp);
        }

        static TextBlock Label(string text, double size, bool bold, string brush)
        {
            var t = new TextBlock { Text = text, FontSize = size, FontWeight = bold ? FontWeights.Bold : FontWeights.Normal, TextTrimming = TextTrimming.CharacterEllipsis };
            t.SetResourceReference(TextBlock.ForegroundProperty, brush);
            return t;
        }

        static TextBox Box(string text)
        {
            var b = new TextBox { Text = text ?? "", FontSize = 14, Padding = new Thickness(10, 7, 10, 7), BorderThickness = new Thickness(1) };
            b.SetResourceReference(Control.BackgroundProperty, "SurfaceBrush");
            b.SetResourceReference(Control.ForegroundProperty, "TextBrush");
            b.SetResourceReference(Control.BorderBrushProperty, "DividerBrush");
            b.SetResourceReference(TextBox.CaretBrushProperty, "AccentBrush");
            return b;
        }

        static TextBox Field(Grid g, string label, string value, int row, int col, int span)
        {
            var sp = new StackPanel { Margin = new Thickness(0, 0, 0, 10) };
            var l = Label(label, 12, false, "Text2Brush"); l.Margin = new Thickness(0, 0, 0, 4);
            sp.Children.Add(l);
            var b = Box(value); sp.Children.Add(b);
            Grid.SetRow(sp, row); Grid.SetColumn(sp, col); Grid.SetColumnSpan(sp, span);
            g.Children.Add(sp);
            return b;
        }

        void Save()
        {
            uint.TryParse(year.Text.Trim(), out var y);
            uint.TryParse(track.Text.Trim(), out var tr);
            try
            {
                Player.WithFileReleased(song.Path, () =>
                {
                    using (var f = TagLib.File.Create(song.Path))
                    {
                        var t = f.Tag;
                        t.Title = Clean(title.Text);
                        t.Performers = Arr(artist.Text);
                        t.AlbumArtists = Arr(albumArtist.Text);
                        t.Album = Clean(album.Text);
                        t.Genres = Arr(genre.Text);
                        t.Year = y; t.Track = tr;
                        t.Lyrics = string.IsNullOrWhiteSpace(lyrics.Text) ? null : lyrics.Text;
                        f.Save();
                    }
                });
            }
            catch (Exception ex)
            {
                Dialogs.Message(this, "Couldn't save the tags", "GEN-Z couldn't write to this file. It may be read-only or open in another program.\n\n" + ex.Message);
                return;
            }
            var fresh = Library.Read(song.Path);
            song.Title = fresh.Title; song.Artist = fresh.Artist; song.Album = fresh.Album; song.AlbumArtist = fresh.AlbumArtist;
            song.Genre = fresh.Genre; song.Year = fresh.Year; song.Track = fresh.Track;
            try { song.Modified = File.GetLastWriteTimeUtc(song.Path).Ticks; } catch { }
            Saved = true;
            DialogResult = true;
        }

        static string Clean(string s) => string.IsNullOrWhiteSpace(s) ? null : s.Trim();
        static string[] Arr(string s) => string.IsNullOrWhiteSpace(s) ? new string[0] : new[] { s.Trim() };
    }
}
