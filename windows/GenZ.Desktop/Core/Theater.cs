using System;
using System.Collections.Generic;
using CSCore;

namespace GenZ.Core
{
    /// <summary>Settings for one sound profile of the GEN-Z Home Theater engine.</summary>
    public class TheaterProfile
    {
        public string Id, Name, Blurb, Icon;
        public double Width = 1, Room, Dialogue, Leveler, Bass, Treble;
        public TheaterProfile Copy() => (TheaterProfile)MemberwiseClone();
    }

    /// <summary>
    /// GEN-Z Home Theater – our own sound engine that runs only inside GEN-Z (nothing is installed in Windows and it
    /// stops when the app closes): surround widening, room ambience, a dialogue enhancer, a volume leveller, bass and
    /// treble shaping, headphone crossfeed and a soft limiter. It also measures the live level for the animated sliders.
    /// </summary>
    public class Theater : ISampleSource
    {
        public static readonly List<TheaterProfile> Profiles = new List<TheaterProfile>
        {
            new TheaterProfile { Id = "movie", Name = "Movie", Icon = "IcMovie", Blurb = "Wide surround, room ambience and clear dialogue", Width = 1.65, Room = 0.45, Dialogue = 4, Leveler = 0.6, Bass = 4, Treble = 1.5 },
            new TheaterProfile { Id = "music", Name = "Music", Icon = "IcNote", Blurb = "Wider stereo stage with warm bass and airy highs", Width = 1.35, Room = 0.15, Dialogue = 0, Leveler = 0.25, Bass = 3, Treble = 2 },
            new TheaterProfile { Id = "game", Name = "Game", Icon = "IcGame", Blurb = "Very wide and punchy – hear where things are", Width = 1.8, Room = 0.25, Dialogue = 2, Leveler = 0, Bass = 4, Treble = 3 },
            new TheaterProfile { Id = "voice", Name = "Voice", Icon = "IcVoice", Blurb = "Podcasts, talks and devotional speech, crystal clear", Width = 1.0, Room = 0, Dialogue = 6, Leveler = 0.8, Bass = -2, Treble = 1 },
            new TheaterProfile { Id = "custom", Name = "Custom", Icon = "IcSettings", Blurb = "Your own mix – move the sliders", Width = 1.4, Room = 0.2, Dialogue = 2, Leveler = 0.3, Bass = 2, Treble = 1 },
        };

        readonly ISampleSource src;
        readonly int ch, fs;
        public WaveFormat WaveFormat => src.WaveFormat;
        public bool CanSeek => src.CanSeek;
        public long Position { get => src.Position; set => src.Position = value; }
        public long Length => src.Length;

        // live parameters (set from the UI thread, read on the audio thread)
        public volatile bool Enabled;
        public volatile bool Headphones;
        double width = 1, room, dialogueDb, leveler, bassDb, trebleDb;

        Biquad[] bass, treble;
        Biquad dlg1, dlg2;
        readonly float[][] delay;   // per channel ring buffers for room and crossfeed
        int dpos;
        readonly int dlen;
        double env = 1e-4, gain = 1, xfL, xfR;

        /// <summary>Peak level of the most recent audio (0..1), for the live volume animation.</summary>
        public double Level { get; private set; }

        public Theater(ISampleSource source)
        {
            src = source;
            ch = Math.Max(1, source.WaveFormat.Channels);
            fs = Math.Max(8000, source.WaveFormat.SampleRate);
            dlen = fs / 10; // 100 ms
            delay = new float[ch][];
            for (int c = 0; c < ch; c++) delay[c] = new float[dlen];
            Set(Profiles[1], false, false);
        }

        public void Set(TheaterProfile p, bool on, bool headphones)
        {
            width = p.Width; room = p.Room; dialogueDb = p.Dialogue; leveler = p.Leveler; bassDb = p.Bass; trebleDb = p.Treble;
            Headphones = headphones;
            var b = new Biquad[ch]; var t = new Biquad[ch];
            for (int c = 0; c < ch; c++) { b[c] = Biquad.LowShelf(fs, 110, bassDb); t[c] = Biquad.HighShelf(fs, 7500, trebleDb); }
            bass = b; treble = t;
            dlg1 = Biquad.Peak(fs, 2600, 0.9, dialogueDb);
            dlg2 = Biquad.Peak(fs, 1100, 1.2, dialogueDb * 0.5);
            Enabled = on;
        }

        public int Read(float[] buffer, int offset, int count)
        {
            int n = src.Read(buffer, offset, count);
            if (n <= 0) { Level *= 0.8; return n; }
            if (Enabled) Process(buffer, offset, n);
            float peak = 0;
            for (int i = offset; i < offset + n; i++) { var a = Math.Abs(buffer[i]); if (a > peak) peak = a; }
            Level = Math.Max(peak, Level * 0.85);
            return n;
        }

