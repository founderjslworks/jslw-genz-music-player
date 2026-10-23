using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Media.Media3D;
using System.Windows.Shapes;
using GenZ.Core;

namespace GenZ
{
    /// <summary>
    /// Album Flow – the classic album carousel from GEN-Z on Android, in real 3D: tilted covers with mirror
    /// reflections, scroll / drag / arrow keys to browse, a "slide to browse albums" bar, and a click on the centre
    /// cover flips it over to show (and play) its songs. Hiding the Now Playing panel brings it to the centre.
    /// </summary>
    public partial class MainWindow
    {
        class FlowItem
        {
            public GeometryModel3D Cover, Refl;
            public DiffuseMaterial CoverMat, ReflMat;
            public AxisAngleRotation3D Rot;
            public ScaleTransform3D Scale;
            public TranslateTransform3D Move;
            public bool Loading;
        }

        Grid pageFlow;
        Viewport3D flowVp;
        PerspectiveCamera flowCam;
        Model3DGroup flowGroup;
        TextBlock flowTitle, flowArtist, flowSub, flowNowText, flowHint;
        Border flowFlip, flowKnob, flowTrack, flowNow;
        StackPanel flowTracks;
        TextBlock flowFlipTitle, flowFlipSub;
        List<AlbumInfo> flowAlbums = new List<AlbumInfo>();
        readonly Dictionary<int, FlowItem> flowItems = new Dictionary<int, FlowItem>();
        double flowPos, flowTarget, flowFlipAngle, flowFlipGoal;
        int flowCenter = -1;
        bool flowHooked, flowDragging, flowFlipped, flowKnobDrag;
        Point flowDown; double flowDownPos, flowVel; DateTime flowLastMove;
        TimeSpan flowLast;
        Color flowBg;
        static int flowRemember;
        static readonly MeshGeometry3D FlowQuad = MakeQuad(0, 1, false), FlowReflQuad = MakeQuad(-0.42, 0, true);

        static MeshGeometry3D MakeQuad(double y0, double y1, bool refl)
        {
            var m = new MeshGeometry3D();
            m.Positions = new Point3DCollection { new Point3D(-0.5, y0, 0), new Point3D(0.5, y0, 0), new Point3D(0.5, y1, 0), new Point3D(-0.5, y1, 0) };
            // the reflection bitmap is already flipped: its top row belongs at the cover's bottom edge
            m.TextureCoordinates = refl
                ? new PointCollection { new Point(0, 1), new Point(1, 1), new Point(1, 0), new Point(0, 0) }
                : new PointCollection { new Point(0, 1), new Point(1, 1), new Point(1, 0), new Point(0, 0) };
            m.TriangleIndices = new Int32Collection { 0, 1, 2, 0, 2, 3 };
            m.Freeze();
            return m;
        }

