using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading.Tasks;

namespace GenZ.Core
{
    /// <summary>
    /// Sharing songs: AIR share (JSL WORKS' offline Wi-Fi sharing app), the Windows share sheet (Windows 10 / 11 –
    /// Nearby sharing, Mail, WhatsApp, Phone Link …) and copying the files to paste anywhere.
    /// </summary>
    public static class Sharing
    {
        // ---------------------------------------------------------------- AIR share
        /// <summary>AIR share's program file, found from its installer's entry in Windows, or null.</summary>
        public static string AirShareExe()
        {
            foreach (var hive in new[] { Microsoft.Win32.Registry.CurrentUser, Microsoft.Win32.Registry.LocalMachine })
                foreach (var root in new[] { @"Software\Microsoft\Windows\CurrentVersion\Uninstall", @"Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall" })
                {
                    try
                    {
                        using (var k = hive.OpenSubKey(root))
                        {
                            if (k == null) continue;
                            foreach (var name in k.GetSubKeyNames())
                                using (var app = k.OpenSubKey(name))
                                {
                                    var dn = app?.GetValue("DisplayName") as string;
                                    if (dn == null || dn.IndexOf("AIR share", StringComparison.OrdinalIgnoreCase) < 0) continue;
                                    var icon = (app.GetValue("DisplayIcon") as string ?? "").Split(',')[0].Trim('"');
                                    if (icon.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) && File.Exists(icon)) return icon;
                                    var dir = app.GetValue("InstallLocation") as string;
                                    if (!string.IsNullOrEmpty(dir) && Directory.Exists(dir))
                                    {
                                        var exe = Directory.GetFiles(dir, "*.exe").FirstOrDefault(f => !Path.GetFileName(f).StartsWith("unins", StringComparison.OrdinalIgnoreCase));
                                        if (exe != null) return exe;
                                    }
                                }
                        }
                    }
                    catch { }
                }
            return null;
        }

        /// <summary>Opens AIR share with the files ready to send. False when AIR share isn't installed.</summary>
        public static bool SendWithAirShare(IList<string> files)
        {
            var exe = AirShareExe();
            if (exe == null) return false;
            var args = "--send " + string.Join(" ", files.Select(f => "\"" + f + "\""));
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(exe, args) { UseShellExecute = true });
            return true;
        }

        // ---------------------------------------------------------------- copy
        /// <summary>Puts the files on the clipboard – paste them into WhatsApp, Telegram, Explorer, a USB drive …</summary>
        public static void CopyFiles(IList<string> files)
        {
            var list = new System.Collections.Specialized.StringCollection();
            list.AddRange(files.ToArray());
            System.Windows.Clipboard.SetFileDropList(list);
        }

        // ---------------------------------------------------------------- Windows share sheet (Windows 10 / 11)
        public static bool ShareSheetAvailable => Environment.OSVersion.Version.Major >= 10;

        [ComImport, Guid("3A3DCD6C-3EAB-43DC-BCDE-45671CE800C8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IDataTransferManagerInterop
        {
            IntPtr GetForWindow([In] IntPtr appWindow, [In] ref Guid riid);
            void ShowShareUIForWindow(IntPtr appWindow);
        }

        static readonly Guid DtmIid = new Guid(0xa5caee9b, 0x8708, 0x49d1, 0x8d, 0x36, 0x67, 0xd2, 0x5a, 0x8d, 0xa0, 0x0c);

        /// <summary>Shows Windows' own Share panel for the files.</summary>
        public static void ShareSheet(IntPtr hwnd, IList<string> files, string title) => ShareSheetCore(hwnd, files, title);

        // kept in its own method so Windows 7 / 8 never load the Windows 10 types
        [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
        static void ShareSheetCore(IntPtr hwnd, IList<string> files, string title)
        {
            var interop = (IDataTransferManagerInterop)System.Runtime.InteropServices.WindowsRuntime.WindowsRuntimeMarshal
                .GetActivationFactory(typeof(global::Windows.ApplicationModel.DataTransfer.DataTransferManager));
            var iid = DtmIid;
            var ptr = interop.GetForWindow(hwnd, ref iid);
            var dtm = (global::Windows.ApplicationModel.DataTransfer.DataTransferManager)Marshal.GetObjectForIUnknown(ptr);
            global::Windows.Foundation.TypedEventHandler<global::Windows.ApplicationModel.DataTransfer.DataTransferManager, global::Windows.ApplicationModel.DataTransfer.DataRequestedEventArgs> handler = null;
            handler = async (s, e) =>
            {
                dtm.DataRequested -= handler;
                var def = e.Request.GetDeferral();
                try
                {
                    e.Request.Data.Properties.Title = title;
                    e.Request.Data.Properties.Description = "Shared from GEN-Z Music";
                    var items = new List<global::Windows.Storage.IStorageItem>();
                    foreach (var f in files)
                        items.Add(await global::Windows.Storage.StorageFile.GetFileFromPathAsync(f));
                    e.Request.Data.SetStorageItems(items);
                }
                catch (Exception ex) { e.Request.FailWithDisplayText("Couldn't share: " + ex.Message); }
                finally { def.Complete(); }
            };
            dtm.DataRequested += handler;
            interop.ShowShareUIForWindow(hwnd);
        }
    }
}
