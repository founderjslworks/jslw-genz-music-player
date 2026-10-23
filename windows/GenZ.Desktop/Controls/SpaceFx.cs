using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using GenZ.Core;

namespace GenZ.Controls
{
    /// <summary>Space-themed click effects (stars, galaxy, comets, sparkle ring) and a drifting star field.</summary>
    public static class SpaceFx
    {
        public static readonly (string id, string label)[] Effects =
            { ("stars", "Shooting stars"), ("galaxy", "Galaxy swirl"), ("comets", "Comets"), ("ring", "Sparkle ring"), ("none", "None") };

        static readonly Random rnd = new Random();
        static readonly Geometry Star = Geometry.Parse("M0,-1 L0.22,-0.22 L1,0 L0.22,0.22 L0,1 L-0.22,0.22 L-1,0 L-0.22,-0.22 Z");

        static Brush Accent => (Brush)Application.Current.Resources["AccentBrush"];
        static Color AccentColor => Application.Current.Resources["AccentColor"] is Color c ? c : Colors.HotPink;

        /// <summary>Plays the chosen effect at point <paramref name="p"/> on the canvas.</summary>
        public static void Play(Canvas layer, Point p, string effect)
        {
            if (layer == null || Settings.Current.ReduceMotion) return;
            switch (effect)
            {
                case "galaxy": Galaxy(layer, p); break;
                case "comets": Comets(layer, p); break;
                case "ring": Ring(layer, p); break;
                case "none": break;
                default: Stars(layer, p); break;
            }
        }

        static void Remove(Canvas layer, UIElement e, TimeSpan after)
        {
            var t = new System.Windows.Threading.DispatcherTimer { Interval = after + TimeSpan.FromMilliseconds(50) };
            t.Tick += (s, a) => { t.Stop(); layer.Children.Remove(e); };
            t.Start();
        }

        static Path MakeStar(double size, Brush fill)
        {
            var path = new Path { Data = Star, Fill = fill, Width = size, Height = size, Stretch = Stretch.Uniform, IsHitTestVisible = false };
            path.Effect = new System.Windows.Media.Effects.DropShadowEffect { Color = Colors.White, BlurRadius = size, ShadowDepth = 0, Opacity = 0.8 };
            return path;
        }

