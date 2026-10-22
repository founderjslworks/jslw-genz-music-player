using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using GenZ.Core;

namespace GenZ.Controls
{
    /// <summary>sin(πt): goes out and comes back, like Android's CycleInterpolator(0.5).</summary>
    public class CycleEase : EasingFunctionBase
    {
        protected override double EaseInCore(double t) => Math.Sin(Math.PI * t);
        protected override Freezable CreateInstanceCore() => new CycleEase();
    }

    /// <summary>
    /// List scroll animations – the set popularised by XuiMod (Apache License 2.0, © 2013 XuiMod / zst123),
    /// the same as GEN-Z on Android: rows animate in as they scroll onto the screen.
    /// </summary>
    public static class ListAnim
    {
        public static readonly string[] Names =
        {
            "Off", "Wave (left)", "Wave (right)", "Scale", "Fade", "Stack from top", "Stack from bottom",
            "Unfold", "Fold", "Slide from left", "Slide from right", "Rotate", "Fly-in (left)", "Fly-in (right)",
        };
        public static readonly string[] Interpolators =
        {
            "Accelerate", "Decelerate", "Accelerate–decelerate", "Anticipate", "Overshoot",
            "Anticipate–overshoot", "Bounce", "Cycle", "Linear",
        };

        public static IEasingFunction Ease(int i)
        {
            switch (i)
            {
                case 0: return new QuadraticEase { EasingMode = EasingMode.EaseIn };
                case 2: return new SineEase { EasingMode = EasingMode.EaseInOut };
                case 3: return new BackEase { Amplitude = 0.6, EasingMode = EasingMode.EaseIn };
                case 4: return new BackEase { Amplitude = 0.6, EasingMode = EasingMode.EaseOut };
                case 5: return new BackEase { Amplitude = 0.6, EasingMode = EasingMode.EaseInOut };
                case 6: return new BounceEase { Bounces = 3, Bounciness = 2.5, EasingMode = EasingMode.EaseOut };
                case 7: return new CycleEase { EasingMode = EasingMode.EaseIn };
                case 8: return null;
                default: return new PowerEase { Power = 3.2, EasingMode = EasingMode.EaseOut };
            }
        }

        /// <summary>Plays scroll animation <paramref name="type"/> on a row that just came into view.</summary>
        public static void Animate(FrameworkElement v, bool down, int type, int durationMs, int interp)
        {
            if (type <= 0 || v == null) return;
            double h = v.ActualHeight > 0 ? v.ActualHeight : 56, w = v.ActualWidth > 0 ? v.ActualWidth : 600;
            var dur = Settings.Ms(durationMs);
            if (dur == TimeSpan.Zero) return;
            var ease = Ease(interp);
            var st = new ScaleTransform(1, 1); var rt = new RotateTransform(0); var tt = new TranslateTransform();
            var g = new TransformGroup(); g.Children.Add(st); g.Children.Add(rt); g.Children.Add(tt);
            v.RenderTransform = g;
            v.BeginAnimation(UIElement.OpacityProperty, null); v.Opacity = 1;
            DoubleAnimation A(double from, double to) => new DoubleAnimation(from, to, dur) { EasingFunction = ease };
            void Scale(double cx, double cy)
            {
                st.CenterX = cx; st.CenterY = cy;
                st.BeginAnimation(ScaleTransform.ScaleXProperty, A(0.5, 1)); st.BeginAnimation(ScaleTransform.ScaleYProperty, A(0.5, 1));
            }
            switch (type)
            {
                case 1: Scale(0, 0); break;
                case 2: Scale(w, h); break;
                case 3: Scale(w / 2, h / 2); break;
                case 4: v.BeginAnimation(UIElement.OpacityProperty, A(0, 1)); break;
                case 5: tt.BeginAnimation(TranslateTransform.YProperty, A(-h, 0)); break;
                case 6: tt.BeginAnimation(TranslateTransform.YProperty, A(h, 0)); break;
                case 7: tt.BeginAnimation(TranslateTransform.YProperty, A(down ? -h : h, 0)); break;
                case 8: tt.BeginAnimation(TranslateTransform.YProperty, A(down ? h : -h, 0)); break;
                case 9: tt.BeginAnimation(TranslateTransform.XProperty, A(-w, 0)); break;
                case 10: tt.BeginAnimation(TranslateTransform.XProperty, A(w, 0)); break;
                case 11: rt.CenterX = w / 2; rt.CenterY = h / 2; rt.BeginAnimation(RotateTransform.AngleProperty, A(180, 0)); break;
                case 12:
                case 13:
                {
                    bool left = type == 12;
                    tt.BeginAnimation(TranslateTransform.YProperty, A(down ? h * 1.4 : -h * 1.4, 0));
                    v.BeginAnimation(UIElement.OpacityProperty, A(0, 1));
                    rt.CenterX = left ? 0 : w; rt.CenterY = 0;
                    rt.BeginAnimation(RotateTransform.AngleProperty, A(down == left ? 12 : -12, 0));
                    break;
                }
            }
        }