        // ================================================================ building the page
        void EnsureFlow()
        {
            if (pageFlow != null) return;
            pageFlow = new Grid { Visibility = Visibility.Collapsed, ClipToBounds = true };
            pageFlow.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            pageFlow.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            pageFlow.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            pageFlow.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            pageFlow.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

            // header
            var head = new Grid { Margin = new Thickness(28, 22, 28, 4) };
            head.ColumnDefinitions.Add(new ColumnDefinition { MinWidth = 90 });
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var titles = new StackPanel { Margin = new Thickness(0, 0, 14, 0) };
            var ht = new TextBlock { Text = "Album Flow", FontSize = 30, FontWeight = FontWeights.Bold, TextTrimming = TextTrimming.CharacterEllipsis };
            ht.SetResourceReference(TextBlock.FontFamilyProperty, "DisplayFont");
            titles.Children.Add(ht);
            flowSub = new TextBlock { FontSize = 13, Margin = new Thickness(0, 2, 0, 0), TextTrimming = TextTrimming.CharacterEllipsis };
            flowSub.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
            titles.Children.Add(flowSub);
            head.Children.Add(titles);
            var tools = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            var play = PillWithIcon("Play album", "IcPlay", true);
            play.Click += (s, e) => { var a = FlowAlbum(); if (a != null) Player.PlayList(a.Songs, 0); };
            var shuf = PillWithIcon("Shuffle", "IcShuffle", false);
            shuf.Margin = new Thickness(10, 0, 0, 0);
            shuf.Click += (s, e) => { var a = FlowAlbum(); if (a != null) { var r = new Random(); Player.PlayList(a.Songs.OrderBy(_ => r.Next()).ToList(), 0); } };
            tools.Children.Add(play); tools.Children.Add(shuf);
            Grid.SetColumn(tools, 1); head.Children.Add(tools);
            pageFlow.Children.Add(head);

            // the 3D carousel
            flowCam = new PerspectiveCamera { Position = new Point3D(0, 0.36, 2.3), LookDirection = new Vector3D(0, 0, -1), UpDirection = new Vector3D(0, 1, 0), FieldOfView = 45 };
            flowGroup = new Model3DGroup();
            flowGroup.Children.Add(new AmbientLight(Colors.White));
            flowVp = new Viewport3D { Camera = flowCam, ClipToBounds = true, Cursor = Cursors.Hand, Focusable = true };
            flowVp.Children.Add(new ModelVisual3D { Content = flowGroup });
            var vpHost = new Grid { Background = Brushes.Transparent };
            vpHost.Children.Add(flowVp);
            vpHost.SizeChanged += (s, e) => { FitFlowCamera(); FlowUpdate(); };
            vpHost.MouseWheel += (s, e) => { if (flowFlipped) return; FlowGo(flowTarget - Math.Sign(e.Delta)); e.Handled = true; };
            vpHost.MouseLeftButtonDown += FlowDown;
            vpHost.MouseMove += FlowMove;
            vpHost.MouseLeftButtonUp += FlowUp;
            vpHost.LostMouseCapture += (s, e) => { if (flowDragging) { flowDragging = false; FlowGo(Math.Round(flowPos)); } };
            flowHint = new TextBlock { Text = "No albums yet – add a music folder to fill Album Flow", FontSize = 14, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Visibility = Visibility.Collapsed };
            flowHint.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
            vpHost.Children.Add(flowHint);
            vpHost.Children.Add(BuildFlowFlip());
            Grid.SetRow(vpHost, 1); pageFlow.Children.Add(vpHost);

            // caption
            var cap = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(28, 0, 28, 10) };
            flowTitle = new TextBlock { FontSize = 22, FontWeight = FontWeights.Bold, TextAlignment = TextAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis, MaxWidth = 640 };
            flowArtist = new TextBlock { FontSize = 14, TextAlignment = TextAlignment.Center, Margin = new Thickness(0, 3, 0, 0), TextTrimming = TextTrimming.CharacterEllipsis, MaxWidth = 640 };
            flowArtist.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
            cap.Children.Add(flowTitle); cap.Children.Add(flowArtist);
            Grid.SetRow(cap, 2); pageFlow.Children.Add(cap);

            // slide to browse albums
            Grid.SetRow(BuildFlowScrubber(), 3); pageFlow.Children.Add(flowTrack);

            // what's playing now – click to bring its album to the centre
            flowNow = new Border { CornerRadius = new CornerRadius(16), Padding = new Thickness(14, 7, 16, 7), Margin = new Thickness(0, 12, 0, 18), HorizontalAlignment = HorizontalAlignment.Center, Cursor = Cursors.Hand, ToolTip = "Show the playing album" };
            flowNow.SetResourceReference(Border.BackgroundProperty, "AccentSoftBrush");
            var nowRow = new StackPanel { Orientation = Orientation.Horizontal };
            var eq = new Path { Data = (Geometry)FindResource("IcNote"), Width = 13, Height = 13, Stretch = Stretch.Uniform, Margin = new Thickness(0, 0, 8, 0), VerticalAlignment = VerticalAlignment.Center };
            eq.SetResourceReference(Shape.FillProperty, "AccentBrush");
            flowNowText = new TextBlock { FontSize = 13, VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis, MaxWidth = 520 };
            nowRow.Children.Add(eq); nowRow.Children.Add(flowNowText);
            flowNow.Child = nowRow;
            flowNow.MouseLeftButtonUp += (s, e) => FlowShowPlaying(false);
            Controls.Bounce.Press(flowNow);
            Grid.SetRow(flowNow, 4); pageFlow.Children.Add(flowNow);

            pageFlow.IsVisibleChanged += (s, e) => FlowHook();
            Pages.Children.Add(pageFlow);
            Player.TrackChanged += () => { if (pageFlow.IsVisible) FlowNowPlaying(); };
        }

        Button PillWithIcon(string text, string icon, bool solid)
        {
            var b = new Button { Style = (Style)FindResource(solid ? "PillButton" : "GhostPill") };
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            var p = new Path { Data = (Geometry)FindResource(icon), Width = 12, Height = 12, Stretch = Stretch.Uniform, Margin = new Thickness(0, 0, 8, 0) };
            p.SetResourceReference(Shape.FillProperty, solid ? "OnAccentBrush" : "AccentBrush");
            var t = new TextBlock { Text = text };
            t.SetResourceReference(TextBlock.ForegroundProperty, solid ? "OnAccentBrush" : "AccentBrush");
            sp.Children.Add(p); sp.Children.Add(t);
            b.Content = sp;
            return b;
        }

