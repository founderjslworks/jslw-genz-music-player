using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Media;

namespace GenZ.Controls
{
    /// <summary>
    /// Particles for the volume slider: glowing motes and little stars rise off the filled part of the bar with the
    /// beat of the music, and a burst of sparks flies from the knob when the volume is changed. Drawn in one pass
    /// with cached, frozen brushes, so it costs almost nothing.
    /// </summary>
    public class VolumeSparks : FrameworkElement
    {
        struct P { public double X, Y, Vx, Vy, Life, Max, Size; public int Kind, Col; }
        readonly List<P> ps = new List<P>(160);
        readonly Random rnd = new Random();
        bool hooked; TimeSpan last; double carry; DateTime pulseAt;
        Color[] cols = { Colors.White, Colors.HotPink, Colors.MediumPurple };

        public VolumeSparks() { IsHitTestVisible = false; ClipToBounds = false; }

        /// <summary>Theme colours: the accent, a second colour and white.</summary>
        public void SetColors(Color accent, Color second) => cols = new[] { Colors.White, accent, second, accent };

        double R(double a, double b) => a + rnd.NextDouble() * (b - a);

        /// <summary>Called every frame while music plays: <paramref name="level"/> 0..1 sets how many particles rise.</summary>
        public void Pulse(double level, double frac)
        {
            if (ActualWidth <= 0 || frac <= 0.005) return;
            var now = DateTime.UtcNow;
            double dt = pulseAt == default ? 1 / 60.0 : Math.Min(0.1, (now - pulseAt).TotalSeconds);
            pulseAt = now;
            carry += level * level * 130 * dt;            // about 130 particles a second at full level, at any refresh rate
            while (carry >= 1 && ps.Count < 150)
            {
                carry -= 1;
                double x = R(0, ActualWidth * frac);
                Add(new P { X = x, Y = ActualHeight / 2 + R(-1.5, 1.5), Vx = R(-14, 14), Vy = -R(18, 46) * (0.6 + level), Max = R(0.6, 1.3), Size = R(1, 2.4), Kind = rnd.Next(5) == 0 ? 1 : 0, Col = rnd.Next(cols.Length) });
            }
            if (carry > 3) carry = 0;
        }

        /// <summary>A burst of sparks from the knob (the volume was just changed).</summary>
        public void Burst(double frac)
        {
            if (ActualWidth <= 0) return;
            double kx = ActualWidth * Math.Max(0, Math.Min(1, frac)), ky = ActualHeight / 2;
            for (int i = 0; i < 16 && ps.Count < 150; i++)
            {
                double ang = R(0, Math.PI * 2), sp = R(40, 120);
                Add(new P { X = kx, Y = ky, Vx = Math.Cos(ang) * sp, Vy = Math.Sin(ang) * sp - 25, Max = R(0.35, 0.75), Size = R(1.2, 2.6), Kind = i % 4 == 0 ? 1 : 0, Col = rnd.Next(cols.Length) });
            }
        }

        void Add(P p)
        {
            ps.Add(p);
            if (!hooked) { hooked = true; last = TimeSpan.Zero; CompositionTarget.Rendering += Tick; }
        }

        void Tick(object sender, EventArgs e)
        {
            var now = ((RenderingEventArgs)e).RenderingTime;
            double dt = last == TimeSpan.Zero ? 1 / 60.0 : Math.Min(0.05, (now - last).TotalSeconds);
            if (dt <= 0) return;
            last = now;
            for (int i = ps.Count - 1; i >= 0; i--)
            {
                var p = ps[i];
                p.Life += dt;
                if (p.Life >= p.Max) { ps.RemoveAt(i); continue; }
                p.Vx *= 1 - 1.8 * dt; p.Vy *= 1 - 1.2 * dt; p.Vy -= 8 * dt;
                p.X += p.Vx * dt; p.Y += p.Vy * dt;
                ps[i] = p;
            }
            InvalidateVisual();
            if (ps.Count == 0) { CompositionTarget.Rendering -= Tick; hooked = false; }
        }

        protected override void OnRender(DrawingContext dc)
        {
            foreach (var p in ps)
            {
                double f = p.Life / p.Max;
                double a = f < 0.15 ? f / 0.15 : 1 - (f - 0.15) / 0.85;
                var c = cols[p.Col % cols.Length];
                var at = new Point(p.X, p.Y);
                if (p.Kind == 1)
                {
                    double s = p.Size * 2.2 * (1 - f * 0.4);
                    var w = B(Colors.White, 255 * a);
                    dc.DrawRectangle(w, null, new Rect(p.X - s * 0.14, p.Y - s, s * 0.28, s * 2));
                    dc.DrawRectangle(w, null, new Rect(p.X - s, p.Y - s * 0.14, s * 2, s * 0.28));
                    dc.DrawEllipse(B(c, 90 * a), null, at, s * 0.8, s * 0.8);
                }
                else
                {
                    dc.DrawEllipse(B(c, 80 * a), null, at, p.Size * 2.4, p.Size * 2.4);
                    dc.DrawEllipse(B(c == Colors.White ? c : Mix(c), 240 * a), null, at, p.Size * 0.8, p.Size * 0.8);
                }
            }
        }

        static Color Mix(Color c) => Color.FromRgb((byte)((c.R + 255) / 2), (byte)((c.G + 255) / 2), (byte)((c.B + 255) / 2));

        static readonly Dictionary<uint, Brush> cache = new Dictionary<uint, Brush>();
        static Brush B(Color c, double a)
        {
            byte al = (byte)((int)Math.Max(0, Math.Min(255, a)) & 0xF0);
            uint key = (uint)(al << 24 | c.R << 16 | c.G << 8 | c.B);
            if (cache.TryGetValue(key, out var b)) return b;
            if (cache.Count > 2000) cache.Clear();
            b = new SolidColorBrush(Color.FromArgb(al, c.R, c.G, c.B)); b.Freeze();
            cache[key] = b; return b;
        }
    }
}
