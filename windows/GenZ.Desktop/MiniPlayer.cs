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
            Title = "GEN-Z Mini Player"; SetResourceReference(FontFamilyProperty, "BodyFont");
            SizeToContent = SizeToContent.Height;

            // ---- layout
            card.CornerRadius = new CornerRadius(20);
            card.ClipToBounds = true;
            card.Effect = new DropShadowEffect { BlurRadius = 24, ShadowDepth = 6, Opacity = 0.45, Color = Colors.Black };
            card.Margin = new Thickness(14);
            var root = new Grid();
            // clip everything (including the blurred cover backdrop) to the rounded card
            root.SizeChanged += (s, e) => root.Clip = new RectangleGeometry(new Rect(0, 0, root.ActualWidth, root.ActualHeight), 20, 20);
            coverBg.CornerRadius = new CornerRadius(20);
            coverBg.Effect = new BlurEffect { Radius = 30 };
            coverBg.Margin = new Thickness(-30);
            var npBg = new Border { CornerRadius = new CornerRadius(20) };
            npBg.SetResourceReference(Border.BackgroundProperty, "NpBrush");
            root.Children.Add(npBg);
            root.Children.Add(coverBg);
            root.Children.Add(new Border { CornerRadius = new CornerRadius(20), Background = new LinearGradientBrush(Color.FromArgb(90, 0, 0, 0), Color.FromArgb(200, 0, 0, 0), 90) });
            var content = new Grid { Margin = new Thickness(12) };
            // crisp text on the transparent window
            RenderOptions.SetClearTypeHint(content, ClearTypeHint.Enabled);
            TextOptions.SetTextFormattingMode(this, TextFormattingMode.Display);
            // the cover glows and gently pulses with the music
            cover.RenderTransformOrigin = new Point(0.5, 0.5);
            var cg = new TransformGroup(); cg.Children.Add(coverScale); cg.Children.Add(flipScale);
            cover.RenderTransform = cg;
            cover.Effect = coverGlow;
            root.Children.Add(content);
            card.Child = root;
            Content = card;

            cover.CornerRadius = new CornerRadius(14);
            cover.Background = new SolidColorBrush(Color.FromArgb(60, 255, 255, 255));
            title.FontSize = 14; title.FontWeight = FontWeights.Bold; title.Foreground = Brushes.White;
            title.TextTrimming = TextTrimming.CharacterEllipsis; artist.TextTrimming = TextTrimming.CharacterEllipsis;
            // same theme fonts as the main window: heading font for the title, body font for the artist
            title.SetResourceReference(TextBlock.FontFamilyProperty, "DisplayFont");
            artist.SetResourceReference(TextBlock.FontFamilyProperty, "BodyFont");
            foreach (var t in new[] { title, artist }) t.Effect = new DropShadowEffect { BlurRadius = 6, ShadowDepth = 1, Opacity = 0.55, Color = Colors.Black };
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
            playIcon.SetResourceReference(Shape.FillProperty, "AccentBrush"); playIcon.Width = 18; playIcon.Height = 18; playIcon.Stretch = Stretch.Uniform;
            playIcon.Data = (Geometry)Application.Current.Resources["IcPlay"];
            play.Content = playIcon;
            play.Click += (s, e) => { Fx.Pop(play); Player.Toggle(); };

            Button prev = null, next = null;
            prev = Icon("IcPrev", 36, (s, e) => { Fx.Pop(prev, -1); Player.Prev(); }, "Previous");
            next = Icon("IcNext", 36, (s, e) => { Fx.Pop(next, 1); Player.Next(); }, "Next");
            // small, rounded corner buttons in a frosted pill
            Button Mini(string key, RoutedEventHandler click, string tip, bool danger)
            {
                var b = new Button { Width = 24, Height = 24, Cursor = Cursors.Hand, Focusable = false, ToolTip = tip, Tag = Application.Current.Resources[key] };
                b.Template = (ControlTemplate)System.Windows.Markup.XamlReader.Parse(
                    "<ControlTemplate TargetType='Button' xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation'>" +
                    "<Border x:Name='bg' CornerRadius='12' Background='Transparent' xmlns:x='http://schemas.microsoft.com/winfx/2006/xaml'>" +
                    "<Path x:Name='ic' Data='{TemplateBinding Tag}' Fill='#F2FFFFFF' Width='11' Height='11' Stretch='Uniform' RenderTransformOrigin='0.5,0.5'>" +
                    "<Path.RenderTransform><ScaleTransform/></Path.RenderTransform></Path></Border>" +
                    "<ControlTemplate.Triggers>" +
                    "<Trigger Property='IsMouseOver' Value='True'><Setter TargetName='bg' Property='Background' Value='" + (danger ? "#E81123" : "#40FFFFFF") + "'/></Trigger>" +
                    "<Trigger Property='IsPressed' Value='True'><Setter TargetName='ic' Property='RenderTransform'><Setter.Value><ScaleTransform ScaleX='0.82' ScaleY='0.82'/></Setter.Value></Setter></Trigger>" +
                    "</ControlTemplate.Triggers></ControlTemplate>");
                b.Click += click;
                b.Margin = new Thickness(1, 0, 1, 0);
                return b;
            }
            var mode = Mini("IcPanel", (s, e) => { Settings.Current.PipMode = Settings.Current.PipMode == "cover" ? "compact" : "cover"; Layout(content); }, "Switch look", false);
            var expand = Mini("IcExpand", (s, e) => main.ExitPip(), "Back to full window", false);
            var close = Mini("IcClose", (s, e) => main.ExitPip(), "Close mini player", true);

            var row = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
            row.Children.Add(prev); play.Margin = new Thickness(8, 0, 8, 0); row.Children.Add(play); row.Children.Add(next);
            controls.Children.Add(row);
            var corner = new StackPanel { Orientation = Orientation.Horizontal };
            corner.Children.Add(mode); corner.Children.Add(expand); corner.Children.Add(close);
            cornerButtons = new Border
            {
                Child = corner, CornerRadius = new CornerRadius(14), Padding = new Thickness(3), HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top,
                Background = new SolidColorBrush(Color.FromArgb(70, 0, 0, 0)), BorderBrush = new SolidColorBrush(Color.FromArgb(50, 255, 255, 255)), BorderThickness = new Thickness(1)
            };

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
                Pulse();
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

        readonly Border cornerButtons;

        void Layout(Grid content)
        {
            content.Children.Clear(); content.RowDefinitions.Clear(); content.ColumnDefinitions.Clear();
            foreach (var el in new FrameworkElement[] { cover, title, artist, controls, bar, cornerButtons })
                (el.Parent as Panel)?.Children.Remove(el);
            var work = SystemParameters.WorkArea;
            if (Settings.Current.PipMode == "cover")
            {
                Width = 300;
                cover.Width = cover.Height = 214;
                content.RowDefinitions.Add(new RowDefinition());
                content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                cover.Margin = new Thickness(0, 22, 0, 8);
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
                Width = 440;
                cover.Width = cover.Height = 96;
                content.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(96) });
                content.ColumnDefinitions.Add(new ColumnDefinition());
                cover.Margin = new Thickness(0);
                cover.VerticalAlignment = VerticalAlignment.Center;
                content.Children.Add(cover);
                var right = new Grid { Margin = new Thickness(14, 0, 0, 0) };
                right.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                right.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                right.RowDefinitions.Add(new RowDefinition());
                var texts = new StackPanel { Margin = new Thickness(0, 2, 92, 0) };
                title.HorizontalAlignment = HorizontalAlignment.Left; artist.HorizontalAlignment = HorizontalAlignment.Left;
                texts.Children.Add(title); texts.Children.Add(artist);
                right.Children.Add(texts);
                Grid.SetRow(bar, 1); bar.Margin = new Thickness(0, 4, 0, 0); right.Children.Add(bar);
                Grid.SetRow(controls, 2); controls.Margin = new Thickness(0, 4, 0, 0); right.Children.Add(controls);
                Grid.SetColumn(right, 1); content.Children.Add(right);
            }
            Grid.SetColumnSpan(cornerButtons, 2); Grid.SetRowSpan(cornerButtons, 4);
            cornerButtons.Margin = new Thickness(0, -4, -4, 0);
            content.Children.Add(cornerButtons);
            if (Settings.Current.PipLeft < 0 || Settings.Current.PipLeft > work.Right - 60 || Settings.Current.PipTop > work.Bottom - 60)
            {
                Left = work.Right - Width - 16; Top = work.Bottom - 200;
                Dispatcher.BeginInvoke(new Action(() => { if (ActualHeight > 0) Top = work.Bottom - ActualHeight - 16; }), System.Windows.Threading.DispatcherPriority.Loaded);
            }
            else { Left = Settings.Current.PipLeft; Top = Math.Min(Settings.Current.PipTop, work.Bottom - 120); }
            ApplyCover();
        }

        System.Windows.Media.Imaging.BitmapSource art;
        readonly ScaleTransform coverScale = new ScaleTransform(1, 1);
        readonly ScaleTransform flipScale = new ScaleTransform(1, 1);
        readonly DropShadowEffect coverGlow = new DropShadowEffect { BlurRadius = 14, ShadowDepth = 0, Opacity = 0.0, Color = Colors.White };
        double pulse;
        ScaleTransform kenScale;
        TranslateTransform kenMove;

        /// <summary>Cover pulse and glow follow the live music level.</summary>
        void Pulse()
        {
            double lvl = Player.IsPlaying && !Settings.Current.ReduceMotion ? Player.Level : 0;
            pulse = Math.Max(lvl, pulse * 0.82);
            double s = 1 + pulse * 0.045;
            coverScale.ScaleX = coverScale.ScaleY = s;
            coverGlow.Opacity = 0.15 + pulse * 0.75;
            coverGlow.BlurRadius = 10 + pulse * 26;
            if (Application.Current.Resources["AccentColor"] is Color c) coverGlow.Color = c;
        }

        /// <summary>Slow "Ken Burns" pan and zoom inside the cover while music plays.</summary>
        void KenBurns(bool playing)
        {
            if (kenScale == null) return;
            if (!playing || Settings.Current.ReduceMotion || Core.Perf.Light)
            {
                kenScale.BeginAnimation(ScaleTransform.ScaleXProperty, null); kenScale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
                kenMove.BeginAnimation(TranslateTransform.XProperty, null); kenMove.BeginAnimation(TranslateTransform.YProperty, null);
                return;
            }
            var z = new DoubleAnimation(1.0, 1.16, TimeSpan.FromSeconds(11)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut } };
            kenScale.BeginAnimation(ScaleTransform.ScaleXProperty, z); kenScale.BeginAnimation(ScaleTransform.ScaleYProperty, z);
            kenMove.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(-0.05, 0.05, TimeSpan.FromSeconds(13)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut } });
            kenMove.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(0.04, -0.04, TimeSpan.FromSeconds(17)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut } });
        }

        /// <summary>The new cover flips in like a turned card.</summary>
        void FlipIn()
        {
            if (Settings.Current.ReduceMotion) { ApplyCover(); return; }
            var flip = flipScale;
            var close = new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(150)) { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseIn } };
            close.Completed += (o, e) =>
            {
                ApplyCover();
                flip.BeginAnimation(ScaleTransform.ScaleXProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(380)) { EasingFunction = new BackEase { Amplitude = 0.4, EasingMode = EasingMode.EaseOut } });
            };
            flip.BeginAnimation(ScaleTransform.ScaleXProperty, close);
        }

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
                Fx.Pop(play);
            }
            bar.Playing = playing && Settings.Current.Sparkles && !Core.Perf.Light;
            if (playing != wasPlaying) { wasPlaying = playing; KenBurns(playing); }
            if (s != shown)
            {
                bool first = shown == null;
                shown = s;
                art = s == null ? null : await Covers.LoadAsync(s, 640);
                if (s == shown) { if (first) ApplyCover(); else FlipIn(); }
            }
        }

        bool wasPlaying;

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
            var brush = Covers.Brush(art);
            kenScale = new ScaleTransform(1, 1, 0.5, 0.5); kenMove = new TranslateTransform();
            var kg = new TransformGroup(); kg.Children.Add(kenScale); kg.Children.Add(kenMove);
            brush.RelativeTransform = kg;
            cover.Background = brush;
            coverBg.Background = Covers.Brush(art);
            KenBurns(Player.IsPlaying);
        }
    }
}