        /// <summary>Twinkling four-point stars burst out, spin and fade.</summary>
        static void Stars(Canvas layer, Point p)
        {
            for (int i = 0; i < 14; i++)
            {
                double size = 6 + rnd.NextDouble() * 12;
                var s = MakeStar(size, i % 3 == 0 ? Brushes.White : Accent);
                Canvas.SetLeft(s, p.X - size / 2); Canvas.SetTop(s, p.Y - size / 2);
                var tt = new TranslateTransform(); var rt = new RotateTransform(0, size / 2, size / 2); var st = new ScaleTransform(0.2, 0.2, size / 2, size / 2);
                var g = new TransformGroup(); g.Children.Add(st); g.Children.Add(rt); g.Children.Add(tt);
                s.RenderTransform = g;
                layer.Children.Add(s);
                double ang = rnd.NextDouble() * Math.PI * 2, dist = 30 + rnd.NextDouble() * 70;
                var dur = TimeSpan.FromMilliseconds(700 + rnd.Next(500));
                var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
                tt.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(Math.Cos(ang) * dist, dur) { EasingFunction = ease });
                tt.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(Math.Sin(ang) * dist + 14, dur) { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseIn } });
                rt.BeginAnimation(RotateTransform.AngleProperty, new DoubleAnimation(0, rnd.Next(2) == 0 ? 220 : -220, dur));
                var pop = new DoubleAnimationUsingKeyFrames { Duration = dur };
                pop.KeyFrames.Add(new EasingDoubleKeyFrame(1.3, KeyTime.FromPercent(0.25)));
                pop.KeyFrames.Add(new EasingDoubleKeyFrame(0.3, KeyTime.FromPercent(1)));
                st.BeginAnimation(ScaleTransform.ScaleXProperty, pop); st.BeginAnimation(ScaleTransform.ScaleYProperty, pop);
                var tw = new DoubleAnimationUsingKeyFrames { Duration = dur };
                tw.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromPercent(0.1)));
                tw.KeyFrames.Add(new LinearDoubleKeyFrame(0.4, KeyTime.FromPercent(0.4)));
                tw.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromPercent(0.6)));
                tw.KeyFrames.Add(new LinearDoubleKeyFrame(0, KeyTime.FromPercent(1)));
                s.BeginAnimation(UIElement.OpacityProperty, tw);
                Remove(layer, s, dur);
            }
        }

        /// <summary>A little spiral galaxy forms, turns and dissolves around the click.</summary>
        static void Galaxy(Canvas layer, Point p)
        {
            const double R = 70;
            var host = new Canvas { Width = R * 2, Height = R * 2, IsHitTestVisible = false };
            Canvas.SetLeft(host, p.X - R); Canvas.SetTop(host, p.Y - R);
            var core = new Ellipse { Width = R * 0.9, Height = R * 0.9 };
            var accent = AccentColor;
            core.Fill = new RadialGradientBrush(new GradientStopCollection
            {
                new GradientStop(Colors.White, 0), new GradientStop(Color.FromArgb(200, accent.R, accent.G, accent.B), 0.35), new GradientStop(Color.FromArgb(0, accent.R, accent.G, accent.B), 1)
            });
            Canvas.SetLeft(core, R - R * 0.45); Canvas.SetTop(core, R - R * 0.45);
            host.Children.Add(core);
            for (int arm = 0; arm < 2; arm++)
                for (int i = 0; i < 22; i++)
                {
                    double t = i / 22.0, th = arm * Math.PI + t * Math.PI * 2.4, r = 8 + t * (R - 10);
                    double size = 1.5 + (1 - t) * 3.5 + rnd.NextDouble() * 1.5;
                    var dot = new Ellipse { Width = size, Height = size, Fill = i % 4 == 0 ? Brushes.White : (Brush)new SolidColorBrush(Color.FromArgb((byte)(255 - t * 120), accent.R, accent.G, accent.B)) };
                    Canvas.SetLeft(dot, R + Math.Cos(th) * r - size / 2 + (rnd.NextDouble() - 0.5) * 4);
                    Canvas.SetTop(dot, R + Math.Sin(th) * r * 0.62 - size / 2 + (rnd.NextDouble() - 0.5) * 4);
                    host.Children.Add(dot);
                }
            var rt = new RotateTransform(0, R, R); var st = new ScaleTransform(0.1, 0.1, R, R);
            var g = new TransformGroup(); g.Children.Add(st); g.Children.Add(rt);
            host.RenderTransform = g;
            layer.Children.Add(host);
            var dur = TimeSpan.FromMilliseconds(1300);
            rt.BeginAnimation(RotateTransform.AngleProperty, new DoubleAnimation(-40, 260, dur) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
            var grow = new DoubleAnimation(0.1, 1.25, dur) { EasingFunction = new BackEase { Amplitude = 0.3, EasingMode = EasingMode.EaseOut } };
            st.BeginAnimation(ScaleTransform.ScaleXProperty, grow); st.BeginAnimation(ScaleTransform.ScaleYProperty, grow);
            var fade = new DoubleAnimationUsingKeyFrames { Duration = dur };
            fade.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromPercent(0.15)));
            fade.KeyFrames.Add(new LinearDoubleKeyFrame(0.9, KeyTime.FromPercent(0.55)));
            fade.KeyFrames.Add(new LinearDoubleKeyFrame(0, KeyTime.FromPercent(1)));
            host.BeginAnimation(UIElement.OpacityProperty, fade);
            Remove(layer, host, dur);
        }

        /// <summary>Comets with glowing tails shoot away from the click.</summary>
        static void Comets(Canvas layer, Point p)
        {
            var accent = AccentColor;
            for (int i = 0; i < 6; i++)
            {
                double ang = (i / 6.0) * 360 + rnd.Next(-20, 20);
                double len = 34 + rnd.Next(26);
                var tail = new Rectangle
                {
                    Width = len, Height = 3, RadiusX = 1.5, RadiusY = 1.5, IsHitTestVisible = false,
                    Fill = new LinearGradientBrush(Color.FromArgb(0, accent.R, accent.G, accent.B), Colors.White, 0)
                };
                tail.Effect = new System.Windows.Media.Effects.DropShadowEffect { Color = accent, BlurRadius = 10, ShadowDepth = 0, Opacity = 1 };
                Canvas.SetLeft(tail, p.X - len); Canvas.SetTop(tail, p.Y - 1.5);
                var tt = new TranslateTransform();
                var g = new TransformGroup(); g.Children.Add(tt); g.Children.Add(new RotateTransform(ang, len, 1.5));
                tail.RenderTransform = g;
                layer.Children.Add(tail);
                var dur = TimeSpan.FromMilliseconds(650 + rnd.Next(250));
                tt.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(0, 70 + rnd.Next(40), dur) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
                var fade = new DoubleAnimationUsingKeyFrames { Duration = dur };
                fade.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromPercent(0.1)));
                fade.KeyFrames.Add(new LinearDoubleKeyFrame(0, KeyTime.FromPercent(1)));
                tail.BeginAnimation(UIElement.OpacityProperty, fade);
                Remove(layer, tail, dur);
            }
            Stars(layer, p);
        }

        /// <summary>A ring of sparkles expands from the click.</summary>
        static void Ring(Canvas layer, Point p)
        {
            for (int i = 0; i < 16; i++)
            {
                double ang = i / 16.0 * Math.PI * 2;
                var s = MakeStar(i % 2 == 0 ? 10 : 6, i % 2 == 0 ? Accent : Brushes.White);
                Canvas.SetLeft(s, p.X - s.Width / 2); Canvas.SetTop(s, p.Y - s.Height / 2);
                var tt = new TranslateTransform(); s.RenderTransform = tt;
                layer.Children.Add(s);
                var dur = TimeSpan.FromMilliseconds(800);
                tt.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(Math.Cos(ang) * 56, dur) { EasingFunction = new BackEase { Amplitude = 0.4, EasingMode = EasingMode.EaseOut } });
                tt.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(Math.Sin(ang) * 56, dur) { EasingFunction = new BackEase { Amplitude = 0.4, EasingMode = EasingMode.EaseOut } });
                s.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(1, 0, dur) { BeginTime = TimeSpan.FromMilliseconds(150) });
                Remove(layer, s, dur + TimeSpan.FromMilliseconds(150));
            }
        }

        /// <summary>Slowly drifting, twinkling stars (the canvas sits behind the sidebar items).</summary>
        public static void StarField(Canvas field, bool on)
        {
            field.Children.Clear();
            if (!on || Settings.Current.ReduceMotion) return;
            void Fill()
            {
                field.Children.Clear();
                double w = Math.Max(200, field.ActualWidth), h = Math.Max(400, field.ActualHeight);
                for (int i = 0; i < 46; i++)
                {
                    bool big = i % 9 == 0;
                    double size = big ? 7 + rnd.NextDouble() * 4 : 1.2 + rnd.NextDouble() * 2;
                    FrameworkElement star = big ? (FrameworkElement)MakeStar(size, Accent) : new Ellipse { Width = size, Height = size, Fill = i % 3 == 0 ? Accent : Brushes.White };
                    star.IsHitTestVisible = false;
                    star.Opacity = 0.15 + rnd.NextDouble() * 0.4;
                    double x = rnd.NextDouble() * w, y = rnd.NextDouble() * h;
                    Canvas.SetLeft(star, x); Canvas.SetTop(star, y);
                    var tt = new TranslateTransform(); star.RenderTransform = tt;
                    field.Children.Add(star);
                    // drift up and a little sideways, forever
                    var drift = TimeSpan.FromSeconds(30 + rnd.Next(40));
                    tt.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(0, -h - 20, drift) { RepeatBehavior = RepeatBehavior.Forever, BeginTime = TimeSpan.FromSeconds(-rnd.NextDouble() * drift.TotalSeconds) });
                    tt.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(-8, 8, TimeSpan.FromSeconds(6 + rnd.Next(6))) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever });
                    var tw = new DoubleAnimation(star.Opacity, Math.Min(1, star.Opacity + 0.5), TimeSpan.FromSeconds(1.2 + rnd.NextDouble() * 2.5)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, BeginTime = TimeSpan.FromSeconds(rnd.NextDouble() * 3) };
                    star.BeginAnimation(UIElement.OpacityProperty, tw);
                    // wrap: stars start below the bottom again when they drift past the top
                    Canvas.SetTop(star, y + h);
                }
            }
            if (field.ActualHeight > 0) Fill(); else field.Loaded += (s, e) => Fill();
        }
    }
}
