using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Shapes;
using System.Windows.Threading;
using GenZ.Controls;
using GenZ.Core;

namespace GenZ
{
    /// <summary>
    /// Picture-in-picture mini player: a small always-on-top window with the cover, title, controls and progress.
    /// Drag it anywhere; double-click (or ⤢) to go back to the full window. Two looks: compact bar and square cover.
    /// </summary>
    public class MiniPlayer : Window
    {
        readonly MainWindow main;
        readonly Border card = new Border();
        readonly Border cover = new Border();
        readonly Border coverBg = new Border();
        readonly TextBlock title = new TextBlock(), artist = new TextBlock();
        readonly Button play;
        readonly Path playIcon = new Path();
        readonly SparkleBar bar = new SparkleBar { BarHeight = 3.5, Height = 16 };
        readonly Grid controls = new Grid();
        readonly DispatcherTimer timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(60) };
        Song shown;

        public MiniPlayer(MainWindow owner)
        {
            main = owner;
            WindowStyle = WindowStyle.None; AllowsTransparency = true; Background = Brushes.Transparent;
            Topmost = true; ShowInTaskbar = false; ResizeMode = ResizeMode.NoResize;
            Title = "GEN-Z Mini Player"; FontFamily = (FontFamily)Application.Current.Resources["BodyFont"];
            SizeToContent = SizeToContent.Manual;

            // ---- layout
            card.CornerRadius = new CornerRadius(20);
            card.ClipToBounds = true;
            card.Effect = new DropShadowEffect { BlurRadius = 24, ShadowDepth = 6, Opacity = 0.45, Color = Colors.Black };
            card.Margin = new Thickness(14);
            var root = new Grid();
            coverBg.CornerRadius = new CornerRadius(20);
            coverBg.Effect = new BlurEffect { Radius = 30 };
            coverBg.Margin = new Thickness(-30);
            root.Children.Add(new Border { CornerRadius = new CornerRadius(20), Background = (Brush)Application.Current.Resources["NpBrush"] });
            root.Children.Add(coverBg);
            root.Children.Add(new Border { CornerRadius = new CornerRadius(20), Background = new LinearGradientBrush(Color.FromArgb(90, 0, 0, 0), Color.FromArgb(200, 0, 0, 0), 90) });
            var content = new Grid { Margin = new Thickness(12) };
            root.Children.Add(content);
            card.Child = root;
            Content = card;

            cover.CornerRadius = new CornerRadius(14);
            cover.Background = new SolidColorBrush(Color.FromArgb(60, 255, 255, 255));
            title.FontSize = 14; title.FontWeight = FontWeights.Bold; title.Foreground = Brushes.White;
            artist.FontSize = 12; artist.Foreground = new SolidColorBrush(Color.FromArgb(210, 255, 255, 255)); artist.Margin = new Thickness(0, 2, 0, 0);

            Button Icon(string key, double size, RoutedEventHandler click, string tip)
            {
                var b = new Button { Style = (Style)Application.Current.Resources["IconButton"], Tag = Application.Current.Resources[key], Foreground = Brushes.White, Width = size, Height = size, ToolTip = tip };
                b.Click += click; return b;
            }
            play = new Button { Width = 44, Height = 44, Cursor = Cursors.Hand, Focusable = false, RenderTransformOrigin = new Point(0.5, 0.5), RenderTransform = new ScaleTransform() };
            play.Template = (ControlTemplate)System.Windows.Markup.XamlReader.Parse(
                "<ControlTemplate TargetType='Button' xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation'>" +
                "<Grid><Ellipse Fill='White'/><ContentPresenter HorizontalAlignment='Center' VerticalAlignment='Center'/></Grid></ControlTemplate>");
            playIcon.Fill = (Brush)Application.Current.Resources["AccentBrush"]; playIcon.Width = 18; playIcon.Height = 18; playIcon.Stretch = Stretch.Uniform;
            playIcon.Data = (Geometry)Application.Current.Resources["IcPlay"];
            play.Content = playIcon;
            play.Click += (s, e) => Player.Toggle();

            var prev = Icon("IcPrev", 36, (s, e) => Player.Prev(), "Previous");
            var next = Icon("IcNext", 36, (s, e) => Player.Next(), "Next");
            var mode = Icon("IcPanel", 28, (s, e) => { Settings.Current.PipMode = Settings.Current.PipMode == "cover" ? "compact" : "cover"; Layout(content); }, "Switch look");
            var expand = Icon("IcExpand", 28, (s, e) => main.ExitPip(), "Back to full window");
            var close = Icon("IcClose", 28, (s, e) => main.ExitPip(), "Close mini player");

            var row = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
            row.Children.Add(prev); play.Margin = new Thickness(8, 0, 8, 0); row.Children.Add(play); row.Children.Add(next);
            controls.Children.Add(row);
            var corner = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top };
            corner.Children.Add(mode); corner.Children.Add(expand); corner.Children.Add(close);
            cornerButtons = corner;

            bar.Seek += f => Player.Position = TimeSpan.FromMilliseconds(f * Player.Duration.TotalMilliseconds);
            Layout(content);

            // ---- behaviour
            MouseLeftButtonDown += (s, e) => { if (e.ClickCount == 2) main.ExitPip(); else try { DragMove(); } catch { } };
            LocationChanged += (s, e) => { Settings.Current.PipLeft = Left; Settings.Current.PipTop = Top; };
            Player.TrackChanged += Refresh;
            Player.StateChanged += Refresh;
            Closed += (s, e) => { Player.TrackChanged -= Refresh; Player.StateChanged -= Refresh; timer.Stop(); };
            timer.Tick += (s, e) =>
            {
                var d = Player.Duration.TotalMilliseconds;
                bar.Value = d > 0 ? Player.Position.TotalMilliseconds / d : 0;
            };
            timer.Start();
            KeyDown += (s, e) => { if (e.Key == Key.Space) Player.Toggle(); else if (e.Key == Key.Escape) main.ExitPip(); };
            Refresh();