        /// <summary>Animate the rows of a ListBox as they scroll into view (like the RecyclerView version on Android).</summary>
        public static void Attach(ListBox list)
        {
            ScrollViewer sv = null;
            var seen = new HashSet<int>();
            int lastFirst = 0;
            list.Loaded += (s, e) =>
            {
                if (sv != null) return;
                sv = FindChild<ScrollViewer>(list);
                if (sv == null) return;
                sv.ScrollChanged += (o, a) =>
                {
                    int first = (int)sv.VerticalOffset, count = (int)Math.Ceiling(sv.ViewportHeight) + 1;
                    var cur = Settings.Current;
                    if (a.ExtentHeightChange != 0 || a.VerticalChange == 0 || cur.ReduceMotion || cur.ScrollAnim == 0)
                    {
                        seen.Clear(); for (int i = first; i < first + count; i++) seen.Add(i);
                        lastFirst = first; return;
                    }
                    bool down = first >= lastFirst; lastFirst = first;
                    var now = new HashSet<int>();
                    for (int i = first; i < first + count; i++)
                    {
                        now.Add(i);
                        if (seen.Contains(i)) continue;
                        if (list.ItemContainerGenerator.ContainerFromIndex(i) is FrameworkElement row)
                            Animate(row, down, cur.ScrollAnim, cur.ScrollDuration, cur.ScrollInterp);
                    }
                    seen.Clear(); foreach (var i in now) seen.Add(i);
                };
            };
        }

        public static T FindChild<T>(DependencyObject root) where T : DependencyObject
        {
            if (root == null) return null;
            for (int i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
            {
                var c = VisualTreeHelper.GetChild(root, i);
                if (c is T t) return t;
                var r = FindChild<T>(c);
                if (r != null) return r;
            }
            return null;
        }
    }

