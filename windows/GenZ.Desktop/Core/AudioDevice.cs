using System;
using System.Linq;
using System.Runtime.InteropServices;
using CSCore.CoreAudioAPI;

namespace GenZ.Core
{
    /// <summary>
    /// What GEN-Z is playing on: the Windows default output (its own device name, e.g. a soundbar model), what kind of
    /// output it is, the Bluetooth device's battery when Windows knows it (Windows 10+), and this PC's battery.
    /// </summary>
    public static class AudioDevice
    {
        public enum Kind { Speakers, Headphones, Soundbar }

        public class Info
        {
            public string Endpoint;      // e.g. "Speakers (boAt Aavante Bar 610)"
            public string Name;          // e.g. "boAt Aavante Bar 610"
            public Kind Kind;
            public bool Hdmi;
            public int? DeviceBattery;   // 0..100 when the device reports it
            public int PcBattery = -1;   // -1 = desktop PC / unknown
            public bool Charging;
        }

        static readonly string[] BarWords = { "bar", "aavante", "theatre", "theater", "home", "tv", "cinema", "hts", "subwoofer", "sound system", "hdmi", "display audio" };
        static readonly string[] HeadWords = { "headphone", "headset", "hands-free", "handsfree", "earbud", "buds", "airpods", "earphone", "neckband", "rockerz", "airdopes" };

        public static bool IsSoundbarName(string n) { n = (n ?? "").ToLowerInvariant(); return BarWords.Any(n.Contains); }
        static bool IsHeadName(string n) { n = (n ?? "").ToLowerInvariant(); return HeadWords.Any(n.Contains); }

        public static Info Read(bool battery = true)
        {
            var i = new Info { Kind = Kind.Speakers };
            try
            {
                using (var en = new MMDeviceEnumerator())
                using (var dev = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia))
                {
                    i.Endpoint = dev.FriendlyName ?? "";
                    // Windows names endpoints "Speakers (Device name)" – the part in brackets is the device's own name
                    int a = i.Endpoint.IndexOf('('), b = i.Endpoint.LastIndexOf(')');
                    i.Name = a >= 0 && b > a ? i.Endpoint.Substring(a + 1, b - a - 1).Trim() : i.Endpoint;
                }
            }
            catch { }
            var all = (i.Endpoint ?? "") + " " + (i.Name ?? "");
            i.Hdmi = all.IndexOf("hdmi", StringComparison.OrdinalIgnoreCase) >= 0 || all.IndexOf("display audio", StringComparison.OrdinalIgnoreCase) >= 0
                     || all.IndexOf("NVIDIA High Definition", StringComparison.OrdinalIgnoreCase) >= 0;
            if (IsHeadName(all)) i.Kind = Kind.Headphones;
            else if (i.Hdmi || IsSoundbarName(i.Name)) i.Kind = Kind.Soundbar;
            if (string.IsNullOrWhiteSpace(i.Name) || i.Name.StartsWith("Realtek", StringComparison.OrdinalIgnoreCase) || i.Name.StartsWith("High Definition", StringComparison.OrdinalIgnoreCase))
                i.Name = null;   // the PC's own sound card – nothing interesting to show
            if (battery && i.Name != null) i.DeviceBattery = BatteryOf(i.Name);
            try
            {
                var ps = System.Windows.Forms.SystemInformation.PowerStatus;
                if ((ps.BatteryChargeStatus & System.Windows.Forms.BatteryChargeStatus.NoSystemBattery) == 0 && ps.BatteryLifePercent <= 1f)
                    i.PcBattery = (int)Math.Round(ps.BatteryLifePercent * 100);
                i.Charging = ps.PowerLineStatus == System.Windows.Forms.PowerLineStatus.Online;
            }
            catch { }
            return i;
        }

        // ---------------------------------------------------------------- Bluetooth battery (Windows 10 1809+)
        [StructLayout(LayoutKind.Sequential)]
        struct DEVPROPKEY { public Guid fmtid; public uint pid; }

        [StructLayout(LayoutKind.Sequential)]
        struct SP_DEVINFO_DATA { public uint cbSize; public Guid ClassGuid; public uint DevInst; public IntPtr Reserved; }

        [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr SetupDiGetClassDevsW(IntPtr classGuid, string enumerator, IntPtr hwnd, uint flags);
        [DllImport("setupapi.dll", SetLastError = true)]
        static extern bool SetupDiEnumDeviceInfo(IntPtr set, uint index, ref SP_DEVINFO_DATA data);
        [DllImport("setupapi.dll", SetLastError = true)]
        static extern bool SetupDiGetDevicePropertyW(IntPtr set, ref SP_DEVINFO_DATA data, ref DEVPROPKEY key, out uint type, byte[] buffer, uint size, out uint required, uint flags);
        [DllImport("setupapi.dll", SetLastError = true)]
        static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);

        const uint DIGCF_PRESENT = 0x2, DIGCF_ALLCLASSES = 0x4;
        static readonly DEVPROPKEY BatteryKey = new DEVPROPKEY { fmtid = new Guid("104EA319-6EE2-4701-BD47-8DDBF425BBE5"), pid = 2 };
        static readonly DEVPROPKEY FriendlyKey = new DEVPROPKEY { fmtid = new Guid("A45C254E-DF1C-4EFD-8020-67D146A850E0"), pid = 14 };
        static readonly DEVPROPKEY DescKey = new DEVPROPKEY { fmtid = new Guid("A45C254E-DF1C-4EFD-8020-67D146A850E0"), pid = 2 };

        /// <summary>The battery Windows shows in Settings › Bluetooth for a device with this name, or null.</summary>
        static int? BatteryOf(string name)
        {
            if (Environment.OSVersion.Version.Major < 10) return null;
            IntPtr set = IntPtr.Zero;
            try
            {
                set = SetupDiGetClassDevsW(IntPtr.Zero, null, IntPtr.Zero, DIGCF_PRESENT | DIGCF_ALLCLASSES);
                if (set == IntPtr.Zero || set == new IntPtr(-1)) return null;
                var buf = new byte[512];
                for (uint k = 0; k < 4000; k++)
                {
                    var d = new SP_DEVINFO_DATA { cbSize = (uint)Marshal.SizeOf(typeof(SP_DEVINFO_DATA)) };
                    if (!SetupDiEnumDeviceInfo(set, k, ref d)) break;
                    var bk = BatteryKey;
                    if (!SetupDiGetDevicePropertyW(set, ref d, ref bk, out _, buf, (uint)buf.Length, out uint got, 0) || got < 1) continue;
                    int level = buf[0];
                    if (level > 100) continue;
                    var n = Str(set, ref d, FriendlyKey, buf) ?? Str(set, ref d, DescKey, buf);
                    if (n == null) continue;
                    if (n.IndexOf(name, StringComparison.OrdinalIgnoreCase) >= 0 || name.IndexOf(n, StringComparison.OrdinalIgnoreCase) >= 0) return level;
                }
            }
            catch { }
            finally { if (set != IntPtr.Zero && set != new IntPtr(-1)) SetupDiDestroyDeviceInfoList(set); }
            return null;
        }

        static string Str(IntPtr set, ref SP_DEVINFO_DATA d, DEVPROPKEY key, byte[] buf)
        {
            Array.Clear(buf, 0, buf.Length);
            if (!SetupDiGetDevicePropertyW(set, ref d, ref key, out _, buf, (uint)buf.Length, out uint got, 0) || got < 2) return null;
            var s = System.Text.Encoding.Unicode.GetString(buf, 0, (int)got).TrimEnd('\0');
            return s.Length > 0 ? s : null;
        }
    }
}