            // pop in
            Opacity = 0;
            Loaded += (s, e) =>
            {
                BeginAnimation(OpacityProperty, new DoubleAnimation(1, Settings.Ms(220)));
                var st = new ScaleTransform(0.85, 0.85); card.RenderTransformOrigin = new Point(0.5, 0.5); card.RenderTransform = st;
                var a = new DoubleAnimation(1, Settings.Ms(380)) { EasingFunction = new BackEase { Amplitude = 0.4, EasingMode = EasingMode.EaseOut } };
                st.BeginAnimation(ScaleTransform.ScaleXProperty, a); st.BeginAnimation(ScaleTransform.ScaleYProperty, a);
            };
        }

        readonly StackPanel cornerButtons;

        void Layout(Grid content)
        {
            content.Children.Clear(); content.RowDefinitions.Clear(); content.ColumnDefinitions.Clear();
            foreach (var el in new FrameworkElement[] { cover, title, artist, controls, bar, cornerButtons })
                (el.Parent as Panel)?.Children.Remove(el);
            var work = SystemParameters.WorkArea;
            if (Settings.Current.PipMode == "cover")
            {
                Width = 300; Height = 340;
                content.RowDefinitions.Add(new RowDefinition());
                content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                cover.Margin = new Thickness(28, 26, 28, 8);
                content.Children.Add(cover);
                var texts = new StackPanel { Margin = new Thickness(6, 4, 6, 0) };
                title.HorizontalAlignment = HorizontalAlignment.Center; artist.HorizontalAlignment = HorizontalAlignment.Center;
                texts.Children.Add(title); texts.Children.Add(artist);
                Grid.SetRow(texts, 1); content.Children.Add(texts);
                Grid.SetRow(bar, 2); bar.Margin = new Thickness(10, 4, 10, 0); content.Children.Add(bar);
                Grid.SetRow(controls, 3); controls.Margin = new Thickness(0, 2, 0, 0); content.Children.Add(controls);
            }
            else
            {
                Width = 420; Height = 150;
                content.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(96) });
                content.ColumnDefinitions.Add(new ColumnDefinition());
                cover.Margin = new Thickness(0);
                content.Children.Add(cover);
                var right = new Grid { Margin = new Thickness(14, 0, 0, 0) };
                right.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                right.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                right.RowDefinitions.Add(new RowDefinition());
                var texts = new StackPanel { Margin = new Thickness(0, 2, 84, 0) };
                title.HorizontalAlignment = HorizontalAlignment.Left; artist.HorizontalAlignment = HorizontalAlignment.Left;
                texts.Children.Add(title); texts.Children.Add(artist);
                right.Children.Add(texts);
                Grid.SetRow(bar, 1); bar.Margin = new Thickness(0, 4, 0, 0); right.Children.Add(bar);
                Grid.SetRow(controls, 2); right.Children.Add(controls);
                Grid.SetColumn(right, 1); content.Children.Add(right);
            }
            Grid.SetColumnSpan(cornerButtons, 2); Grid.SetRowSpan(cornerButtons, 4);
            cornerButtons.Margin = new Thickness(0, -6, -6, 0);
            content.Children.Add(cornerButtons);
            if (Settings.Current.PipLeft < 0 || Settings.Current.PipLeft > work.Right - 60 || Settings.Current.PipTop > work.Bottom - 60)
            { Left = work.Right - Width - 16; Top = work.Bottom - Height - 16; }
            else { Left = Settings.Current.PipLeft; Top = Settings.Current.PipTop; }
            ApplyCover();
        }

        System.Windows.Media.Imaging.BitmapSource art;

        async void Refresh()
        {
            var s = Player.Current;
            title.Text = s?.Title ?? "GEN-Z Music";
            artist.Text = s?.Artist ?? "Nothing playing";
            var playing = Player.IsPlaying;
            var geo = (Geometry)Application.Current.Resources[playing ? "IcPause" : "IcPlay"];
            if (playIcon.Data != geo)
            {
                playIcon.Data = geo;
                var st = (ScaleTransform)play.RenderTransform;
                var a = new DoubleAnimation(1.18, 1, Settings.Ms(420)) { EasingFunction = new ElasticEase { Oscillations = 1, Springiness = 5 } };
                st.BeginAnimation(ScaleTransform.ScaleXProperty, a); st.BeginAnimation(ScaleTransform.ScaleYProperty, a);
            }
            bar.Playing = playing && Settings.Current.Sparkles;
            if (s != shown)
            {
                shown = s;
                art = s == null ? null : await Covers.LoadAsync(s, 640);
                if (s == shown) ApplyCover();
            }
        }

        void ApplyCover()
        {
            if (art == null)
            {
                cover.Background = new SolidColorBrush(Color.FromArgb(60, 255, 255, 255));
                cover.Child = new Path { Data = (Geometry)Application.Current.Resources["IcNote"], Fill = Brushes.White, Width = 34, Height = 34, Stretch = Stretch.Uniform, Opacity = 0.8 };
                coverBg.Background = null;
                return;
            }
            cover.Child = null;
            cover.Background = new ImageBrush(art) { Stretch = Stretch.UniformToFill };
            coverBg.Background = new ImageBrush(art) { Stretch = Stretch.UniformToFill };
        }
    }
}