    /// <summary>Playful, springy motion: rows bounce in, and squish + spring back when pressed.</summary>
    public static class Bounce
    {
        /// <summary>Android Bounce.enter: rise 90% of their height, scale .85 → 1 with overshoot, fade in, staggered.</summary>
        public static void Enter(IList<FrameworkElement> items, double stepMs = 42, int max = 40)
        {
            var s = Settings.Current;
            if (s.ReduceMotion || s.ListAnimation == "none") return;
            for (int i = 0; i < items.Count; i++)
            {
                var v = items[i];
                if (i >= max) { v.Opacity = 1; continue; }
                var delay = Settings.Ms(i * stepMs);
                var dur = Settings.Ms(520);
                v.Opacity = 0;
                v.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, Settings.Ms(300)) { BeginTime = delay, EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut } });
                if (s.ListAnimation == "fade") { v.RenderTransform = Transform.Identity; continue; }
                double h = v.ActualHeight > 0 ? v.ActualHeight : 56;
                var st = new ScaleTransform(1, 1); var tt = new TranslateTransform();
                var g = new TransformGroup(); g.Children.Add(st); g.Children.Add(tt);
                v.RenderTransformOrigin = new Point(0.5, 0.5);
                v.RenderTransform = g;
                if (s.ListAnimation == "cascade")
                {
                    tt.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(-26, 0, Settings.Ms(380)) { BeginTime = delay, EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
                    continue;
                }
                // "bounce" (default, as on Android)
                tt.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(h * 0.9, 0, dur) { BeginTime = delay, EasingFunction = new BackEase { Amplitude = 0.55, EasingMode = EasingMode.EaseOut } });
                var sc = new DoubleAnimation(0.85, 1, dur) { BeginTime = delay, EasingFunction = new BackEase { Amplitude = 0.7, EasingMode = EasingMode.EaseOut } };
                st.BeginAnimation(ScaleTransform.ScaleXProperty, sc); st.BeginAnimation(ScaleTransform.ScaleYProperty, sc);
            }
        }

        /// <summary>Squish to 94% on mouse down, spring back with overshoot on release. Clicks still work.</summary>
        public static void Press(FrameworkElement v)
        {
            v.PreviewMouseLeftButtonDown += (s, e) => Squish(v, true);
            v.PreviewMouseLeftButtonUp += (s, e) => Squish(v, false);
            v.MouseLeave += (s, e) => { if (e.LeftButton == MouseButtonState.Pressed) Squish(v, false); };
        }

        public static void Squish(FrameworkElement v, bool down)
        {
            if (Settings.Current.ReduceMotion) return;
            var st = PressScale(v);
            var a = down
                ? new DoubleAnimation(0.94, TimeSpan.FromMilliseconds(90)) { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut } }
                : new DoubleAnimation(1, TimeSpan.FromMilliseconds(460)) { EasingFunction = new ElasticEase { Oscillations = 1, Springiness = 4 } };
            st.BeginAnimation(ScaleTransform.ScaleXProperty, a); st.BeginAnimation(ScaleTransform.ScaleYProperty, a);
        }

        static readonly DependencyProperty PressScaleProperty = DependencyProperty.RegisterAttached("PressScale", typeof(ScaleTransform), typeof(Bounce));

        /// <summary>A scale transform that lives inside the element's render transform (kept even when other animations replace it).</summary>
        static ScaleTransform PressScale(FrameworkElement v)
        {
            var st = (ScaleTransform)v.GetValue(PressScaleProperty);
            if (st != null && v.RenderTransform is TransformGroup g0 && g0.Children.Contains(st)) return st;
            st = new ScaleTransform(1, 1);
            v.SetValue(PressScaleProperty, st);
            var g = new TransformGroup();
            if (v.RenderTransform != null && v.RenderTransform != Transform.Identity) g.Children.Add(v.RenderTransform.IsFrozen ? v.RenderTransform.Clone() : v.RenderTransform);
            g.Children.Add(st);
            v.RenderTransformOrigin = new Point(0.5, 0.5);
            v.RenderTransform = g;
            return st;
        }
    }

    /// <summary>
    /// Staggered, springy entrance for form-like screens (Equalizer, Settings, Animations, Themes): every section, row and
    /// pill arrives one after another. Vertical stacks are opened up so their rows animate individually.
    /// </summary>
    public static class Stagger
    {
        public static void Enter(Panel container, double startDelay = 60, double step = 38)
        {
            if (Settings.Current.ReduceMotion) return;
            int i = 0;
            Walk(container, startDelay, step, ref i);
        }

        static void Walk(Panel panel, double startDelay, double step, ref int i)
        {
            foreach (UIElement el in panel.Children)
            {
                if (!(el is FrameworkElement v) || v.Visibility != Visibility.Visible) continue;
                if (i > 70) { v.Opacity = 1; continue; }
                if (v is StackPanel sp && sp.Orientation == Orientation.Vertical && sp.Children.Count > 1) { Walk(sp, startDelay, step, ref i); continue; }
                if (v is WrapPanel wp && wp.Children.Count > 0)
                {
                    // pills pop in quickly one after another
                    double baseDelay = startDelay + i * step;
                    int k = 0;
                    foreach (UIElement c in wp.Children) if (c is FrameworkElement f) Pop(f, baseDelay + k++ * 18);
                    i++;
                    continue;
                }
                Rise(v, startDelay + i * step);
                i++;
            }
        }

        public static void Rise(FrameworkElement v, double delayMs, double distance = 36)
        {
            var delay = Settings.Ms(delayMs);
            var dur = Settings.Ms(520);
            v.Opacity = 0;
            v.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, Settings.Ms(320)) { BeginTime = delay });
            var st = new ScaleTransform(0.96, 0.96); var tt = new TranslateTransform(0, distance);
            var g = new TransformGroup(); g.Children.Add(st); g.Children.Add(tt);
            v.RenderTransformOrigin = new Point(0.5, 0.5); v.RenderTransform = g;
            var ease = new BackEase { Amplitude = 0.45, EasingMode = EasingMode.EaseOut };
            tt.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(distance, 0, dur) { BeginTime = delay, EasingFunction = ease });
            var sc = new DoubleAnimation(0.96, 1, dur) { BeginTime = delay, EasingFunction = ease };
            st.BeginAnimation(ScaleTransform.ScaleXProperty, sc); st.BeginAnimation(ScaleTransform.ScaleYProperty, sc);
        }

        public static void Pop(FrameworkElement v, double delayMs)
        {
            var delay = Settings.Ms(delayMs);
            v.Opacity = 0;
            v.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, Settings.Ms(220)) { BeginTime = delay });
            var st = new ScaleTransform(0.6, 0.6);
            v.RenderTransformOrigin = new Point(0.5, 0.5); v.RenderTransform = st;
            var a = new DoubleAnimation(0.6, 1, Settings.Ms(420)) { BeginTime = delay, EasingFunction = new BackEase { Amplitude = 0.6, EasingMode = EasingMode.EaseOut } };
            st.BeginAnimation(ScaleTransform.ScaleXProperty, a); st.BeginAnimation(ScaleTransform.ScaleYProperty, a);
        }
    }

    /// <summary>
    /// Screen-to-screen transitions, the same set as GEN-Z on Android. "Zoom", "Drop", "Fold" and "Swoop" are adapted
    /// from the window animation sets shipped with XuiMod (Apache License 2.0). The old screen is captured as a picture
    /// and animated out over the new one as it comes in.
    /// </summary>
    public static class Transitions
    {
        public static readonly (string id, string label)[] Styles =
        {
            ("genz", "GEN-Z slide"), ("fade", "Fade"), ("zoom", "Zoom"), ("grow", "Grow"), ("slide_up", "Slide up"),
            ("drop", "Drop"), ("fold", "Fold"), ("swoop", "Swoop"), ("random", "Random"),
        };

        static readonly Random rnd = new Random();

        /// <summary>A picture of the screen that is about to leave, placed over it.</summary>
        public static Image Capture(FrameworkElement area, Panel overlayHost)
        {
            if (area.ActualWidth < 2 || area.ActualHeight < 2) return null;
            try
            {
                var dpi = VisualTreeHelper.GetDpi(area);
                var bmp = new RenderTargetBitmap((int)Math.Ceiling(area.ActualWidth * dpi.DpiScaleX), (int)Math.Ceiling(area.ActualHeight * dpi.DpiScaleY),
                    96 * dpi.DpiScaleX, 96 * dpi.DpiScaleY, PixelFormats.Pbgra32);
                // draw through a VisualBrush: rendering the element directly would include its offset in the window and shift the picture
                var dv = new DrawingVisual();
                using (var ctx = dv.RenderOpen())
                    ctx.DrawRectangle(new VisualBrush(area) { Stretch = Stretch.None, AlignmentX = AlignmentX.Left, AlignmentY = AlignmentY.Top },
                        null, new Rect(0, 0, area.ActualWidth, area.ActualHeight));
                bmp.Render(dv);
                bmp.Freeze();
                var img = new Image { Source = bmp, Stretch = Stretch.Fill, IsHitTestVisible = false, Width = area.ActualWidth, Height = area.ActualHeight,
                    HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top };
                var p = area.TranslatePoint(new Point(0, 0), overlayHost);
                img.Margin = new Thickness(p.X, p.Y, 0, 0);
                if (overlayHost is Grid) { Grid.SetRowSpan(img, 10); Grid.SetColumnSpan(img, 10); }
                overlayHost.Children.Add(img);
                return img;
            }
            catch { return null; }
        }

        /// <summary>Runs the enter animation on <paramref name="page"/> and the exit animation on the captured old screen.</summary>
        public static void Run(string style, FrameworkElement page, Image old, Panel overlayHost, FrameworkElement clipArea)
        {
            page.BeginAnimation(UIElement.OpacityProperty, null); page.Opacity = 1; page.RenderTransform = Transform.Identity;
            if (Settings.Current.ReduceMotion || style == "none") { Remove(old, overlayHost); return; }
            if (style == "random") style = Styles[rnd.Next(Styles.Length - 1)].id;
            double w = Math.Max(1, clipArea.ActualWidth), h = Math.Max(1, clipArea.ActualHeight);
            if (old != null) old.Clip = new RectangleGeometry(new Rect(0, 0, w, h));

            var dec = new PowerEase { Power = 2, EasingMode = EasingMode.EaseOut };
            var acc = new QuadraticEase { EasingMode = EasingMode.EaseIn };
            var lin = (IEasingFunction)null;
            TimeSpan Ms(double ms) => Settings.Ms(ms);

            var inT = Setup(page, 0.5, 0.5);
            (ScaleTransform s, TranslateTransform t)? outT = null;
            if (old != null) outT = Setup(old, 0.5, 0.5);
            double longest = 0;

            void Anim(DependencyObject target, DependencyProperty prop, double from, double to, double ms, IEasingFunction ease, double delay = 0)
            {
                if (target == null) return;
                longest = Math.Max(longest, ms + delay);
                var a = new DoubleAnimation(from, to, Ms(ms)) { EasingFunction = ease, BeginTime = Ms(delay), FillBehavior = FillBehavior.HoldEnd };
                if (target is UIElement u) u.BeginAnimation(prop, a);
                else ((Animatable)target).BeginAnimation(prop, a);
            }

            var op = UIElement.OpacityProperty;
            switch (style)
            {
                case "fade":
                    Anim(page, op, 0, 1, 260, lin);
                    Anim(old, op, 1, 0, 260, lin);
                    break;
                case "zoom":
                    page.RenderTransformOrigin = new Point(0.5, 0.1); if (old != null) old.RenderTransformOrigin = new Point(0.5, 0.1);
                    Anim(page, op, 0, 1, 200, lin);
                    Anim(inT.s, ScaleTransform.ScaleXProperty, 0.95, 1, 220, dec); Anim(inT.s, ScaleTransform.ScaleYProperty, 0.95, 1, 220, dec);
                    if (outT != null)
                    {
                        Anim(outT.Value.s, ScaleTransform.ScaleXProperty, 1, 1.11, 220, dec); Anim(outT.Value.s, ScaleTransform.ScaleYProperty, 1, 1.11, 220, dec);
                        Anim(old, op, 1, 0, 220, lin);
                    }
                    break;
                case "grow":
                    Anim(inT.s, ScaleTransform.ScaleXProperty, 0.8, 1, 300, dec); Anim(inT.s, ScaleTransform.ScaleYProperty, 0.8, 1, 300, dec);
                    Anim(page, op, 0, 1, 250, dec);
                    Anim(old, op, 1, 0, 300, lin);
                    break;
                case "slide_up":
                    Anim(inT.t, TranslateTransform.YProperty, h, 0, 340, dec);
                    Anim(old, op, 1, 0.3, 340, lin);
                    break;
                case "drop":
                    Anim(inT.t, TranslateTransform.YProperty, -h, 0, 420, dec);
                    Anim(inT.s, ScaleTransform.ScaleXProperty, 0.4, 1, 320, dec, 140);
                    if (outT != null)
                    {
                        Anim(outT.Value.t, TranslateTransform.YProperty, 0, h, 420, dec);
                        Anim(outT.Value.s, ScaleTransform.ScaleXProperty, 1, 0.4, 320, dec, 140);
                    }
                    break;
                case "fold":
                    if (outT != null) Anim(outT.Value.s, ScaleTransform.ScaleYProperty, 1, 0, 200, acc);
                    page.Opacity = 0;
                    Anim(page, op, 0, 1, 1, lin, 180);
                    Anim(inT.s, ScaleTransform.ScaleYProperty, 0, 1, 200, lin, 180);
                    break;
                case "swoop":
                    Anim(inT.t, TranslateTransform.YProperty, -h * 0.6, 0, 320, dec);
                    Anim(page, op, 0, 1, 320, dec);
                    if (outT != null)
                    {
                        Anim(outT.Value.t, TranslateTransform.XProperty, 0, -w * 0.5, 320, acc);
                        Anim(old, op, 1, 0, 320, acc);
                    }
                    break;
                default: // "genz": slide in from the right, old screen slides 30% left and fades
                    Anim(inT.t, TranslateTransform.XProperty, w, 0, 320, dec);
                    Anim(page, op, 0.6, 1, 320, dec);
                    if (outT != null)
                    {
                        Anim(outT.Value.t, TranslateTransform.XProperty, 0, -w * 0.3, 320, dec);
                        Anim(old, op, 1, 0, 320, dec);
                    }
                    break;
            }
            // the old picture goes when its animation ends
            if (old != null)
            {
                var t = new System.Windows.Threading.DispatcherTimer { Interval = Ms(longest + 30) };
                if (t.Interval == TimeSpan.Zero) t.Interval = TimeSpan.FromMilliseconds(1);
                t.Tick += (s, e) => { t.Stop(); Remove(old, overlayHost); };
                t.Start();
            }
        }

        static void Remove(Image old, Panel host) { if (old != null) host.Children.Remove(old); }

        static (ScaleTransform s, TranslateTransform t) Setup(FrameworkElement v, double ox, double oy)
        {
            var s = new ScaleTransform(1, 1); var t = new TranslateTransform();
            var g = new TransformGroup(); g.Children.Add(s); g.Children.Add(t);
            v.RenderTransformOrigin = new Point(ox, oy);
            v.RenderTransform = g;
            return (s, t);
        }
    }
}
