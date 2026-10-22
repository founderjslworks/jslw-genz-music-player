using System.Windows;
using System.Windows.Controls;
using GenZ.Core;

namespace GenZ.Controls
{
    /// <summary>Attached property: a Border shows the cover of the bound song, loaded in the background (works with recycled rows).</summary>
    public static class CoverLoader
    {
        public static readonly DependencyProperty SongProperty = DependencyProperty.RegisterAttached(
            "Song", typeof(Song), typeof(CoverLoader), new PropertyMetadata(null, OnSong));

        public static Song GetSong(DependencyObject d) => (Song)d.GetValue(SongProperty);
        public static void SetSong(DependencyObject d, Song v) => d.SetValue(SongProperty, v);

        static async void OnSong(DependencyObject d, DependencyPropertyChangedEventArgs e)
        {
            if (!(d is Border b)) return;
            b.SetResourceReference(Border.BackgroundProperty, "SurfaceBrush");
            var s = e.NewValue as Song;
            if (s == null) return;
            int size = (int)System.Math.Max(48, b.Width > 0 ? b.Width : 48);
            var bmp = await Covers.LoadAsync(s, size);
            if (bmp != null && GetSong(b) == s) b.Background = Covers.Brush(bmp);
        }
    }
}
