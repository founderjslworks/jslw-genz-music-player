using System;
using System.Runtime.InteropServices;
using System.Windows.Media;

namespace GenZ.Core
{
    /// <summary>
    /// Old-hardware support. "auto" looks at the graphics tier, processor cores and memory once at start-up;
    /// on a weak PC GEN-Z runs in Light mode: no star field, sidebar or scroll effects, simple fades, a capped
    /// frame rate and lighter image scaling. "best" and "light" force a mode. Software drawing (safe mode)
    /// is a separate switch for old or faulty graphics drivers.
    /// </summary>
    public static class Perf
    {
        public static bool Light { get; set; }
        public static string Reason { get; private set; } = "";
        public static int Tier { get; private set; } = 2;
        public static int Cores { get; private set; } = Environment.ProcessorCount;
        public static double RamGb { get; private set; }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
        class MEMORYSTATUSEX
        {
            public uint dwLength = (uint)Marshal.SizeOf(typeof(MEMORYSTATUSEX));
            public uint dwMemoryLoad; public ulong ullTotalPhys, ullAvailPhys, ullTotalPageFile, ullAvailPageFile, ullTotalVirtual, ullAvailVirtual, ullAvailExtendedVirtual;
        }
        [DllImport("kernel32.dll", CharSet = CharSet.Auto, SetLastError = true)]
        static extern bool GlobalMemoryStatusEx([In, Out] MEMORYSTATUSEX m);

        /// <summary>Call once on the UI thread after Settings.Load().</summary>
        public static void Decide(bool forceBest = false)
        {
            try { Tier = RenderCapability.Tier >> 16; } catch { Tier = 2; }
            try { var m = new MEMORYSTATUSEX(); if (GlobalMemoryStatusEx(m)) RamGb = m.ullTotalPhys / 1073741824.0; } catch { }
            string mode = Settings.Current.Performance ?? "auto";
            if (forceBest) { Light = false; Reason = ""; return; }
            if (mode == "light") { Light = true; Reason = "you chose Light"; return; }
            if (mode == "best") { Light = false; Reason = ""; return; }
            if (Settings.Current.SoftwareRendering) { Light = true; Reason = "software drawing is on"; return; }
            if (Tier < 2) { Light = true; Reason = "basic graphics"; return; }
            if (Cores <= 2) { Light = true; Reason = Cores + "-core processor"; return; }
            if (RamGb > 0 && RamGb < 3.5) { Light = true; Reason = RamGb.ToString("0.#") + " GB memory"; return; }
            Light = false; Reason = "";
        }

        /// <summary>Plain-language summary of this PC for the Settings page.</summary>
        public static string Summary()
        {
            string g = Tier >= 2 ? "full graphics acceleration" : Tier == 1 ? "partial graphics acceleration" : "software graphics";
            string ram = RamGb > 0 ? $" · {RamGb:0.#} GB memory" : "";
            return $"This PC: {Cores} processor cores{ram} · {g}.";
        }

        /// <summary>The bitmap scaling GEN-Z uses for covers: high quality, or faster linear filtering in Light mode.</summary>
        public static BitmapScalingMode Scaling => Light ? BitmapScalingMode.Linear : BitmapScalingMode.HighQuality;
    }
}
