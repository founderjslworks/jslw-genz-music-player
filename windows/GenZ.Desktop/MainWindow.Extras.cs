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
using System.Windows.Threading;
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
            sparkAccent = null; SparkColors();
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
            if (lvl > 0) SparkColors();
            if (lvl > 0 && NpVolume.IsVisible) NpVolSparks.Pulse(lvl, Frac(NpVolume));
            if (BarVolumeBox.Visibility == Visibility.Visible)
            {
                Live(BarVolume, BarVolGlow, BarVolIcon);
                if (lvl > 0 && BarVolume.IsVisible) BarVolSparks.Pulse(lvl, Frac(BarVolume));
            }
        }

        /// <summary>Changing the volume always answers with a glow along the bar and a speaker pop – playing or not.</summary>
        void VolumeTouched()
        {
            if (!Settings.Current.LiveVolume || Settings.Current.ReduceMotion || Core.Perf.Light) return;
            volPulse = Math.Max(volPulse, 0.55);
            SparkColors();
            var nowB = DateTime.UtcNow;
            if ((nowB - lastBurst).TotalMilliseconds >= 80)
            {
                lastBurst = nowB;
                if (NpVolume.IsVisible) NpVolSparks.Burst(Frac(NpVolume));
                if (BarVolume.IsVisible) BarVolSparks.Burst(Frac(BarVolume));
            }
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

        DateTime lastBurst;
        static double Frac(Slider s) => (s.Value - s.Minimum) / Math.Max(0.0001, s.Maximum - s.Minimum);

        Color? sparkAccent;
        void SparkColors()
        {
            if (!(Application.Current.Resources["AccentColor"] is Color a) || sparkAccent == a) return;
            sparkAccent = a;
            var second = TryFindResource("ProgressBrush") is LinearGradientBrush lg && lg.GradientStops.Count > 1 ? lg.GradientStops[lg.GradientStops.Count - 1].Color : a;
            NpVolSparks.SetColors(a, second); BarVolSparks.SetColors(a, second);
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

        // ================================================================ JSLW Sound Engine (Sound page)
        readonly Dictionary<string, Slider> theaterSliders = new Dictionary<string, Slider>();
        bool theaterUpdating;
        Border deviceStrip, npDeviceStrip;
        DispatcherTimer deviceTimer;
        TextBlock listeningLine;

        static readonly (string id, string name, string icon)[] Outputs =
        {
            ("auto", "Auto-detect", "IcSpark"), ("headphones", "Headphones", "IcHeadphones"),
            ("speakers", "Speakers", "IcSpeakerSmall"), ("soundbar", "Soundbar mode", "IcSoundbar"),
        };

        string OutputId() { var c = Settings.Current; return string.IsNullOrEmpty(c.TheaterOutput) ? (c.TheaterHeadphones ? "headphones" : "speakers") : c.TheaterOutput; }

        TextBlock Section(string text, string icon)
        {
            var t = new TextBlock { FontSize = 11, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 18, 0, 8) };
            t.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
            var ic = new Path { Data = (Geometry)FindResource(icon), Width = 13, Height = 13, Stretch = Stretch.Uniform, Margin = new Thickness(0, 0, 7, -2) };
            ic.SetResourceReference(Shape.FillProperty, "AccentBrush");
            t.Inlines.Add(new System.Windows.Documents.InlineUIContainer(ic));
            t.Inlines.Add(new System.Windows.Documents.Run(text.ToUpperInvariant()));
            return t;
        }

        /// <summary>Premium icon tile: gradient square with a white glyph when chosen, a soft outline when not.</summary>
        Border IconTile(string icon, double size, bool on)
        {
            var b = new Border { Width = size, Height = size, CornerRadius = new CornerRadius(size * 0.28) };
            var ic = new Path { Data = (Geometry)FindResource(icon), Stretch = Stretch.Uniform, Margin = new Thickness(size * 0.24) };
            if (on) { b.SetResourceReference(Border.BackgroundProperty, "HeaderBrush"); ic.Fill = Brushes.White; }
            else
            {
                b.SetResourceReference(Border.BackgroundProperty, "AccentSoftBrush");
                b.SetResourceReference(Border.BorderBrushProperty, "DividerBrush"); b.BorderThickness = new Thickness(1);
                ic.SetResourceReference(Shape.FillProperty, "AccentBrush");
            }
            b.Child = ic;
            return b;
        }

        FrameworkElement SoundTile(string icon, string name, string tip, bool on, Action pick)
        {
            var sp = new StackPanel { Width = 92, Margin = new Thickness(0, 0, 8, 8), Cursor = Cursors.Hand, ToolTip = tip, Background = Brushes.Transparent };
            var t = IconTile(icon, 54, on); t.HorizontalAlignment = HorizontalAlignment.Center;
            sp.Children.Add(t);
            var l = new TextBlock { Text = name, FontSize = 11.5, TextAlignment = TextAlignment.Center, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 6, 0, 0), FontWeight = on ? FontWeights.Bold : FontWeights.Normal };
            l.SetResourceReference(TextBlock.ForegroundProperty, on ? "TextBrush" : "Text2Brush");
            sp.Children.Add(l);
            sp.MouseLeftButtonUp += (s, e) => pick();
            Bounce.Press(sp);
            return sp;
        }

        void BuildTheater()
        {
            var c = Settings.Current;
            if (c.TheaterCustom == null) c.TheaterCustom = Theater.Profiles.First(p => p.Id == "custom").Copy();
            TheaterPanel.Children.Clear(); theaterSliders.Clear();

            deviceStrip = MakeDeviceStrip(false);
            TheaterPanel.Children.Add(deviceStrip);
            StartDeviceTimer();

            var card = new Border { CornerRadius = new CornerRadius(18), Padding = new Thickness(20, 18, 20, 18) };
            card.SetResourceReference(Border.BackgroundProperty, "SurfaceBrush");
            var body = new StackPanel();
            card.Child = body;

            var head = new Grid();
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); head.ColumnDefinitions.Add(new ColumnDefinition());
            head.Children.Add(IconTile("IcEq", 44, true));
            var ht = new StackPanel { Margin = new Thickness(14, 0, 70, 0), VerticalAlignment = VerticalAlignment.Center };
            ht.Children.Add(new TextBlock { Text = "JSLW Sound Engine", FontSize = 18, FontWeight = FontWeights.Bold });
            var sub = new TextBlock { Text = "Surround, clear dialogue, room and steady volume – only inside GEN-Z, switched off when you close it.", FontSize = 12, Margin = new Thickness(0, 2, 0, 0), TextWrapping = TextWrapping.Wrap };
            sub.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
            ht.Children.Add(sub);
            Grid.SetColumn(ht, 1); head.Children.Add(ht);
            var sw = new CheckBox { Style = (Style)FindResource("Switch"), IsChecked = c.TheaterOn, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center, ToolTip = "JSLW Sound Engine on / off" };
            sw.Checked += (s, e) => { c.TheaterOn = true; Settings.Save(); Player.ApplyTheater(); ShowToast("JSLW Sound Engine on · " + Theater.Profiles.First(p => p.Id == c.TheaterProfile).Name); };
            sw.Unchecked += (s, e) => { c.TheaterOn = false; Settings.Save(); Player.ApplyTheater(); ShowToast("JSLW Sound Engine off"); };
            Grid.SetColumn(sw, 1); head.Children.Add(sw);
            body.Children.Add(head);

            // profile
            body.Children.Add(Section("Sound profile", "IcNote"));
            var cards = new WrapPanel();
            foreach (var p in Theater.Profiles)
            {
                var prof = p;
                cards.Children.Add(SoundTile(p.Icon, p.Name, p.Blurb, p.Id == c.TheaterProfile, () =>
                {
                    c.TheaterProfile = prof.Id; c.TheaterOn = true;
                    Settings.Save(); Player.ApplyTheater(); ShowToast("JSLW Sound Engine · " + prof.Name); BuildTheater();
                }));
            }
            body.Children.Add(cards);
            var blurb = new TextBlock { Text = (Theater.Profiles.FirstOrDefault(p => p.Id == c.TheaterProfile) ?? Theater.Profiles[0]).Blurb, FontSize = 12 };
            blurb.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
            body.Children.Add(blurb);

            // output
            body.Children.Add(Section("Tune for", "IcBtLink"));
            var outRow = new WrapPanel();
            foreach (var o in Outputs)
            {
                var id = o.id;
                bool on = OutputId() == id;
                var btn = new Button { Style = (Style)FindResource(on ? "PillButton" : "GhostPill"), Margin = new Thickness(0, 0, 8, 8), Padding = new Thickness(14, 6, 14, 6) };
                var bsp = new StackPanel { Orientation = Orientation.Horizontal };
                var bi = new Path { Data = (Geometry)FindResource(o.icon), Width = 14, Height = 14, Stretch = Stretch.Uniform, Margin = new Thickness(0, 0, 8, 0) };
                bi.SetResourceReference(Shape.FillProperty, on ? "OnAccentBrush" : "AccentBrush");
                var bt = new TextBlock { Text = o.name };
                bt.SetResourceReference(TextBlock.ForegroundProperty, on ? "OnAccentBrush" : "AccentBrush");
                bsp.Children.Add(bi); bsp.Children.Add(bt); btn.Content = bsp;
                btn.Click += (s, e) => { c.TheaterOutput = id; Settings.Save(); Player.OutputChanged(); BuildTheater(); };
                outRow.Children.Add(btn);
            }
            body.Children.Add(outRow);
            listeningLine = new TextBlock { FontSize = 12, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap };
            listeningLine.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
            body.Children.Add(listeningLine);

            // room
            body.Children.Add(Section("Room", "IcRoomHall"));
            var rooms = new WrapPanel();
            foreach (var r in Theater.Rooms)
            {
                var room = r;
                rooms.Children.Add(SoundTile(r.Icon, r.Name, r.Id == "auto" ? "Chosen by the sound profile" : null, r.Id == c.TheaterRoom, () =>
                {
                    c.TheaterRoom = room.Id;
                    if (room.Amount >= 0) c.TheaterRoomAmount = room.Amount;
                    c.TheaterOn = true; Settings.Save(); Player.ApplyTheater(); ShowToast("Room · " + room.Name); BuildTheater();
                }));
            }
            body.Children.Add(rooms);

            // fine tuning: one compact card, folded to a summary line until opened
            body.Children.Add(Section("Fine tuning", "IcRoomStudio"));
            var cur = c.TheaterProfile == "custom" ? c.TheaterCustom : Theater.Profiles.First(p => p.Id == c.TheaterProfile);
            double roomVal = c.TheaterRoom == "auto" ? cur.Room : c.TheaterRoom == "off" ? 0 : c.TheaterRoomAmount;
            var tune = new Border { CornerRadius = new CornerRadius(14), Padding = new Thickness(14, 12, 14, 12), BorderThickness = new Thickness(1) };
            tune.SetResourceReference(Border.BackgroundProperty, "BgBrush");
            tune.SetResourceReference(Border.BorderBrushProperty, "DividerBrush");
            var tb = new StackPanel(); tune.Child = tb;
            var th = new Grid { Cursor = Cursors.Hand, Background = Brushes.Transparent };
            th.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); th.ColumnDefinitions.Add(new ColumnDefinition()); th.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            th.Children.Add(IconTile("IcRoomStudio", 34, false));
            var tht = new StackPanel { Margin = new Thickness(12, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            tht.Children.Add(new TextBlock { Text = "Bass, treble, surround & more", FontSize = 14, FontWeight = FontWeights.Bold });
            var summary = new TextBlock { FontSize = 11.5, TextTrimming = TextTrimming.CharacterEllipsis };
            summary.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
            Action sum = () => summary.Text = $"Surround {(cur.Width <= 1.01 ? "off" : "+" + Math.Round((cur.Width - 1) * 100) + "%")} · Room {Math.Round(roomVal * 100)}% · Dialogue {cur.Dialogue:0.0} dB · Leveller {Math.Round(cur.Leveler * 100)}% · Bass {cur.Bass:+0.0;-0.0;0} dB · Treble {cur.Treble:+0.0;-0.0;0} dB";
            sum();
            tht.Children.Add(summary);
            Grid.SetColumn(tht, 1); th.Children.Add(tht);
            var chev = new Path { Data = (Geometry)FindResource("IcChevronDown"), Width = 14, Height = 14, Stretch = Stretch.Uniform, RenderTransformOrigin = new Point(0.5, 0.5), RenderTransform = new RotateTransform(c.TuningOpen ? 180 : 0) };
            chev.SetResourceReference(Shape.FillProperty, "Text2Brush");
            Grid.SetColumn(chev, 2); th.Children.Add(chev);
            tb.Children.Add(th);

            var grid = new Grid { Margin = new Thickness(0, 10, 0, 0), Visibility = c.TuningOpen ? Visibility.Visible : Visibility.Collapsed };
            th.MouseLeftButtonUp += (s, e) =>
            {
                c.TuningOpen = !c.TuningOpen; Settings.Save();
                grid.Visibility = c.TuningOpen ? Visibility.Visible : Visibility.Collapsed;
                ((RotateTransform)chev.RenderTransform).BeginAnimation(RotateTransform.AngleProperty, new DoubleAnimation(c.TuningOpen ? 180 : 0, TimeSpan.FromMilliseconds(220)));
                if (c.TuningOpen && !Settings.Current.ReduceMotion) grid.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(260)));
            };
            grid.ColumnDefinitions.Add(new ColumnDefinition()); grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(24) }); grid.ColumnDefinitions.Add(new ColumnDefinition());
            var specs = new (string key, string label, string icon, double min, double max, double val, Func<double, string> fmt)[]
            {
                ("width", "Surround width", "IcEqSurround", 1, 2, cur.Width, v => v <= 1.01 ? "Off" : "+" + Math.Round((v - 1) * 100) + "%"),
                ("room", "Room amount", "IcRoomHall", 0, 1, roomVal, v => Math.Round(v * 100) + "%"),
                ("dialogue", "Dialogue clarity", "IcEqVocal", 0, 8, cur.Dialogue, v => v < 0.1 ? "Off" : "+" + v.ToString("0.0") + " dB"),
                ("leveler", "Volume leveller", "IcEqLoud", 0, 1, cur.Leveler, v => v < 0.01 ? "Off" : Math.Round(v * 100) + "%"),
                ("bass", "Bass", "IcEqBass", -6, 9, cur.Bass, v => (v > 0 ? "+" : "") + v.ToString("0.0") + " dB"),
                ("treble", "Treble", "IcEqTreble", -6, 9, cur.Treble, v => (v > 0 ? "+" : "") + v.ToString("0.0") + " dB"),
            };
            for (int i = 0; i < specs.Length; i++)
            {
                var sp = specs[i];
                if (i % 2 == 0) grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                var cell = new StackPanel { Margin = new Thickness(0, 4, 0, 6) };
                var top = new Grid();
                var lab = new StackPanel { Orientation = Orientation.Horizontal };
                var li = new Path { Data = (Geometry)FindResource(sp.icon), Width = 14, Height = 14, Stretch = Stretch.Uniform, Margin = new Thickness(0, 0, 8, 0) };
                li.SetResourceReference(Shape.FillProperty, "AccentBrush");
                lab.Children.Add(li); lab.Children.Add(new TextBlock { Text = sp.label, FontSize = 12.5 });
                top.Children.Add(lab);
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
                    if (key == "room" && c.TheaterRoom != "auto")
                    {
                        // a room preset is chosen: the slider sets how much of that room you hear
                        if (c.TheaterRoom == "off" && e.NewValue > 0) c.TheaterRoom = "auto";
                        else c.TheaterRoomAmount = e.NewValue;
                        roomVal = e.NewValue;
                        if (c.TheaterRoom != "auto") { if (!c.TheaterOn) { c.TheaterOn = true; sw.IsChecked = true; } Player.ApplyTheater(); sum(); return; }
                    }
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
                        case "room": t.Room = e.NewValue; roomVal = e.NewValue; break;
                        case "dialogue": t.Dialogue = e.NewValue; break;
                        case "leveler": t.Leveler = e.NewValue; break;
                        case "bass": t.Bass = e.NewValue; break;
                        case "treble": t.Treble = e.NewValue; break;
                    }
                    cur = t;
                    if (!c.TheaterOn) { c.TheaterOn = true; sw.IsChecked = true; }
                    Player.ApplyTheater(); sum();
                };
                sl.PreviewMouseLeftButtonUp += (s, e) => { Settings.Save(); if (c.TheaterProfile == "custom") RefreshTheaterCards(cards); };
                theaterSliders[key] = sl;
                cell.Children.Add(sl);
                Grid.SetRow(cell, i / 2); Grid.SetColumn(cell, (i % 2) * 2);
                grid.Children.Add(cell);
            }
            tb.Children.Add(grid);
            body.Children.Add(tune);
            TheaterPanel.Children.Add(card);
            RefreshDevice();
        }

        void RefreshTheaterCards(WrapPanel cards)
        {
            var c = Settings.Current;
            int i = 0;
            foreach (FrameworkElement el in cards.Children)
            {
                if (!(el is StackPanel sp) || i >= Theater.Profiles.Count) break;
                var p = Theater.Profiles[i++];
                bool on = p.Id == c.TheaterProfile;
                sp.Children[0] = IconTile(p.Icon, 54, on);
                ((Border)sp.Children[0]).HorizontalAlignment = HorizontalAlignment.Center;
                if (sp.Children[1] is TextBlock l) { l.FontWeight = on ? FontWeights.Bold : FontWeights.Normal; l.SetResourceReference(TextBlock.ForegroundProperty, on ? "TextBrush" : "Text2Brush"); }
            }
        }

        // ---------------------------------------------------------------- "Playing on" strip
        Border MakeDeviceStrip(bool compact)
        {
            var b = new Border { CornerRadius = new CornerRadius(16), Padding = new Thickness(12, 9, 14, 9), Margin = compact ? new Thickness(0, 12, 0, 0) : new Thickness(0, 0, 0, 14), BorderThickness = new Thickness(1), Visibility = Visibility.Collapsed };
            b.SetResourceReference(Border.BackgroundProperty, compact ? "AccentSoftBrush" : "SurfaceBrush");
            b.SetResourceReference(Border.BorderBrushProperty, "AccentSoftBrush");
            return b;
        }

        void StartDeviceTimer()
        {
            if (deviceTimer != null) return;
            deviceTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(15) };
            deviceTimer.Tick += (s, e) => { if (IsVisible && WindowState != WindowState.Minimized) RefreshDevice(); };
            deviceTimer.Start();
        }

        string lastEndpoint;

        /// <summary>Reads the output device in the background and fills both strips (Sound page and Now Playing).</summary>
        async void RefreshDevice()
        {
            AudioDevice.Info i;
            try { i = await Task.Run(() => AudioDevice.Read()); } catch { return; }
            if (i.Endpoint != lastEndpoint) { bool first = lastEndpoint == null; lastEndpoint = i.Endpoint; if (!first) Player.OutputChanged(); }
            if (listeningLine != null)
            {
                var kind = i.Kind == AudioDevice.Kind.Soundbar ? "soundbar / home theatre" : i.Kind == AudioDevice.Kind.Headphones ? "headphones" : "speakers";
                var tuned = Player.OutputKind() == AudioDevice.Kind.Soundbar ? "soundbar" : Player.OutputKind() == AudioDevice.Kind.Headphones ? "headphones" : "speakers";
                listeningLine.Text = "Listening on: " + (i.Name ?? i.Endpoint ?? "this PC") + " (" + kind + ")" + (OutputId() == "auto" ? "" : " · tuned for " + tuned);
            }
            FillStrip(deviceStrip, i, false);
            if (npDeviceStrip == null && NpInfo != null) { npDeviceStrip = MakeDeviceStrip(true); NpInfo.Children.Add(npDeviceStrip); StartDeviceTimer(); }
            FillStrip(npDeviceStrip, i, true);
        }

        void FillStrip(Border b, AudioDevice.Info i, bool compact)
        {
            if (b == null) return;
            // only for a soundbar, Bluetooth or HDMI device – not the PC's own speakers or sound card
            if (i.Name == null) { b.Visibility = Visibility.Collapsed; return; }
            var row = new Grid();
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); row.ColumnDefinitions.Add(new ColumnDefinition());
            var dot = new Border { Width = 34, Height = 34, CornerRadius = new CornerRadius(17), VerticalAlignment = VerticalAlignment.Center };
            dot.SetResourceReference(Border.BackgroundProperty, "HeaderBrush");
            dot.Child = new Path { Data = (Geometry)FindResource(i.Kind == AudioDevice.Kind.Headphones ? "IcHeadphones" : i.Kind == AudioDevice.Kind.Soundbar ? "IcSoundbar" : "IcSpeakerSmall"), Fill = Brushes.White, Stretch = Stretch.Uniform, Margin = new Thickness(8) };
            row.Children.Add(dot);
            var col = new StackPanel { Margin = new Thickness(10, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            var title = new TextBlock { Text = "Playing on " + i.Name, FontSize = 13, FontWeight = FontWeights.Bold, TextTrimming = TextTrimming.CharacterEllipsis };
            title.SetResourceReference(TextBlock.ForegroundProperty, compact ? "NpTextBrush" : "TextBrush");
            col.Children.Add(title);
            var stats = new WrapPanel { Margin = new Thickness(0, 2, 0, 0) };
            void Stat(string icon, string text, bool warn = false)
            {
                var sp = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 12, 0) };
                var p = new Path { Data = (Geometry)FindResource(icon), Width = 12, Height = 12, Stretch = Stretch.Uniform, Margin = new Thickness(0, 0, 4, 0) };
                var t = new TextBlock { Text = text, FontSize = 11.5 };
                if (warn) { p.Fill = t.Foreground = new SolidColorBrush(Color.FromRgb(0xFF, 0x6B, 0x6B)); }
                else { p.SetResourceReference(Shape.FillProperty, "AccentBrush"); t.SetResourceReference(TextBlock.ForegroundProperty, compact ? "NpText2Brush" : "Text2Brush"); }
                sp.Children.Add(p); sp.Children.Add(t); stats.Children.Add(sp);
            }
            Stat("IcBattery", i.DeviceBattery.HasValue ? i.DeviceBattery + "%" : "Battery not reported", i.DeviceBattery.HasValue && i.DeviceBattery <= 15);
            Stat("IcBtLink", i.Hdmi ? "HDMI" : i.Kind == AudioDevice.Kind.Soundbar ? "Soundbar" : "Connected");
            if (i.PcBattery >= 0) Stat("IcBattery", "PC " + i.PcBattery + "%" + (i.Charging ? " ⚡" : ""), i.PcBattery <= 15 && !i.Charging);
            col.Children.Add(stats);
            Grid.SetColumn(col, 1); row.Children.Add(col);
            b.Child = row;
            if (b.Visibility != Visibility.Visible)
            {
                b.Visibility = Visibility.Visible;
                if (!Settings.Current.ReduceMotion) b.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(320)));
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
