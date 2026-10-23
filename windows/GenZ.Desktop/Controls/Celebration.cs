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

        // ---- romantic 3D heart frame (glossy hearts around the edges, bokeh, sparkles, warm glow)
        double frameT = -1;
        const double FrameLen = 3.8;
        struct FHeart { public double X, Y, Size, Phase, Spin, Appear, Bob, Depth; }
        struct Bokeh { public double X, Y, R, Phase, Rise; public byte A; }
        FHeart[] fh = new FHeart[0];
        Bokeh[] bk = new Bokeh[0];
        static readonly Brush GlossFront = MakeGloss(Color.FromRgb(0xFF, 0x9A, 0xAE), Color.FromRgb(0xE8, 0x12, 0x3A), Color.FromRgb(0x7A, 0x00, 0x16));
        static readonly Brush GlossBack = MakeGloss(Color.FromRgb(0xC9, 0x2B, 0x4B), Color.FromRgb(0x9A, 0x06, 0x25), Color.FromRgb(0x4A, 0x00, 0x0C));
        static readonly Brush Shade = Frozen(new SolidColorBrush(Color.FromArgb(70, 60, 0, 10)));

        static Brush MakeGloss(Color hi, Color mid, Color lo)
        {
            var b = new RadialGradientBrush { GradientOrigin = new Point(0.32, 0.28), Center = new Point(0.42, 0.4), RadiusX = 0.75, RadiusY = 0.75 };
            b.GradientStops.Add(new GradientStop(hi, 0)); b.GradientStops.Add(new GradientStop(mid, 0.45)); b.GradientStops.Add(new GradientStop(lo, 1));
            b.Freeze(); return b;
        }
        static Brush Frozen(Brush b) { b.Freeze(); return b; }

        // Fading is done with pre-built, frozen brush copies at 21 opacity levels – never with PushOpacity, which makes
        // WPF render every particle into its own off-screen layer (that is what made the frame and glitter effects stall).
        static readonly System.Collections.Generic.Dictionary<Brush, Brush[]> opCache = new System.Collections.Generic.Dictionary<Brush, Brush[]>();
        static Brush Op(Brush b, double a)
        {
            int lv = (int)Math.Round(Math.Max(0, Math.Min(1, a)) * 20);
            if (!opCache.TryGetValue(b, out var arr)) { arr = new Brush[21]; opCache[b] = arr; }
            var r = arr[lv];
            if (r == null) { r = b.Clone(); r.Opacity = lv / 20.0; r.Freeze(); arr[lv] = r; }
            return r;
        }
        static readonly Brush Vignette = MakeVignette();
        static Brush MakeVignette()
        {
            var v = new RadialGradientBrush { RadiusX = 0.75, RadiusY = 0.75 };
            v.GradientStops.Add(new GradientStop(Color.FromArgb(0, 255, 30, 70), 0.45));
            v.GradientStops.Add(new GradientStop(Color.FromArgb(150, 200, 0, 40), 1));
            v.Freeze(); return v;
        }
        static readonly Brush BokehBrush = Frozen(new RadialGradientBrush(Color.FromArgb(110, 255, 150, 180), Color.FromArgb(0, 255, 80, 120)));
        static readonly Brush GlitBack = Frozen(new RadialGradientBrush(Color.FromArgb(110, 0x9B, 0x4D, 0xFF), Color.FromArgb(0, 0x40, 0x10, 0x80)));
        static readonly Color[] GlitCol = { Color.FromRgb(0xFF, 0xFF, 0xFF), Color.FromRgb(0xE3, 0x9B, 0xFF), Color.FromRgb(0xFF, 0x7A, 0xD9), Color.FromRgb(0xB0, 0x7C, 0xFF) };

        void BuildFrame()
        {
            double W = Math.Max(400, ActualWidth), H = Math.Max(300, ActualHeight);
            var list = new System.Collections.Generic.List<FHeart>();
            double edge = Math.Min(W, H) * 0.12;
            // hearts along the four edges, bigger in the corners, like a frame
            int perW = (int)(W / 70), perH = (int)(H / 70);
            void Add2(double px, double py, double sz)
                => list.Add(new FHeart { X = px, Y = py, Size = sz, Phase = R(0, 6.28), Spin = R(1.2, 2.6) * (rnd.Next(2) == 0 ? 1 : -1), Appear = R(0, 0.5), Bob = R(4, 12), Depth = R(0.75, 1.15) });
            for (int i = 0; i <= perW; i++)
            {
                double px = i / (double)perW * W;
                Add2(px + R(-20, 20), R(-10, edge), R(18, 46));
                Add2(px + R(-20, 20), H - R(-10, edge), R(18, 46));
            }
            for (int i = 1; i < perH; i++)
            {
                double py = i / (double)perH * H;
                Add2(R(-10, edge), py + R(-20, 20), R(18, 46));
                Add2(W - R(-10, edge), py + R(-20, 20), R(18, 46));
            }
            foreach (var c in new[] { new Point(0, 0), new Point(W, 0), new Point(0, H), new Point(W, H) })
                for (int k = 0; k < 3; k++) Add2(c.X + R(-edge, edge) * (c.X == 0 ? -0.2 : 1) * 0.6, c.Y + R(-edge, edge) * 0.6, R(50, 84));
            // draw the small (far) hearts first
            list.Sort((a, b) => (a.Size * a.Depth).CompareTo(b.Size * b.Depth));
            fh = list.ToArray();
            var bl = new System.Collections.Generic.List<Bokeh>();
            for (int i = 0; i < 46; i++)
            {
                bool nearEdge = i % 3 != 0;
                double bx = nearEdge ? (rnd.Next(2) == 0 ? R(0, W * 0.22) : R(W * 0.78, W)) : R(0, W);
                bl.Add(new Bokeh { X = bx, Y = R(0, H), R = R(8, 46), Phase = R(0, 6.28), Rise = R(10, 40), A = (byte)R(40, 110) });
            }
            bk = bl.ToArray();
        }

        // ---- magic glitter heart (sparkles fly in, form a big heart, shimmer, then dissolve)
        double glitT = -1;
        const double GlitLen = 4.0;
        const int GN = 380;
        readonly double[] gx0 = new double[GN], gy0 = new double[GN], gx = new double[GN], gy = new double[GN], gph = new double[GN], gsz = new double[GN], gdx = new double[GN], gdy = new double[GN];
        readonly int[] gcol = new int[GN];
        static readonly Brush[] GlitDot =
        {
            Frozen(new SolidColorBrush(Color.FromRgb(0xFF, 0xFF, 0xFF))), Frozen(new SolidColorBrush(Color.FromRgb(0xE3, 0x9B, 0xFF))),
            Frozen(new SolidColorBrush(Color.FromRgb(0xFF, 0x7A, 0xD9))), Frozen(new SolidColorBrush(Color.FromRgb(0xB0, 0x7C, 0xFF))),
        };
        static readonly Brush[] GlitGlow =
        {
            Frozen(new RadialGradientBrush(Color.FromArgb(140, 255, 255, 255), Color.FromArgb(0, 255, 255, 255))),
            Frozen(new RadialGradientBrush(Color.FromArgb(140, 0xC0, 0x6B, 0xFF), Color.FromArgb(0, 0xC0, 0x6B, 0xFF))),
            Frozen(new RadialGradientBrush(Color.FromArgb(140, 0xFF, 0x4F, 0xC8), Color.FromArgb(0, 0xFF, 0x4F, 0xC8))),
            Frozen(new RadialGradientBrush(Color.FromArgb(140, 0x8A, 0x5C, 0xFF), Color.FromArgb(0, 0x8A, 0x5C, 0xFF))),
        };

        void BuildGlitter()
        {
            double W = Math.Max(400, ActualWidth), H = Math.Max(300, ActualHeight);
            double cx = W / 2, cy = H / 2 - H * 0.03, sc = Math.Min(W, H) * 0.021;
            for (int i = 0; i < GN; i++)
            {
                // most on the outline (the classic heart curve), the rest scattered inside
                double t = R(0, Math.PI * 2);
                double hx = 16 * Math.Pow(Math.Sin(t), 3), hy = -(13 * Math.Cos(t) - 5 * Math.Cos(2 * t) - 2 * Math.Cos(3 * t) - Math.Cos(4 * t));
                double k = i % 4 == 0 ? Math.Sqrt(R(0, 0.85)) : R(0.96, 1.04);
                gx[i] = cx + hx * sc * k; gy[i] = cy + hy * sc * k;
                gx0[i] = R(-0.1, 1.1) * W; gy0[i] = R(-0.1, 1.1) * H;
                gph[i] = R(0, 6.28); gsz[i] = i % 4 == 0 ? R(0.8, 1.8) : R(1.2, 3.2);
                gdx[i] = R(-60, 60); gdy[i] = R(-160, -40);
                gcol[i] = rnd.Next(GlitDot.Length);
            }
        }

        void DrawGlitter(DrawingContext dc)
        {
            if (glitT < 0) return;
            double t = glitT;
            double inT = Math.Min(1, t / 1.0), outT = Math.Max(0, (t - (GlitLen - 1.1)) / 1.1);
            double gather = 1 - Math.Pow(1 - inT, 3);
            double beat = 1 + 0.045 * Math.Sin(t * 7) * (1 - outT);
            double W = ActualWidth, H = ActualHeight, cx = W / 2, cy = H / 2;
            // a soft purple glow behind the heart
            double ga = Math.Min(1, t / 0.6) * (1 - outT);
            double rr = Math.Min(W, H) * 0.42;
            dc.DrawEllipse(Op(GlitBack, ga), null, new Point(cx, cy), rr, rr);
            for (int i = 0; i < GN; i++)
            {
                double px = gx0[i] + (gx[i] - gx0[i]) * gather, py = gy0[i] + (gy[i] - gy0[i]) * gather;
                px = cx + (px - cx) * beat; py = cy + (py - cy) * beat;
                px += gdx[i] * outT * 1.5; py += gdy[i] * outT * 1.5;
                double tw = 0.35 + 0.65 * Math.Max(0, Math.Sin(time * 9 + gph[i]));
                double a = Math.Min(1, t / 0.3) * (1 - outT) * tw;
                if (a <= 0.02) continue;
                double sz = gsz[i] * (0.8 + tw * 0.6);
                dc.DrawEllipse(Op(GlitGlow[gcol[i]], a), null, new Point(px, py), sz * 3.2, sz * 3.2);
                if (i % 7 == 0)
                {
                    // star glints
                    var w = B(Colors.White, 255 * a);
                    dc.DrawRectangle(w, null, new Rect(px - sz * 0.25, py - sz * 2.6, sz * 0.5, sz * 5.2));
                    dc.DrawRectangle(w, null, new Rect(px - sz * 2.6, py - sz * 0.25, sz * 5.2, sz * 0.5));
                }
                else dc.DrawEllipse(B(GlitCol[gcol[i]], 255 * a), null, new Point(px, py), sz * 0.7, sz * 0.7);
            }
        }

        // ---- glowing particle explosion (a bright core flash, a shock wave, particles with light trails, floating embers)
        double expT = -1;
        const double ExpLen = 3.4;
        const int EN = 540;
        readonly double[] ex = new double[EN], ey = new double[EN], evx = new double[EN], evy = new double[EN], epx = new double[EN], epy = new double[EN], edl = new double[EN], esz = new double[EN], eph = new double[EN];
        readonly bool[] ember = new bool[EN];
        double expX, expY;
        Color expA = Color.FromRgb(0x2E, 0x9B, 0xFF), expB = Color.FromRgb(0x00, 0xE5, 0xFF);

        void BuildExplosion(Point c)
        {
            expX = c.X; expY = c.Y;
            for (int i = 0; i < EN; i++)
            {
                double ang = R(0, Math.PI * 2);
                // three waves: a fast outer shell, a medium cloud and slow embers that linger
                int wave = i % 3;
                double sp = wave == 0 ? R(700, 1300) : wave == 1 ? R(250, 700) : R(40, 220);
                ex[i] = epx[i] = expX + Math.Cos(ang) * R(0, 10); ey[i] = epy[i] = expY + Math.Sin(ang) * R(0, 10);
                evx[i] = Math.Cos(ang) * sp; evy[i] = Math.Sin(ang) * sp;
                edl[i] = wave * 0.06 + R(0, 0.05);
                esz[i] = wave == 2 ? R(1.5, 3.5) : R(1, 2.6);
                eph[i] = R(0, 6.28);
                ember[i] = wave == 2;
            }
        }

        void StepExplosion(double dt)
        {
            for (int i = 0; i < EN; i++)
            {
                if (expT < edl[i]) continue;
                epx[i] = ex[i]; epy[i] = ey[i];
                double drag = ember[i] ? 0.9 : 3.2;
                evx[i] *= Math.Max(0, 1 - drag * dt); evy[i] *= Math.Max(0, 1 - drag * dt);
                evy[i] += (ember[i] ? -14 : 40) * dt;              // embers float up, sparks fall a little
                if (ember[i]) evx[i] += Math.Sin(time * 2 + eph[i]) * 18 * dt;
                ex[i] += evx[i] * dt; ey[i] += evy[i] * dt;
            }
        }

        static Color Mix(Color a, Color b, double t) => Color.FromRgb((byte)(a.R + (b.R - a.R) * t), (byte)(a.G + (b.G - a.G) * t), (byte)(a.B + (b.B - a.B) * t));

        void DrawExplosion(DrawingContext dc)
        {
            if (expT < 0) return;
            double t = expT;
            // core flash
            double fl = Math.Max(0, 1 - t / 0.5);
            if (fl > 0)
            {
                double r = 40 + 260 * (1 - fl * fl);
                dc.DrawEllipse(new RadialGradientBrush(Color.FromArgb((byte)(255 * fl), 255, 255, 255), Color.FromArgb(0, expA.R, expA.G, expA.B)), null, new Point(expX, expY), r, r);
            }
            // shock wave
            if (t < 1.0)
            {
                double f = t / 1.0;
                double r = f * Math.Max(ActualWidth, ActualHeight) * 0.7;
                dc.DrawEllipse(null, new Pen(B(expB, 200 * (1 - f)), 10 * (1 - f) + 1), new Point(expX, expY), r, r);
                dc.DrawEllipse(null, new Pen(B(Colors.White, 120 * (1 - f)), 2), new Point(expX, expY), r * 0.96, r * 0.96);
            }
            double fade = t > ExpLen - 1.0 ? Math.Max(0, (ExpLen - t) / 1.0) : 1;
            for (int i = 0; i < EN; i++)
            {
                double age = t - edl[i];
                if (age < 0) continue;
                double life = ember[i] ? 1 : Math.Max(0, 1 - age / 1.9);
                double a = life * fade;
                if (a <= 0.02) continue;
                // white-hot first, then cyan, then deep blue
                double heat = Math.Min(1, age / 0.9);
                var c = heat < 0.5 ? Mix(Colors.White, expB, heat * 2) : Mix(expB, expA, (heat - 0.5) * 2);
                double tw = ember[i] ? 0.5 + 0.5 * Math.Sin(time * 5 + eph[i]) : 1;
                double sp = Math.Sqrt(evx[i] * evx[i] + evy[i] * evy[i]);
                if (!ember[i] && sp > 30)
                {
                    // light trail
                    double k = Math.Min(0.06, 18 / Math.Max(1, sp));
                    dc.DrawLine(P(c, 210 * a, esz[i], true),
                        new Point(ex[i] - evx[i] * k * 2.2, ey[i] - evy[i] * k * 2.2), new Point(ex[i], ey[i]));
                }
                dc.DrawEllipse(B(c, 90 * a * tw), null, new Point(ex[i], ey[i]), esz[i] * 3, esz[i] * 3);
                dc.DrawEllipse(B(Colors.White, 255 * a * tw), null, new Point(ex[i], ey[i]), esz[i] * 0.6, esz[i] * 0.6);
            }
        }

        /// <summary>"explosion", "frame", "glitter", "all" or "simple".</summary>
        public string Style = "explosion";

        double FrameAlpha()
        {
            if (frameT < 0) return 0;
            if (frameT < 0.45) return frameT / 0.45;
            if (frameT > FrameLen - 0.9) return Math.Max(0, (FrameLen - frameT) / 0.9);
            return 1;
        }

        void DrawFrame(DrawingContext dc)
        {
            double fa = FrameAlpha();
            if (fa <= 0) return;
            double W = ActualWidth, H = ActualHeight;
            // warm red glow around the window (vignette)
            dc.DrawRectangle(Op(Vignette, fa), null, new Rect(0, 0, W, H));
            // bokeh lights drifting up
            foreach (var b in bk)
            {
                double y2 = b.Y - b.Rise * frameT;
                double tw = 0.6 + 0.4 * Math.Sin(time * 1.6 + b.Phase);
                dc.DrawEllipse(Op(BokehBrush, b.A / 110.0 * fa * tw), null, new Point(b.X, y2), b.R, b.R);
            }
            // glossy 3D hearts turning in place
            foreach (var h in fh)
            {
                double t = frameT - h.Appear;
                if (t <= 0) continue;
                double pop = Overshoot(Math.Min(1, t / 0.45));
                double turn = Math.Cos(time * h.Spin + h.Phase);           // fake rotation around the vertical axis
                double sx = Math.Max(0.12, Math.Abs(turn)) * (turn >= 0 ? 1 : -1);
                double sz = h.Size * h.Depth * pop * 0.5;
                double bob = Math.Sin(time * 2 + h.Phase) * h.Bob;
                double ha = fa * Math.Min(1, t / 0.25);
                // soft shadow
                dc.PushTransform(new MatrixTransform(sz * Math.Abs(sx), 0, 0, sz, h.X + 3, h.Y + bob + 5));
                dc.DrawGeometry(Op(Shade, ha), null, Heart);
                dc.Pop();
                // body (front is bright and glossy, the back is darker while it turns)
                dc.PushTransform(new TranslateTransform(h.X, h.Y + bob));
                dc.PushTransform(new RotateTransform(Math.Sin(time + h.Phase) * 10));
                dc.PushTransform(new ScaleTransform(sz * Math.Abs(sx), sz));
                dc.DrawGeometry(Op(turn >= 0 ? GlossFront : GlossBack, ha), null, Heart);
                if (turn > 0.2)
                {
                    // specular highlights
                    dc.DrawEllipse(B(Colors.White, 190 * turn * ha), null, new Point(-0.42, -0.36), 0.17, 0.11);
                    dc.DrawEllipse(B(Colors.White, 90 * turn * ha), null, new Point(0.45, -0.38), 0.07, 0.05);
                }
                dc.Pop(); dc.Pop(); dc.Pop();
            }
            // sparkles twinkling around the frame
            for (int k = 0; k < 40; k++)
            {
                double px = (k * 97.13 % 1) * W, py = (k * 53.71 % 1) * H;
                if (k % 2 == 0) py = k % 4 == 0 ? (k * 13 % 60) : H - (k * 17 % 60); else px = k % 3 == 0 ? (k * 11 % 60) : W - (k * 19 % 60);
                double tw = Math.Max(0, Math.Sin(time * 6 + k * 1.7));
                var br = B(Colors.White, 255 * fa * tw);
                double ssz = 3 + (k % 4) * 1.5;
                dc.DrawRectangle(br, null, new Rect(px - ssz * 0.15, py - ssz, ssz * 0.3, ssz * 2));
                dc.DrawRectangle(br, null, new Rect(px - ssz, py - ssz * 0.15, ssz * 2, ssz * 0.3));
            }
        }

        public void SetColors(Color accent, Color second) =>
            colors = new[] { C(0xFF2D55), accent, second, C(0xFFC93C), C(0xFF5C8A), C(0xB388FF), Colors.White };

        public void Celebrate(Point center, double coverSize)
        {
            love = true; bigX = center.X; bigY = center.Y; bigSize = Math.Max(160, coverSize); bigT = 0; rings = 0;
            if (Style == "frame" || Style == "both" || Style == "all") { BuildFrame(); frameT = 0; }
            if (Style == "glitter" || Style == "both" || Style == "all") { BuildGlitter(); glitT = 0; }
            if (Style == "explosion" || Style == "all") { BuildExplosion(center); expT = 0; }
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
            if (frameT >= 0) { frameT += dt; if (frameT > FrameLen) frameT = -1; }
            if (glitT >= 0) { glitT += dt; if (glitT > GlitLen) glitT = -1; }
            if (expT >= 0) { expT += dt; StepExplosion(dt); if (expT > ExpLen) expT = -1; }
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
            if (bigT < 0 && count == 0 && rings == 0 && frameT < 0 && glitT < 0 && expT < 0) { CompositionTarget.Rendering -= Frame; running = false; }
        }

        // brushes and pens are cached (alpha in 32 steps) so hundreds of particles don't allocate new ones every frame
        static readonly System.Collections.Generic.Dictionary<uint, Brush> brushCache = new System.Collections.Generic.Dictionary<uint, Brush>();
        static readonly System.Collections.Generic.Dictionary<ulong, Pen> penCache = new System.Collections.Generic.Dictionary<ulong, Pen>();
        static Brush B(Color c, double a)
        {
            byte al = (byte)((int)Math.Max(0, Math.Min(255, a)) & 0xF8);
            uint key = (uint)(al << 24 | c.R << 16 | c.G << 8 | c.B);
            if (brushCache.TryGetValue(key, out var b)) return b;
            if (brushCache.Count > 4000) brushCache.Clear();
            b = new SolidColorBrush(Color.FromArgb(al, c.R, c.G, c.B)); b.Freeze();
            brushCache[key] = b; return b;
        }
        static Pen P(Color c, double a, double width, bool round)
        {
            byte al = (byte)((int)Math.Max(0, Math.Min(255, a)) & 0xF0);
            ulong key = ((ulong)(al << 24 | c.R << 16 | c.G << 8 | c.B) << 16) | ((ulong)Math.Round(width * 4) << 1) | (round ? 1UL : 0UL);
            if (penCache.TryGetValue(key, out var p)) return p;
            if (penCache.Count > 4000) penCache.Clear();
            p = new Pen(B(c, al), Math.Round(width * 4) / 4);
            if (round) { p.StartLineCap = PenLineCap.Round; p.EndLineCap = PenLineCap.Round; }
            p.Freeze(); penCache[key] = p; return p;
        }

        protected override void OnRender(DrawingContext dc)
        {
            double W = ActualWidth, H = ActualHeight;
            DrawFrame(dc);
            DrawGlitter(dc);
            DrawExplosion(dc);
            // shock-wave rings
            for (int k = 0; k < rings; k++)
            {
                double t = ringT[k]; if (t < 0) continue;
                double f = Math.Min(1, t / 0.9);
                var pen = P(colors[k % colors.Length], 220 * (1 - f), 14 * (1 - f) + 1, false);
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
                    var tail = P(col[i], (kind[i] == 3 ? 200 : 170) * a, size[i] * 0.9, true);
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
