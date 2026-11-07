using System;
using System.Collections.Generic;
using System.Linq;
using GenZ.Core;

namespace GenZ.Controls
{
    /// <summary>
    /// Album Flow layouts (same set as GEN-Z for Android). Each places a cover in 3D from its signed distance to the
    /// centre d (in covers): position, turn about each axis, size and whether its mirror reflection shows.
    /// Covers are 1×1 with their origin at the bottom centre.
    /// </summary>
    public static class FlowStyles
    {
        public static readonly List<(string Id, string Name)> All = new List<(string, string)>
        {
            ("classic", "Classic flow"),
            ("carousel", "3D carousel ring"),
            ("stack", "Card stack"),
            ("wave", "Wave"),
            ("fan", "Fan of cards"),
            ("crate", "Record crate"),
            ("depth", "Depth tunnel"),
        };

        public struct Pose { public double X, Y, Z, RotX, RotY, RotZ, Scale; public bool Reflection; }

        static string Valid(string id) => All.Any(a => a.Id == id) ? id : "classic";

        /// <summary>The style to draw: the user's pick, or the active theme's suggestion ("theme").</summary>
        public static string Current
        {
            get
            {
                var s = Settings.Current.FlowStyle;
                return Valid(string.IsNullOrEmpty(s) || s == "theme" ? ThemeManager.Current?.FlowStyle : s);
            }
        }

        public static string NameOf(string id) => All.First(a => a.Id == Valid(id)).Name;

        public static Pose Place(string style, double d)
        {
            double ad = Math.Abs(d), cl = Math.Max(-1, Math.Min(1, d)), sg = Math.Sign(d);
            var p = new Pose { Scale = 1, Reflection = true };
            switch (style)
            {
                case "carousel":
                {
                    // covers stand on a turning drum, facing outwards
                    double ang = Math.Max(-150, Math.Min(150, d * 34)), r = Math.PI / 180 * ang, R = 1.25;
                    p.X = Math.Sin(r) * R; p.Z = (Math.Cos(r) - 1) * R;
                    p.RotY = ang;
                    if (Math.Abs(ang) > 105) p.Scale = 0;
                    break;
                }
                case "stack":
                {
                    // a deck: the next covers peek out from behind the front one
                    double k = Math.Min(ad, 4);
                    p.X = sg * k * 0.16; p.Y = k * 0.07; p.Z = -k * 0.32;
                    if (ad > 4.2) p.Scale = 0;
                    p.Reflection = ad < 0.5;
                    break;
                }
                case "wave":
                    // flat covers riding a gentle wave
                    p.X = d * 0.78; p.Y = Math.Sin(d * 1.3) * 0.13; p.Z = -Math.Min(ad, 1.5) * 0.3;
                    p.RotZ = Math.Max(-12, Math.Min(12, -d * 5));
                    p.Reflection = false;
                    break;
                case "fan":
                {
                    // a hand of cards pivoting from far below
                    double th = Math.Max(-70, Math.Min(70, d * 15)) * Math.PI / 180, R = 1.9;
                    p.X = R * Math.Sin(th); p.Y = R * Math.Cos(th) - R; p.Z = -ad * 0.012;
                    p.RotZ = -th * 180 / Math.PI;
                    p.Reflection = false;
                    break;
                }
                case "crate":
                    // flicking records in a crate: side covers tip back and drop a little
                    p.RotX = -Math.Min(ad, 1) * 32;
                    p.RotY = cl * 22;
                    p.X = sg * (ad <= 1 ? ad * 0.9 : 0.9 + (ad - 1) * 0.66);
                    p.Y = -Math.Min(ad, 1) * 0.06; p.Z = -Math.Min(ad, 1) * 0.45;
                    p.Reflection = ad < 0.5;
                    break;
                case "depth":
                    // a tunnel: neighbours shrink into the distance
                    p.X = d * 0.62; p.Z = -ad * 0.95; p.RotY = cl * 14;
                    if (ad > 9) p.Scale = 0;
                    break;
                default:
                    p.X = sg * (ad <= 1 ? ad * 0.98 : 0.98 + (ad - 1) * 0.7);
                    p.Z = -Math.Min(ad, 1) * 0.55 - Math.Max(0, ad - 1) * 0.02;
                    p.RotY = cl * 55;
                    break;
            }
            return p;
        }
    }
}