        void Process(float[] buf, int off, int n)
        {
            var b = bass; var t = treble;
            int frames = n / ch;
            bool stereo = ch == 2;
            int d7 = fs * 7 / 1000, d13 = fs * 13 / 1000, d23 = fs * 23 / 1000, d15 = fs * 15 / 1000, dx = Math.Max(1, fs * 3 / 10000);
            double attack = Math.Exp(-1.0 / (0.02 * fs)), release = Math.Exp(-1.0 / (0.5 * fs)), smooth = Math.Exp(-1.0 / (0.25 * fs));
            double xa = Math.Exp(-2 * Math.PI * 700.0 / fs); // crossfeed low-pass
            for (int f = 0; f < frames; f++)
            {
                int i = off + f * ch;
                // tone
                for (int c = 0; c < ch; c++) buf[i + c] = (float)t[c].Run(b[c].Run(buf[i + c]));
                if (stereo)
                {
                    double L = buf[i], R = buf[i + 1];
                    double M = (L + R) * 0.5, S = (L - R) * 0.5;
                    // dialogue lives in the centre (mid) channel
                    if (dialogueDb != 0) M = dlg2.Run(dlg1.Run(M));
                    // surround: widen the side signal and add a Haas-delayed copy for depth
                    int p15 = (dpos - d15 + dlen) % dlen;
                    double Sd = (delay[0][p15] - delay[1][p15]) * 0.5;
                    S = S * width + Sd * room * 0.5;
                    L = M + S; R = M - S;
                    // room: a few early reflections, crossed between the ears
                    if (room > 0)
                    {
                        int a7 = (dpos - d7 + dlen) % dlen, a13 = (dpos - d13 + dlen) % dlen, a23 = (dpos - d23 + dlen) % dlen;
                        L += room * (0.22 * delay[0][a7] + 0.16 * delay[1][a13] + 0.11 * delay[0][a23]);
                        R += room * (0.22 * delay[1][a7] + 0.16 * delay[0][a13] + 0.11 * delay[1][a23]);
                    }
                    // headphones: soft crossfeed so the wide sound does not tire the ears
                    if (Headphones)
                    {
                        int ax = (dpos - dx + dlen) % dlen;
                        xfL = (1 - xa) * delay[1][ax] + xa * xfL;
                        xfR = (1 - xa) * delay[0][ax] + xa * xfR;
                        L = L * 0.86 + xfL * 0.3; R = R * 0.86 + xfR * 0.3;
                    }
                    delay[0][dpos] = buf[i]; delay[1][dpos] = buf[i + 1];
                    buf[i] = (float)L; buf[i + 1] = (float)R;
                }
                else for (int c = 0; c < ch; c++) delay[c][dpos] = buf[i + c];
                dpos = (dpos + 1) % dlen;

                // volume leveller: slow automatic gain towards a steady loudness
                double lvl = 0;
                for (int c = 0; c < ch; c++) lvl = Math.Max(lvl, Math.Abs(buf[i + c]));
                env = lvl > env ? attack * env + (1 - attack) * lvl : release * env + (1 - release) * lvl;
                double target = 1;
                if (leveler > 0 && env > 0.003) target = Math.Max(0.5, Math.Min(3.2, 0.32 / env));
                target = 1 + (target - 1) * leveler;
                gain = smooth * gain + (1 - smooth) * target;
                // headroom for the boosts, then a soft limiter
                double g = gain * Headroom();
                for (int c = 0; c < ch; c++) buf[i + c] = Limit(buf[i + c] * g);
            }
        }

        double Headroom() => Math.Pow(10, -Math.Max(0, Math.Max(bassDb, Math.Max(trebleDb, dialogueDb))) * 0.5 / 20) / Math.Max(1, (width - 1) * 0.35 + 1);

        static float Limit(double x)
        {
            double a = Math.Abs(x);
            if (a <= 0.9) return (float)x;
            return (float)(Math.Sign(x) * (0.9 + 0.1 * Math.Tanh((a - 0.9) / 0.1)));
        }

        public void Dispose() => src.Dispose();

        /// <summary>RBJ cookbook biquad filter.</summary>
        class Biquad
        {
            double b0, b1, b2, a1, a2, z1, z2;
            public double Run(double x)
            {
                double y = b0 * x + z1;
                z1 = b1 * x - a1 * y + z2;
                z2 = b2 * x - a2 * y;
                return y;
            }
            static Biquad Make(double b0, double b1, double b2, double a0, double a1, double a2)
                => new Biquad { b0 = b0 / a0, b1 = b1 / a0, b2 = b2 / a0, a1 = a1 / a0, a2 = a2 / a0 };

            public static Biquad Peak(int fs, double f, double q, double db)
            {
                double A = Math.Pow(10, db / 40), w = 2 * Math.PI * f / fs, al = Math.Sin(w) / (2 * q), cw = Math.Cos(w);
                return Make(1 + al * A, -2 * cw, 1 - al * A, 1 + al / A, -2 * cw, 1 - al / A);
            }
            public static Biquad LowShelf(int fs, double f, double db)
            {
                double A = Math.Pow(10, db / 40), w = 2 * Math.PI * f / fs, cw = Math.Cos(w), al = Math.Sin(w) / 2 * Math.Sqrt(2), sa = 2 * Math.Sqrt(A) * al;
                return Make(A * ((A + 1) - (A - 1) * cw + sa), 2 * A * ((A - 1) - (A + 1) * cw), A * ((A + 1) - (A - 1) * cw - sa),
                            (A + 1) + (A - 1) * cw + sa, -2 * ((A - 1) + (A + 1) * cw), (A + 1) + (A - 1) * cw - sa);
            }
            public static Biquad HighShelf(int fs, double f, double db)
            {
                double A = Math.Pow(10, db / 40), w = 2 * Math.PI * f / fs, cw = Math.Cos(w), al = Math.Sin(w) / 2 * Math.Sqrt(2), sa = 2 * Math.Sqrt(A) * al;
                return Make(A * ((A + 1) + (A - 1) * cw + sa), -2 * A * ((A - 1) + (A + 1) * cw), A * ((A + 1) + (A - 1) * cw - sa),
                            (A + 1) - (A - 1) * cw + sa, 2 * ((A - 1) - (A + 1) * cw), (A + 1) - (A - 1) * cw - sa);
            }
        }
    }
}