        /// <summary>The back of a flipped cover: the album's songs.</summary>
        FrameworkElement BuildFlowFlip()
        {
            flowFlip = new Border { CornerRadius = new CornerRadius(18), Padding = new Thickness(18, 16, 18, 12), Visibility = Visibility.Collapsed, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, RenderTransformOrigin = new Point(0.5, 0.5), RenderTransform = new ScaleTransform(1, 1) };
            flowFlip.SetResourceReference(Border.BackgroundProperty, "SurfaceBrush");
            flowFlip.SetResourceReference(Border.BorderBrushProperty, "DividerBrush");
            flowFlip.BorderThickness = new Thickness(1);
            var dock = new DockPanel();
            var top = new Grid { Margin = new Thickness(0, 0, 0, 10) };
            top.ColumnDefinitions.Add(new ColumnDefinition());
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var tt = new StackPanel { Margin = new Thickness(0, 0, 10, 0) };
            flowFlipTitle = new TextBlock { FontSize = 18, FontWeight = FontWeights.Bold, TextTrimming = TextTrimming.CharacterEllipsis };
            flowFlipSub = new TextBlock { FontSize = 12.5, Margin = new Thickness(0, 2, 0, 0), TextTrimming = TextTrimming.CharacterEllipsis };
            flowFlipSub.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
            tt.Children.Add(flowFlipTitle); tt.Children.Add(flowFlipSub);
            top.Children.Add(tt);
            var play = new Button { Style = (Style)FindResource("IconButton"), Tag = FindResource("IcPlay"), ToolTip = "Play album", VerticalAlignment = VerticalAlignment.Center };
            play.Click += (s, e) => { var a = FlowAlbum(); if (a != null) Player.PlayList(a.Songs, 0); };
            Grid.SetColumn(play, 1); top.Children.Add(play);
            var close = new Button { Style = (Style)FindResource("IconButton"), Tag = FindResource("IcClose"), ToolTip = "Back to the covers (Esc)", VerticalAlignment = VerticalAlignment.Center };
            close.Click += (s, e) => FlowFlip(false);
            Grid.SetColumn(close, 2); top.Children.Add(close);
            DockPanel.SetDock(top, Dock.Top); dock.Children.Add(top);
            flowTracks = new StackPanel();
            dock.Children.Add(new ScrollViewer { Content = flowTracks, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
            flowFlip.Child = dock;
            return flowFlip;
        }

        /// <summary>"slide to browse albums" – a knob you drag along a rounded track, with shimmering text.</summary>
        FrameworkElement BuildFlowScrubber()
        {
            flowTrack = new Border { Height = 52, CornerRadius = new CornerRadius(26), Margin = new Thickness(28, 4, 28, 0), MaxWidth = 560, Padding = new Thickness(6), BorderThickness = new Thickness(1) };
            flowTrack.SetResourceReference(Border.BackgroundProperty, "SurfaceBrush");
            flowTrack.SetResourceReference(Border.BorderBrushProperty, "DividerBrush");
            var g = new Grid();
            var text = new TextBlock { Text = "slide to browse albums", FontSize = 19, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(90, 0, 10, 0), IsHitTestVisible = false };
            text.Loaded += (s, e) => FlowShimmer(text);
            g.Children.Add(text);
            var canvas = new Canvas { Background = Brushes.Transparent, ClipToBounds = true };
            flowKnob = new Border { Width = 92, Height = 38, CornerRadius = new CornerRadius(19), Cursor = Cursors.SizeWE };
            flowKnob.SetResourceReference(Border.BackgroundProperty, "AccentBrush");
            var arrow = new Path { Data = Geometry.Parse("M4,11 H16 M11,5 L17,11 L11,17"), Width = 22, Height = 20, Stretch = Stretch.Uniform, StrokeThickness = 2.6, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, StrokeLineJoin = PenLineJoin.Round, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            arrow.SetResourceReference(Shape.StrokeProperty, "OnAccentBrush");
            flowKnob.Child = arrow;
            canvas.Children.Add(flowKnob);
            g.Children.Add(canvas);
            flowTrack.Child = g;
            double KnobRange() => Math.Max(1, canvas.ActualWidth - flowKnob.Width);
            void Seek(Point p)
            {
                if (flowAlbums.Count < 2) return;
                double f = Math.Max(0, Math.Min(1, (p.X - flowKnob.Width / 2) / KnobRange()));
                double idx = f * (flowAlbums.Count - 1);
                flowTarget = Math.Round(idx); flowPos = flowPos + (idx - flowPos) * 0.6;
                if (flowFlipped) FlowFlip(false);
            }
            canvas.MouseLeftButtonDown += (s, e) => { flowKnobDrag = true; canvas.CaptureMouse(); Seek(e.GetPosition(canvas)); e.Handled = true; };
            canvas.MouseMove += (s, e) => { if (flowKnobDrag) Seek(e.GetPosition(canvas)); };
            canvas.MouseLeftButtonUp += (s, e) => { flowKnobDrag = false; canvas.ReleaseMouseCapture(); };
            canvas.LostMouseCapture += (s, e) => flowKnobDrag = false;
            canvas.SizeChanged += (s, e) => FlowKnob();
            return flowTrack;
        }

        void FlowShimmer(TextBlock t)
        {
            var c = ((SolidColorBrush)FindResource("Text2Brush")).Color;
            var hi = ((SolidColorBrush)FindResource("TextBrush")).Color;
            var br = new LinearGradientBrush { StartPoint = new Point(0, 0.5), EndPoint = new Point(1, 0.5) };
            br.GradientStops.Add(new GradientStop(Color.FromArgb(150, c.R, c.G, c.B), 0));
            br.GradientStops.Add(new GradientStop(Color.FromArgb(150, c.R, c.G, c.B), 0.38));
            br.GradientStops.Add(new GradientStop(hi, 0.5));
            br.GradientStops.Add(new GradientStop(Color.FromArgb(150, c.R, c.G, c.B), 0.62));
            br.GradientStops.Add(new GradientStop(Color.FromArgb(150, c.R, c.G, c.B), 1));
            var tr = new TranslateTransform();
            br.RelativeTransform = tr;
            t.Foreground = br;
            if (Settings.Current.ReduceMotion || Perf.Light) return;
            tr.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(-1, 1, TimeSpan.FromSeconds(2.4)) { RepeatBehavior = RepeatBehavior.Forever });
        }

        // ================================================================ showing it
        void ShowFlow()
        {
            EnsureFlow();
            var bg = FindResource("BgBrush") is SolidColorBrush sb ? sb.Color : Colors.Black;
            var albums = Library.Albums.ToList();
            bool changed = albums.Count != flowAlbums.Count || albums.Where((a, i) => a.Key != flowAlbums[i].Key).Any() || bg != flowBg;
            if (changed)
            {
                foreach (var it in flowItems.Values) { flowGroup.Children.Remove(it.Cover); flowGroup.Children.Remove(it.Refl); }
                flowItems.Clear();
                flowAlbums = albums; flowBg = bg;
                flowPos = flowTarget = Math.Max(0, Math.Min(flowRemember, flowAlbums.Count - 1));
                flowCenter = -1;
            }
            flowSub.Text = flowAlbums.Count == 0 ? "Your albums as a 3D carousel" : $"{flowAlbums.Count} albums · scroll, drag or use ← → · click the centre cover to see its songs";
            flowHint.Visibility = flowAlbums.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            flowTrack.Visibility = flowAlbums.Count > 1 ? Visibility.Visible : Visibility.Hidden;
            if (flowFlipped) { flowFlipped = false; flowFlip.Visibility = Visibility.Collapsed; flowFlipAngle = flowFlipGoal = 0; }
            ShowPage(pageFlow);
            FlowNowPlaying();
            FitFlowCamera();
            FlowUpdate();
            FlowHook();
            flowVp.Focus();
        }

        AlbumInfo FlowAlbum() => flowAlbums.Count == 0 ? null : flowAlbums[(int)Math.Max(0, Math.Min(flowAlbums.Count - 1, Math.Round(flowTarget)))];

        int FlowPlayingIndex()
        {
            var s = Player.Current;
            if (s == null) return -1;
            return flowAlbums.FindIndex(a => a.Songs.Contains(s));
        }

        /// <summary>Brings the album that is playing to the centre (instantly when the page has just opened).</summary>
        void FlowShowPlaying(bool jump)
        {
            int i = FlowPlayingIndex();
            if (i < 0) return;
            if (flowFlipped) FlowFlip(false);
            if (jump) flowPos = i;
            FlowGo(i);
        }

        void FlowNowPlaying()
        {
            var s = Player.Current;
            flowNow.Visibility = s == null ? Visibility.Collapsed : Visibility.Visible;
            if (s != null) flowNowText.Text = "Now playing · " + s.Title + (string.IsNullOrEmpty(s.Artist) ? "" : " – " + s.Artist);
        }

        void FlowGo(double target)
        {
            if (flowAlbums.Count == 0) return;
            flowTarget = Math.Max(0, Math.Min(flowAlbums.Count - 1, Math.Round(target)));
            FlowHook();
        }

        void FitFlowCamera()
        {
            if (flowVp == null || flowVp.ActualHeight < 10) return;
            double aspect = flowVp.ActualWidth / flowVp.ActualHeight, k = 2 * Math.Tan(22.5 * Math.PI / 180);
            // the centre cover and its reflection fill most of the height, and at least three covers fit across
            // WPF's FieldOfView is horizontal: the visible width is dist·k, the height is that ÷ aspect
            double dist = Math.Max(1.84 * aspect, 3.3) / k;
            flowCam.Position = new Point3D(0, 0.29, dist);
            FlowKnob();
        }

        void FlowHook()
        {
            bool want = pageFlow != null && pageFlow.IsVisible;
            if (want && !flowHooked) { flowHooked = true; flowLast = TimeSpan.Zero; CompositionTarget.Rendering += FlowFrame; }
            else if (!want && flowHooked) { flowHooked = false; CompositionTarget.Rendering -= FlowFrame; }
        }

        void FlowFrame(object sender, EventArgs e)
        {
            var now = ((RenderingEventArgs)e).RenderingTime;
            double dt = flowLast == TimeSpan.Zero ? 1 / 60.0 : Math.Min(0.05, (now - flowLast).TotalSeconds);
            if (dt <= 0) return;
            flowLast = now;
            bool moving = false;
            if (!flowDragging)
            {
                double k = 1 - Math.Exp(-dt * (Settings.Current.ReduceMotion ? 60 : 9));
                double d = flowTarget - flowPos;
                if (Math.Abs(d) > 0.0005) { flowPos += d * k; moving = true; } else flowPos = flowTarget;
            }
            if (Math.Abs(flowFlipGoal - flowFlipAngle) > 0.01)
            {
                double step = 90 / 0.17 * dt;
                flowFlipAngle = flowFlipGoal > flowFlipAngle ? Math.Min(flowFlipGoal, flowFlipAngle + step) : Math.Max(flowFlipGoal, flowFlipAngle - step);
                moving = true;
                if (flowFlipAngle >= 90 && flowFlipped) FlowShowTracks();
            }
            if (moving || flowDragging) FlowUpdate();
            else if (!flowKnobDrag && flowFlipAngle == flowFlipGoal) { /* idle: nothing to redraw */ }
        }

        /// <summary>Places every visible cover: tilt, scale, overlap and dimming by distance from the centre.</summary>
        void FlowUpdate()
        {
            if (flowAlbums.Count == 0) { flowTitle.Text = ""; flowArtist.Text = ""; return; }
            int lo = Math.Max(0, (int)Math.Floor(flowPos) - 7), hi = Math.Min(flowAlbums.Count - 1, (int)Math.Ceiling(flowPos) + 7);
            foreach (var key in flowItems.Keys.Where(k => k < lo || k > hi).ToList())
            {
                var it = flowItems[key]; flowGroup.Children.Remove(it.Cover); flowGroup.Children.Remove(it.Refl); flowItems.Remove(key);
            }
            int center = (int)Math.Round(flowPos);
            for (int i = lo; i <= hi; i++)
            {
                if (!flowItems.TryGetValue(i, out var it)) it = FlowMake(i);
                double d = i - flowPos, ad = Math.Abs(d), cl = Math.Max(-1, Math.Min(1, d));
                double x = Math.Sign(d) * (ad <= 1 ? ad * 0.62 : 0.62 + (ad - 1) * 0.3);
                double z = -Math.Min(ad, 1) * 0.55 - Math.Max(0, ad - 1) * 0.02;
                double angle = cl * 62;
                if (i == center && flowFlipAngle > 0) angle -= flowFlipAngle;
                it.Rot.Angle = angle;
                it.Move.OffsetX = x; it.Move.OffsetZ = z;
                byte lum = (byte)(255 * Math.Max(0.35, 1 - 0.32 * Math.Min(ad, 1) - 0.1 * Math.Max(0, ad - 1)));
                var c = Color.FromRgb(lum, lum, lum);
                if (it.CoverMat.AmbientColor != c) { it.CoverMat.AmbientColor = c; it.ReflMat.AmbientColor = c; }
                bool hide = i == center && flowFlipAngle >= 89.9;
                var want = hide ? 0.0 : 1.0;
                if (it.Scale.ScaleX != want) { it.Scale.ScaleX = want; it.Scale.ScaleY = want; it.Scale.ScaleZ = want; }
            }
            if (center != flowCenter && center >= 0 && center < flowAlbums.Count)
            {
                flowCenter = center; flowRemember = center;
                var a = flowAlbums[center];
                flowTitle.Text = a.Title;
                flowArtist.Text = string.Join(" · ", new[] { a.Artist, a.Year > 0 ? a.Year.ToString() : null, a.Songs.Count + (a.Songs.Count == 1 ? " song" : " songs") }.Where(t => !string.IsNullOrEmpty(t)));
                if (!Settings.Current.ReduceMotion)
                {
                    flowTitle.BeginAnimation(OpacityProperty, new DoubleAnimation(0.3, 1, TimeSpan.FromMilliseconds(180)));
                    flowArtist.BeginAnimation(OpacityProperty, new DoubleAnimation(0.3, 1, TimeSpan.FromMilliseconds(240)));
                }
            }
            FlowKnob();
        }

        void FlowKnob()
        {
            if (flowKnob == null || !(flowKnob.Parent is Canvas cv)) return;
            double f = flowAlbums.Count > 1 ? flowPos / (flowAlbums.Count - 1) : 0;
            Canvas.SetLeft(flowKnob, Math.Max(0, cv.ActualWidth - flowKnob.Width) * Math.Max(0, Math.Min(1, f)));
            Canvas.SetTop(flowKnob, Math.Max(0, (cv.ActualHeight - flowKnob.Height) / 2));
        }

        FlowItem FlowMake(int i)
        {
            var it = new FlowItem { Rot = new AxisAngleRotation3D(new Vector3D(0, 1, 0), 0), Scale = new ScaleTransform3D(1, 1, 1), Move = new TranslateTransform3D() };
            var tg = new Transform3DGroup();
            tg.Children.Add(it.Scale);
            tg.Children.Add(new RotateTransform3D(it.Rot));
            tg.Children.Add(it.Move);
            var placeholder = new ImageBrush(FlowPlaceholder(flowAlbums[i]));
            it.CoverMat = new DiffuseMaterial(placeholder);
            it.ReflMat = new DiffuseMaterial(new SolidColorBrush(flowBg));
            it.Cover = new GeometryModel3D(FlowQuad, it.CoverMat) { BackMaterial = it.CoverMat, Transform = tg };
            it.Refl = new GeometryModel3D(FlowReflQuad, it.ReflMat) { BackMaterial = it.ReflMat, Transform = tg };
            flowGroup.Children.Add(it.Cover); flowGroup.Children.Add(it.Refl);
            flowItems[i] = it;
            FlowLoadCover(i, it);
            return it;
        }

        async void FlowLoadCover(int i, FlowItem it)
        {
            var a = flowAlbums[i];
            if (a.First == null) { it.ReflMat.Brush = new ImageBrush(FlowReflection(((ImageBrush)it.CoverMat.Brush).ImageSource as BitmapSource)); return; }
            it.Loading = true;
            int px = (int)Math.Max(Perf.Light ? 320 : 512, Math.Min(Perf.Light ? 400 : 1024, flowVp.ActualHeight * 0.7));
            BitmapSource img = null;
            try { img = await Covers.LoadAsync(a.First, px); } catch { }
            it.Loading = false;
            if (!flowItems.TryGetValue(i, out var still) || still != it) return;
            var src = img ?? ((ImageBrush)it.CoverMat.Brush).ImageSource as BitmapSource;
            if (img != null)
            {
                var b = new ImageBrush(img); RenderOptions.SetBitmapScalingMode(b, Perf.Scaling); b.Freeze();
                it.CoverMat.Brush = b;
            }
            var r = FlowReflection(src);
            if (r != null) { var rb = new ImageBrush(r); rb.Freeze(); it.ReflMat.Brush = rb; }
        }

        /// <summary>A mirror image of the cover's lower part, fading into the page background (opaque, so 3D sorting stays simple).</summary>
        BitmapSource FlowReflection(BitmapSource img)
        {
            if (img == null) return null;
            const int w = 256, h = 108;
            var dv = new DrawingVisual();
            using (var dc = dv.RenderOpen())
            {
                dc.DrawRectangle(new SolidColorBrush(flowBg), null, new Rect(0, 0, w, h));
                var mask = new LinearGradientBrush(Color.FromArgb(120, 0, 0, 0), Color.FromArgb(0, 0, 0, 0), new Point(0, 0), new Point(0, h)) { MappingMode = BrushMappingMode.Absolute };
                dc.PushOpacityMask(mask);
                dc.PushTransform(new MatrixTransform(1, 0, 0, -1, 0, w));
                dc.DrawImage(img, new Rect(0, 0, w, w));
                dc.Pop(); dc.Pop();
            }
            var rtb = new RenderTargetBitmap(w, h, 96, 96, PixelFormats.Pbgra32);
            rtb.Render(dv); rtb.Freeze();
            return rtb;
        }

        /// <summary>For albums without art: the theme's colours with the album's initial.</summary>
        BitmapSource FlowPlaceholder(AlbumInfo a)
        {
            const int s = 256;
            var dv = new DrawingVisual();
            using (var dc = dv.RenderOpen())
            {
                var accent = Application.Current.Resources["AccentColor"] is Color ac ? ac : Colors.HotPink;
                var g = new LinearGradientBrush(accent, Color.FromRgb((byte)(accent.R / 3), (byte)(accent.G / 3), (byte)(accent.B / 3)), 45);
                dc.DrawRectangle(g, null, new Rect(0, 0, s, s));
                string ch = string.IsNullOrWhiteSpace(a.Title) ? "♪" : a.Title.Trim().Substring(0, 1).ToUpperInvariant();
                var ft = new FormattedText(ch, System.Globalization.CultureInfo.CurrentUICulture, FlowDirection.LeftToRight,
                    new Typeface((FontFamily)FindResource("DisplayFont"), FontStyles.Normal, FontWeights.Bold, FontStretches.Normal), 120, Brushes.White, 1.0);
                dc.DrawText(ft, new Point((s - ft.Width) / 2, (s - ft.Height) / 2));
            }
            var rtb = new RenderTargetBitmap(s, s, 96, 96, PixelFormats.Pbgra32);
            rtb.Render(dv); rtb.Freeze();
            return rtb;
        }

        // ================================================================ mouse, keys and the flip
        void FlowDown(object sender, MouseButtonEventArgs e)
        {
            if (flowFlipped || flowAlbums.Count == 0) return;
            flowVp.Focus();
            flowDown = e.GetPosition(flowVp); flowDownPos = flowPos; flowVel = 0; flowLastMove = DateTime.UtcNow;
            flowDragging = false;
            ((UIElement)sender).CaptureMouse();
        }

        void FlowMove(object sender, MouseEventArgs e)
        {
            if (e.LeftButton != MouseButtonState.Pressed || !((UIElement)sender).IsMouseCaptured) return;
            var p = e.GetPosition(flowVp);
            double dx = p.X - flowDown.X;
            if (!flowDragging && Math.Abs(dx) < 6) return;
            flowDragging = true;
            double per = Math.Max(60, flowVp.ActualHeight * 0.3);
            double np = Math.Max(-0.4, Math.Min(flowAlbums.Count - 0.6, flowDownPos - dx / per));
            var now = DateTime.UtcNow; double sec = Math.Max(0.001, (now - flowLastMove).TotalSeconds);
            flowVel = flowVel * 0.6 + (np - flowPos) / sec * 0.4; flowLastMove = now;
            flowPos = np; flowTarget = Math.Round(np);
        }

        void FlowUp(object sender, MouseButtonEventArgs e)
        {
            var el = (UIElement)sender;
            bool was = flowDragging;
            flowDragging = false;
            if (el.IsMouseCaptured) el.ReleaseMouseCapture();
            if (was) { FlowGo(flowPos + Math.Max(-6, Math.Min(6, flowVel * 0.18))); return; }
            // a click: the centre cover flips, a side cover comes to the centre
            int hit = FlowHit(e.GetPosition(flowVp));
            if (hit < 0) return;
            if (hit == (int)Math.Round(flowPos) && Math.Abs(flowTarget - flowPos) < 0.05)
            {
                if (e.ClickCount >= 2) { var a = flowAlbums[hit]; Player.PlayList(a.Songs, 0); }
                else FlowFlip(true);
            }
            else FlowGo(hit);
        }

        int FlowHit(Point p)
        {
            int found = -1;
            VisualTreeHelper.HitTest(flowVp, null, r =>
            {
                if (r is RayMeshGeometry3DHitTestResult m)
                    foreach (var kv in flowItems)
                        if (kv.Value.Cover == m.ModelHit || kv.Value.Refl == m.ModelHit) { found = kv.Key; return HitTestResultBehavior.Stop; }
                return HitTestResultBehavior.Continue;
            }, new PointHitTestParameters(p));
            return found;
        }

        void FlowFlip(bool open)
        {
            var a = FlowAlbum();
            if (a == null) return;
            if (open)
            {
                flowFlipped = true; flowFlipGoal = 90;
                if (Settings.Current.ReduceMotion) { flowFlipAngle = 90; FlowUpdate(); FlowShowTracks(); }
                FlowHook();
            }
            else
            {
                if (!flowFlipped) return;
                flowFlipped = false;
                var st = (ScaleTransform)flowFlip.RenderTransform;
                var anim = new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(Settings.Current.ReduceMotion ? 1 : 170)) { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseIn } };
                anim.Completed += (s, e) => { flowFlip.Visibility = Visibility.Collapsed; st.BeginAnimation(ScaleTransform.ScaleXProperty, null); st.ScaleX = 1; flowFlipGoal = 0; FlowHook(); };
                st.BeginAnimation(ScaleTransform.ScaleXProperty, anim);
            }
        }

