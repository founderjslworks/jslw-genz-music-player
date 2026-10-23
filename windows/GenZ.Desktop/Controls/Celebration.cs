using System;
using System.Windows;
using System.Windows.Media;

namespace GenZ.Controls
{
    /// <summary>
    /// Full-window "love it!" celebration for favouriting a song (the same as GEN-Z on Android): a giant heart pops over the
    /// cover with a glow, then bursts into a shower of hearts, confetti and sparkles that rain over the whole window, with
    /// shock-wave rings. Unfavouriting plays a small, gentle heart shrink. Clicks pass straight through.
    /// </summary>
    public class Celebration : FrameworkElement
    {
        static readonly Geometry Heart;
        static Celebration()
        {
            var g = new StreamGeometry();
            using (var c = g.Open())
            {
                c.BeginFigure(new Point(0, 0.9), true, true);
                c.BezierTo(new Point(-1.25, 0.05), new Point(-0.75, -1.0), new Point(0, -0.38), true, true);
                c.BezierTo(new Point(0.75, -1.0), new Point(1.25, 0.05), new Point(0, 0.9), true, true);
            }
            g.Freeze();
            Heart = g;
        }

        readonly Random rnd = new Random(5);
        Color[] colors = { C(0xFF2D55), C(0xFF5C8A), C(0xFFC93C), C(0xB388FF), Colors.White };
        static Color C(int rgb) => Color.FromRgb((byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb);

        double bigX, bigY, bigT = -1, bigSize; bool love = true;
        const int N = 900;
        readonly double[] x = new double[N], y = new double[N], vx = new double[N], vy = new double[N], life = new double[N], max = new double[N], size = new double[N], rot = new double[N], vr = new double[N];
        readonly int[] kind = new int[N]; readonly Color[] col = new Color[N];
        int count;
        readonly double[] ringT = new double[4]; int rings;
        double time; DateTime last; bool running;

        public Celebration() { IsHitTestVisible = false; }

        public void SetColors(Color accent, Color second) =>
            colors = new[] { C(0xFF2D55), accent, second, C(0xFFC93C), C(0xFF5C8A), C(0xB388FF), Colors.White };

        public void Celebrate(Point center, double coverSize)
        {
            love = true; bigX = center.X; bigY = center.Y; bigSize = Math.Max(160, coverSize); bigT = 0; rings = 0;
            Start();
        }

        public void Unlove(Point center, double coverSize)
        {
            love = false; bigX = center.X; bigY = center.Y; bigSize = Math.Max(160, coverSize) * 0.6; bigT = 0;
            Start();
        }

        void Start()
        {
            if (running) return;
            running = true; last = DateTime.Now;
            CompositionTarget.Rendering += Frame;
        }

        double R(double a, double b) => a + rnd.NextDouble() * (b - a);

        int Add() { if (count >= N) return -1; int i = count++; life[i] = 0; return i; }

        void Spawn(int k, double speed, double sz)
        {
            int i = Add(); if (i < 0) return;
            double ang = R(0, Math.PI * 2);
            kind[i] = k;
            x[i] = bigX + Math.Cos(ang) * bigSize * 0.25; y[i] = bigY + Math.Sin(ang) * bigSize * 0.25;
            vx[i] = Math.Cos(ang) * speed; vy[i] = Math.Sin(ang) * speed - R(150, 450);
            max[i] = k == 0 ? R(1.6, 2.6) : k == 1 ? R(1.8, 3) : R(0.6, 1.3);
            size[i] = sz * (k == 0 ? 1.4 : 1);
            rot[i] = R(0, 360); vr[i] = R(-540, 540);
            col[i] = colors[rnd.Next(colors.Length)];
        }

        void Burst()
        {
            // hearts flying outwards and up, then raining down over the whole window
            for (int k = 0; k < 70; k++) Spawn(0, R(260, 900), R(18, 40) * 0.5);
            for (int k = 0; k < 90; k++) Spawn(1, R(300, 1100), R(4, 8));
            for (int k = 0; k < 40; k++) Spawn(2, R(150, 700), R(3, 7));
            // a second wave falling from the top edge so the shower fills the window
            for (int k = 0; k < 22; k++)
            {
                int i = Add(); if (i < 0) break;
                kind[i] = 0; x[i] = R(0, ActualWidth); y[i] = -R(10, 160);
                vx[i] = R(-40, 40); vy[i] = R(60, 220); max[i] = R(1.8, 2.6);
                size[i] = R(9, 18); rot[i] = R(-30, 30); vr[i] = R(-90, 90); col[i] = colors[rnd.Next(colors.Length)];
            }
            rings = 3; ringT[0] = 0; ringT[1] = -0.08; ringT[2] = -0.18;
            // firecrackers: rockets launch from the bottom of the window, one after another, and burst into sparks
            for (int k = 0; k < 7; k++)
            {
                int i = Add(); if (i < 0) break;
                kind[i] = 3;
                x[i] = R(ActualWidth * 0.08, ActualWidth * 0.92); y[i] = ActualHeight + 10;
                double apex = R(ActualHeight * 0.12, ActualHeight * 0.45);
                double tUp = R(0.65, 0.95);
                vy[i] = -(ActualHeight + 10 - apex) / tUp * 1.35; vx[i] = R(-60, 60);
                life[i] = -k * R(0.12, 0.28); max[i] = tUp;
                size[i] = 2.6; rot[i] = 0; vr[i] = 0;
                col[i] = colors[rnd.Next(colors.Length)];
            }
        }

        /// <summary>A rocket bursts: a sphere of coloured sparks with a bright flash.</summary>
        void Explode(double ex, double ey, Color c)
        {
            int n = 46;
            var second = colors[rnd.Next(colors.Length)];
            for (int k = 0; k < n; k++)
            {
                int i = Add(); if (i < 0) return;
                double ang = k / (double)n * Math.PI * 2 + R(-0.05, 0.05), sp = R(160, 340);
                kind[i] = 4; x[i] = ex; y[i] = ey;
                vx[i] = Math.Cos(ang) * sp; vy[i] = Math.Sin(ang) * sp;
                max[i] = R(0.9, 1.5); size[i] = R(1.6, 2.8); rot[i] = 0; vr[i] = 0;
                col[i] = k % 3 == 0 ? second : c;
            }
            int f = Add(); if (f < 0) return;
            kind[f] = 5; x[f] = ex; y[f] = ey; vx[f] = vy[f] = 0; max[f] = 0.35; size[f] = R(40, 70); col[f] = c; rot[f] = vr[f] = 0;
        }

        void Frame(object sender, EventArgs e)
        {
            var now = DateTime.Now;
            double dt = Math.Max(0, Math.Min(0.05, (now - last).TotalSeconds));
            last = now; time += dt;
            if (bigT >= 0)
            {
                double before = bigT; bigT += dt;
                if (love && before < 0.32 && bigT >= 0.32) Burst();
                if (bigT > (love ? 1.1 : 0.55)) bigT = -1;
            }
            for (int k = 0; k < rings; k++) ringT[k] += dt;
            if (rings > 0 && ringT[rings - 1] > 0.9) rings = 0;
            int idx = 0;
            while (idx < count)
            {
                life[idx] += dt;
                if (kind[idx] == 3)
                {
                    if (life[idx] < 0) { idx++; continue; }           // waiting to launch
                    if (life[idx] >= max[idx]) { var ex = x[idx]; var ey = y[idx]; var ec = col[idx]; life[idx] = max[idx] + 1; Explode(ex, ey, ec); }
                }
                if (life[idx] >= max[idx] || y[idx] > ActualHeight + 60)
                {
                    int l = count - 1;
                    x[idx] = x[l]; y[idx] = y[l]; vx[idx] = vx[l]; vy[idx] = vy[l]; life[idx] = life[l]; max[idx] = max[l];
                    size[idx] = size[l]; rot[idx] = rot[l]; vr[idx] = vr[l]; col[idx] = col[l]; kind[idx] = kind[l];
                    count--; continue;
                }
                double g = kind[idx] == 0 ? 520 : kind[idx] == 1 ? 700 : kind[idx] == 3 ? 260 : kind[idx] == 4 ? 140 : kind[idx] == 5 ? 0 : 300;
                double drag = kind[idx] == 0 ? 1.6 : kind[idx] == 1 ? 2.4 : kind[idx] == 3 ? 0.2 : kind[idx] == 4 ? 1.4 : 2;
                vy[idx] += g * dt;
                vx[idx] *= 1 - drag * dt; vy[idx] *= 1 - drag * dt * 0.6;
                x[idx] += vx[idx] * dt; y[idx] += vy[idx] * dt;
                rot[idx] += vr[idx] * dt;
                idx++;
            }
            InvalidateVisual();
            if (bigT < 0 && count == 0 && rings == 0) { CompositionTarget.Rendering -= Frame; running = false; }
        }

        static Brush B(Color c, double a) { var b = new SolidColorBrush(Color.FromArgb((byte)Math.Max(0, Math.Min(255, a)), c.R, c.G, c.B)); b.Freeze(); return b; }

        protected override void OnRender(DrawingContext dc)
        {
            double W = ActualWidth, H = ActualHeight;
            // shock-wave rings
            for (int k = 0; k < rings; k++)
            {
                double t = ringT[k]; if (t < 0) continue;
                double f = Math.Min(1, t / 0.9);
                var pen = new Pen(B(colors[k % colors.Length], 220 * (1 - f)), 14 * (1 - f) + 1);
                double r = bigSize * 0.4 + f * Math.Max(W, H) * 0.75;
                dc.DrawEllipse(null, pen, new Point(bigX, bigY), r, r);
            }
            // particles
            for (int i = 0; i < count; i++)
            {
                double f = life[i] / max[i];
                double a = f > 0.75 ? (1 - f) / 0.25 : 1;
                if (kind[i] >= 3)
                {
                    if (life[i] < 0) continue;
                    if (kind[i] == 5)
                    {
                        // burst flash
                        double fr = size[i] * (0.4 + f);
                        var rg = new RadialGradientBrush(Color.FromArgb((byte)(200 * (1 - f)), 255, 255, 255), Color.FromArgb(0, col[i].R, col[i].G, col[i].B));
                        dc.DrawEllipse(rg, null, new Point(x[i], y[i]), fr, fr);
                        continue;
                    }
                    // rocket or spark with a glowing trail
                    double tl = kind[i] == 3 ? 0.06 : 0.045;
                    var tail = new Pen(B(col[i], (kind[i] == 3 ? 200 : 170) * a), size[i] * 0.9) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round };
                    dc.DrawLine(tail, new Point(x[i] - vx[i] * tl, y[i] - vy[i] * tl), new Point(x[i], y[i]));
                    double tw = kind[i] == 4 ? 0.6 + 0.4 * Math.Sin(time * 30 + i) : 1;
                    dc.DrawEllipse(B(Colors.White, 255 * a * tw), null, new Point(x[i], y[i]), size[i] * 0.7, size[i] * 0.7);
                    dc.DrawEllipse(B(col[i], 120 * a), null, new Point(x[i], y[i]), size[i] * 1.8, size[i] * 1.8);
                    continue;
                }
                dc.PushTransform(new TranslateTransform(x[i], y[i]));
                dc.PushTransform(new RotateTransform(rot[i]));
                switch (kind[i])
                {
                    case 0:
                        double sc = size[i] * (0.9 + 0.15 * Math.Sin(time * 10 + i));
                        dc.PushTransform(new ScaleTransform(sc, sc));
                        dc.DrawGeometry(B(col[i], 240 * a), null, Heart);
                        dc.DrawEllipse(B(Colors.White, 110 * a), null, new Point(-0.38, -0.32), 0.16, 0.16);
                        dc.Pop();
                        break;
                    case 1:
                        double flip = Math.Cos(time * 9 + i);
                        double hh = Math.Abs(size[i] * 0.45 * flip);
                        dc.DrawRectangle(B(col[i], 255 * a), null, new Rect(-size[i], -hh, size[i] * 2, hh * 2 + 0.01));
                        break;
                    default:
                        double tw = 0.5 + 0.5 * Math.Sin(time * 20 + i * 3);
                        var br = B(Colors.White, 255 * a * tw);
                        double sz = size[i] * (0.6 + 0.6 * tw);
                        dc.DrawRectangle(br, null, new Rect(-sz * 0.18, -sz, sz * 0.36, sz * 2));
                        dc.DrawRectangle(br, null, new Rect(-sz, -sz * 0.18, sz * 2, sz * 0.36));
                        break;
                }
                dc.Pop(); dc.Pop();
            }
            // the big heart: pops with an overshoot, glows, then lifts away as the burst happens
            if (bigT >= 0)
            {
                if (love)
                {
                    double t = Math.Min(1, bigT / 0.32);
                    double pop = Overshoot(t);
                    double outT = Math.Max(0, (bigT - 0.32) / 0.78);
                    double sc = bigSize * 0.5 * (pop + outT * 0.5);
                    double a = 1 - outT;
                    dc.DrawEllipse(B(colors[0], 70 * a), null, new Point(bigX, bigY), sc * 1.6, sc * 1.6);
                    dc.PushTransform(new TranslateTransform(bigX, bigY - outT * 40));
                    dc.PushTransform(new ScaleTransform(sc, sc));
                    dc.DrawGeometry(B(colors[0], 255 * a), null, Heart);
                    var hl = Color.FromRgb((byte)((colors[0].R + 255 * 1.2) / 2.2), (byte)((colors[0].G + 255 * 1.2) / 2.2), (byte)((colors[0].B + 255 * 1.2) / 2.2));
                    dc.DrawEllipse(B(hl, 200 * a), null, new Point(-0.4, -0.3), 0.2, 0.2);
                    dc.Pop(); dc.Pop();
                }
                else
                {
                    double t = Math.Min(1, bigT / 0.55);
                    double sc = bigSize * 0.5 * (1 - t * 0.6);
                    dc.PushTransform(new TranslateTransform(bigX, bigY + t * 30));
                    dc.PushTransform(new ScaleTransform(sc, sc));
                    dc.DrawGeometry(B(Color.FromRgb(0x9E, 0x9E, 0x9E), 160 * (1 - t)), null, Heart);
                    dc.Pop(); dc.Pop();
                }
            }
        }

        static double Overshoot(double t) { const double T = 2.6; t -= 1; return t * t * ((T + 1) * t + T) + 1; }
    }
}
