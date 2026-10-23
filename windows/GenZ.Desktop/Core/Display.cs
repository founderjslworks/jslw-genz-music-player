using System;
using System.Linq;
using System.Runtime.InteropServices;

namespace GenZ.Core
{
    /// <summary>Monitor information: the highest refresh rate of the connected displays (60, 120, 144, 165, 240 Hz…).</summary>
    public static class Display
    {
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct DEVMODE
        {
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
            public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
            public int dmFields, dmPositionX, dmPositionY, dmDisplayOrientation, dmDisplayFixedOutput;
            public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
            public short dmLogPixels;
            public int dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency;
            public int dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2, dmPanningWidth, dmPanningHeight;
        }
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool EnumDisplaySettings(string device, int mode, ref DEVMODE dm);

        public static int RefreshRate()
        {
            int best = 60;
            try
            {
                foreach (var screen in System.Windows.Forms.Screen.AllScreens)
                {
                    var dm = new DEVMODE { dmSize = (short)Marshal.SizeOf(typeof(DEVMODE)) };
                    if (EnumDisplaySettings(screen.DeviceName, -1, ref dm) && dm.dmDisplayFrequency > 1) best = Math.Max(best, dm.dmDisplayFrequency);
                }
            }
            catch { }
            return Math.Max(60, Math.Min(360, best));
        }
    }
}