        void FlowShowTracks()
        {
            var a = FlowAlbum();
            if (a == null || flowFlip.Visibility == Visibility.Visible) return;
            flowFlipTitle.Text = a.Title;
            flowFlipSub.Text = (string.IsNullOrEmpty(a.Artist) ? "" : a.Artist + " · ") + a.Songs.Count + (a.Songs.Count == 1 ? " song" : " songs");
            flowTracks.Children.Clear();
            for (int i = 0; i < a.Songs.Count; i++)
            {
                var s = a.Songs[i]; int idx = i;
                bool playing = s == Player.Current;
                var row = new Border { CornerRadius = new CornerRadius(10), Padding = new Thickness(10, 7, 10, 7), Cursor = Cursors.Hand, Background = Brushes.Transparent };
                var g = new Grid();
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(34) });
                g.ColumnDefinitions.Add(new ColumnDefinition());
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var no = new TextBlock { Text = s.Track > 0 ? (s.Track % 1000).ToString() : (i + 1).ToString(), FontSize = 13, VerticalAlignment = VerticalAlignment.Center };
                no.SetResourceReference(TextBlock.ForegroundProperty, playing ? "AccentBrush" : "Text2Brush");
                var t = new TextBlock { Text = s.Title, FontSize = 14, FontWeight = playing ? FontWeights.SemiBold : FontWeights.Normal, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
                if (playing) t.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
                var len = new TextBlock { Text = s.DurationText, FontSize = 12.5, Margin = new Thickness(10, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
                len.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
                g.Children.Add(no); Grid.SetColumn(t, 1); g.Children.Add(t); Grid.SetColumn(len, 2); g.Children.Add(len);
                row.Child = g;
                row.MouseEnter += (o, e) => row.SetResourceReference(Border.BackgroundProperty, "HoverBrush");
                row.MouseLeave += (o, e) => row.Background = Brushes.Transparent;
                row.MouseLeftButtonUp += (o, e) => Player.PlayList(a.Songs, idx);
                row.MouseRightButtonUp += (o, e) => { SongsMenu(new List<Song> { s }, "list").IsOpen = true; e.Handled = true; };
                flowTracks.Children.Add(row);
            }
            double h = Math.Max(200, flowVp.ActualHeight * 0.66);
            flowFlip.Width = Math.Min(Math.Max(320, h * 1.3), flowVp.ActualWidth - 32);
            flowFlip.MaxHeight = Math.Max(220, flowVp.ActualHeight - 16);
            flowFlip.Height = Math.Min(flowFlip.MaxHeight, Math.Max(220, h * 1.08));
            flowFlip.Visibility = Visibility.Visible;
            var st = (ScaleTransform)flowFlip.RenderTransform;
            st.BeginAnimation(ScaleTransform.ScaleXProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(Settings.Current.ReduceMotion ? 1 : 240)) { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut } });
        }

        /// <summary>Keys on the Album Flow page: ← → Home End browse, Enter flips (Ctrl+Enter plays), Esc flips back.</summary>
        bool FlowKeys(KeyEventArgs e)
        {
            if (view != View.Flow || pageFlow == null || !pageFlow.IsVisible || SearchBox.IsKeyboardFocusWithin) return false;
            if ((Keyboard.Modifiers & (ModifierKeys.Control | ModifierKeys.Alt)) != 0 && e.Key != Key.Enter) return false;
            switch (e.Key)
            {
                case Key.Left: if (!flowFlipped) FlowGo(flowTarget - 1); return true;
                case Key.Right: if (!flowFlipped) FlowGo(flowTarget + 1); return true;
                case Key.Home: if (!flowFlipped) FlowGo(0); return true;
                case Key.End: if (!flowFlipped) FlowGo(flowAlbums.Count - 1); return true;
                case Key.Enter:
                    if ((Keyboard.Modifiers & ModifierKeys.Control) != 0) { var a = FlowAlbum(); if (a != null) Player.PlayList(a.Songs, 0); }
                    else FlowFlip(!flowFlipped);
                    return true;
                case Key.Escape: if (flowFlipped) { FlowFlip(false); return true; } return false;
            }
            return false;
        }
    }
}
