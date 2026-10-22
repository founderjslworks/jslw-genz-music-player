using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace GenZ.Controls
{
    /// <summary>
    /// Progress bar with the theme's gradient, a glowing knob and twinkling stars that drift off the
    /// played part while music plays. Drag or click to seek.
    /// </summary>
    public class SparkleBar : FrameworkElement
    {
        class Star { public double X, Y, Vx, Vy, Life, Size; }
        readonly List<Star> stars = new List<Star>();
        readonly Random rnd = new Random();
        double value;
        bool playing, dragging, hooked;
        TimeSpan last;

        public event Action<double> Seek;
        public event Action<double> Scrubbing;

        public double Value { get => value; set { if (!dragging) { this.value = Math.Max(0, Math.Min(1, value)); InvalidateVisual(); } } }
        public bool Playing { get => playing; set { playing = value; Hook(); } }
        public double BarHeight { get; set; } = 5;

        public SparkleBar()
        {
            Height = 26; Cursor = Cursors.Hand; SnapsToDevicePixels = true;
            IsVisibleChanged += (s, e) => Hook();
            Unloaded += (s, e) => Unhook();
        }

        void Hook()
        {
            bool want = playing && IsVisible;
            if (want && !hooked) { CompositionTarget.Rendering += Tick; hooked = true; }
            else if (!want && hooked && stars.Count == 0) Unhook();
        }
        void Unhook() { if (hooked) { CompositionTarget.Rendering -= Tick; hooked = false; } }

        void Tick(object sender, EventArgs e)
        {
            var now = ((RenderingEventArgs)e).RenderingTime;
            double dt = last == TimeSpan.Zero ? 0.016 : Math.Min(0.05, (now - last).TotalSeconds);
            last = now;
            if (dt <= 0) return;
            double w = ActualWidth, cy = ActualHeight / 2;
            if (playing && rnd.NextDouble() < dt * 22 && w > 0)
            {
                double x = rnd.NextDouble() * value * w;
                stars.Add(new Star { X = x, Y = cy, Vx = (rnd.NextDouble() - 0.5) * 18, Vy = -(8 + rnd.NextDouble() * 22) * (rnd.NextDouble() < 0.5 ? 1 : -1), Life = 1, Size = 1.5 + rnd.NextDouble() * 2.5 });
            }
            for (int i = stars.Count - 1; i >= 0; i--)
            {
                var s = stars[i];
                s.X += s.Vx * dt; s.Y += s.Vy * dt; s.Life -= dt * 1.1;
                if (s.Life <= 0) stars.RemoveAt(i);
            }
            InvalidateVisual();
            if (!playing && stars.Count == 0) Unhook();
        }

        protected override void OnRender(DrawingContext dc)
        {
            double w = ActualWidth, h = ActualHeight, cy = h / 2, bh = BarHeight;
            if (w <= 0) return;
            var track = TryFindResource("DividerBrush") as Brush ?? Brushes.Gray;
            var fill = TryFindResource("ProgressBrush") as Brush ?? Brushes.HotPink;
            var accent = TryFindResource("AccentColor") is Color c ? c : Colors.HotPink;
            dc.DrawRoundedRectangle(Brushes.Transparent, null, new Rect(0, 0, w, h), 0, 0); // hit area
            dc.DrawRoundedRectangle(track, null, new Rect(0, cy - bh / 2, w, bh), bh / 2, bh / 2);
            double x = value * w;
            if (x > 0)
            {
                var fb = fill.Clone();
                if (fb is LinearGradientBrush lg) { lg.MappingMode = BrushMappingMode.Absolute; lg.StartPoint = new Point(0, 0); lg.EndPoint = new Point(Math.Max(1, w), 0); }
                dc.DrawRoundedRectangle(fb, null, new Rect(0, cy - bh / 2, x, bh), bh / 2, bh / 2);
            }
            foreach (var s in stars)
            {
                var a = (byte)(Math.Max(0, Math.Min(1, s.Life)) * 255);
                var b = new SolidColorBrush(Color.FromArgb(a, 255, 255, 255));
                DrawStar(dc, b, s.X, s.Y, s.Size * (0.6 + s.Life * 0.6));
                dc.DrawEllipse(new SolidColorBrush(Color.FromArgb((byte)(a / 3), accent.R, accent.G, accent.B)), null, new Point(s.X, s.Y), s.Size * 1.6, s.Size * 1.6);
            }
            var glow = new RadialGradientBrush(Color.FromArgb(150, accent.R, accent.G, accent.B), Color.FromArgb(0, accent.R, accent.G, accent.B));
            dc.DrawEllipse(glow, null, new Point(x, cy), bh * 3.2, bh * 3.2);
            dc.DrawEllipse(Brushes.White, new Pen(new SolidColorBrush(accent), 2), new Point(x, cy), bh * 1.4, bh * 1.4);
        }

        static void DrawStar(DrawingContext dc, Brush b, double x, double y, double r)
        {
            var g = new StreamGeometry();
            using (var ctx = g.Open())
            {
                ctx.BeginFigure(new Point(x, y - r), true, true);
                ctx.LineTo(new Point(x + r * 0.28, y - r * 0.28), false, false);
                ctx.LineTo(new Point(x + r, y), false, false);
                ctx.LineTo(new Point(x + r * 0.28, y + r * 0.28), false, false);
                ctx.LineTo(new Point(x, y + r), false, false);
                ctx.LineTo(new Point(x - r * 0.28, y + r * 0.28), false, false);
                ctx.LineTo(new Point(x - r, y), false, false);
                ctx.LineTo(new Point(x - r * 0.28, y - r * 0.28), false, false);
            }
            g.Freeze();
            dc.DrawGeometry(b, null, g);
        }

        protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
        {
            dragging = true; CaptureMouse(); Update(e.GetPosition(this).X); e.Handled = true;
        }
        protected override void OnMouseMove(MouseEventArgs e) { if (dragging) Update(e.GetPosition(this).X); }
        protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
        {
            if (!dragging) return;
            dragging = false; ReleaseMouseCapture(); Seek?.Invoke(value);
        }
        void Update(double x)
        {
            value = Math.Max(0, Math.Min(1, x / Math.Max(1, ActualWidth)));
            Scrubbing?.Invoke(value); InvalidateVisual();
        }
    }

    /// <summary>The Indian tricolour with the 24-spoke Ashoka Chakra.</summary>
    public class Tricolor : FrameworkElement
    {
        public Tricolor() { Width = 18; Height = 12; }
        protected override void OnRender(DrawingContext dc)
        {
            double w = ActualWidth, h = ActualHeight, band = h / 3;
            dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(0xFF, 0x99, 0x33)), null, new Rect(0, 0, w, band));
            dc.DrawRectangle(Brushes.White, null, new Rect(0, band, w, band));
            dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(0x13, 0x88, 0x08)), null, new Rect(0, band * 2, w, band));
            var navy = new SolidColorBrush(Color.FromRgb(0, 0, 0x80));
            double r = band * 0.46, cx = w / 2, cy = h / 2;
            dc.DrawEllipse(null, new Pen(navy, Math.Max(0.6, r * 0.18)), new Point(cx, cy), r, r);
            var spoke = new Pen(navy, Math.Max(0.3, r * 0.08));
            for (int k = 0; k < 24; k++)
            {
                double a = Math.PI * 2 * k / 24;
                dc.DrawLine(spoke, new Point(cx, cy), new Point(cx + Math.Cos(a) * r, cy + Math.Sin(a) * r));
            }
            dc.DrawRectangle(null, new Pen(new SolidColorBrush(Color.FromArgb(64, 0, 0, 0)), 0.6), new Rect(0.3, 0.3, w - 0.6, h - 0.6));
        }
    }
}
